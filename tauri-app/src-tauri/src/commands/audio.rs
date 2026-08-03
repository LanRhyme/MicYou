use crate::server::ServerState;
use cpal::traits::{DeviceTrait, HostTrait};
use micyou_audio::dsp::AudioDspSettings;
use tauri::{AppHandle, State};

#[derive(serde::Serialize)]
pub struct PipeWireStatus {
    pub available: bool,
    pub setup: bool,
    pub device_exists: bool,
}

#[cfg(target_os = "linux")]
#[tauri::command]
pub fn check_pipewire() -> PipeWireStatus {
    PipeWireStatus {
        available: crate::pipewire::is_available(),
        setup: crate::pipewire::is_setup(),
        device_exists: crate::pipewire::device_exists(),
    }
}

#[cfg(not(target_os = "linux"))]
#[tauri::command]
pub fn check_pipewire() -> PipeWireStatus {
    PipeWireStatus {
        available: false,
        setup: false,
        device_exists: false,
    }
}

#[tauri::command]
pub fn get_audio_devices() -> Vec<String> {
    let mut names = Vec::new();
    let host = cpal::default_host();
    if let Ok(devices) = host.output_devices() {
        for dev in devices {
            if let Ok(name) = dev.name() {
                names.push(name);
            }
        }
    }
    names.sort();
    names.dedup();
    names
}

#[tauri::command]
pub fn update_audio_settings(
    state: State<'_, ServerState>,
    mut settings: AudioDspSettings,
) -> Result<String, String> {
    settings.normalize();
    // AEC must always run first in the processing chain
    if let Some(pos) = settings.processing_chain.iter().position(|s| s == "AEC") {
        if pos != 0 {
            let stage = settings.processing_chain.remove(pos);
            settings.processing_chain.insert(0, stage);
        }
    }
    match state.dsp_settings.write() {
        Ok(mut current) => {
            *current = settings.clone();
            // Persist to the shared settings.json so the CLI sees the same values
            crate::app_config::save_dsp_settings(&settings)
                .map_err(|e| format!("Failed to persist settings: {e}"))?;
            Ok("Settings updated".to_string())
        }
        Err(e) => Err(format!("Failed to update settings: {}", e)),
    }
}

/// Whether the shared server.json exists (used by the GUI to migrate
/// pre-sync localStorage values on first run of a new version).
#[tauri::command]
pub fn server_prefs_exists() -> bool {
    std::path::Path::new(&crate::app_config::server_prefs_path()).exists()
}

/// Current DSP settings.
/// Prefers the shared settings.json so edits made by the CLI are reflected;
/// falls back to the in-memory state when the file is unreadable.
#[tauri::command]
pub fn get_audio_settings(state: State<'_, ServerState>) -> Result<AudioDspSettings, String> {
    if std::path::Path::new(&crate::app_config::settings_path()).exists() {
        return Ok(crate::app_config::load_dsp_settings());
    }
    state
        .dsp_settings
        .read()
        .map(|s| s.clone())
        .map_err(|e| format!("Failed to read settings: {}", e))
}

/// Connection-level settings shared with the CLI (server.json).
#[tauri::command]
pub fn get_server_prefs() -> crate::app_config::ServerPrefs {
    crate::app_config::load_server_prefs()
}

#[tauri::command]
pub fn save_server_prefs(prefs: crate::app_config::ServerPrefs) -> Result<String, String> {
    crate::app_config::save_server_prefs(&prefs)?;
    Ok("Server prefs saved".to_string())
}

#[tauri::command]
pub async fn set_mute_state(
    _app: AppHandle,
    state: State<'_, ServerState>,
    is_muted: bool,
) -> Result<(), String> {
    let mute_msg = micyou_protocol::micyou::MessageWrapper {
        audio_packet: None,
        connect: None,
        mute: Some(micyou_protocol::micyou::MuteMessage { is_muted }),
        ping: None,
        pong: None,
    };

    let tx = {
        let lock = state.active_connection.lock().await;
        lock.as_ref().map(|connection| connection.sender.clone())
    };
    if let Some(tx) = tx {
        tx.send(mute_msg).await.map_err(|e| e.to_string())?;
        Ok(())
    } else {
        Err("No active connection".to_string())
    }
}

#[tauri::command]
pub async fn set_monitoring(
    app: AppHandle,
    state: State<'_, ServerState>,
    enabled: bool,
) -> Result<(), String> {
    use tauri::Emitter;
    state
        .is_monitoring
        .store(enabled, std::sync::atomic::Ordering::Relaxed);
    let _ = app.emit("monitoring-enabled-changed", enabled);
    Ok(())
}

#[tauri::command]
pub fn set_spectrum_streaming(state: State<'_, ServerState>, enabled: bool) {
    state
        .spectrum_streaming_enabled
        .store(enabled, std::sync::atomic::Ordering::Release);
}

#[tauri::command]
pub async fn check_blackhole() -> Result<crate::blackhole::BlackHoleStatus, String> {
    crate::blackhole::check_blackhole().await
}

#[tauri::command]
pub async fn set_blackhole_as_input() -> Result<crate::blackhole::BlackHoleResult, String> {
    crate::blackhole::set_blackhole_as_input().await
}

#[tauri::command]
pub async fn restore_input_device() -> Result<crate::blackhole::BlackHoleResult, String> {
    crate::blackhole::restore_input_device().await
}

#[tauri::command]
pub async fn check_vbcable() -> Result<bool, String> {
    Ok(crate::vbcable::is_installed())
}

#[cfg(feature = "vbcable")]
#[tauri::command]
pub async fn install_vbcable(
    app: tauri::AppHandle,
) -> Result<crate::vbcable::VBCableResult, String> {
    Ok(crate::vbcable::install(std::sync::Arc::new(crate::events::TauriEventSink(app))).await)
}

#[cfg(not(feature = "vbcable"))]
#[tauri::command]
pub fn install_vbcable() -> Result<crate::vbcable::VBCableResult, String> {
    Ok(crate::vbcable::VBCableResult {
        success: false,
        error_type: Some("feature_disabled".to_string()),
        message: Some("VB-Cable installation feature not enabled".to_string()),
    })
}
