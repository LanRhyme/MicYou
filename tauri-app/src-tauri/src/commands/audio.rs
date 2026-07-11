use tauri::{AppHandle, State};
use cpal::traits::{DeviceTrait, HostTrait};
use crate::server::ServerState;
use micyou_audio::dsp::AudioDspSettings;

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
pub fn update_audio_settings(state: State<'_, ServerState>, settings: AudioDspSettings) -> Result<String, String> {
    match state.dsp_settings.write() {
        Ok(mut current) => {
            *current = settings;
            Ok("Settings updated".to_string())
        }
        Err(e) => Err(format!("Failed to update settings: {}", e)),
    }
}

#[tauri::command]
pub async fn set_mute_state(_app: AppHandle, state: State<'_, ServerState>, is_muted: bool) -> Result<(), String> {
    let mute_msg = micyou_protocol::micyou::MessageWrapper {
        audio_packet: None,
        connect: None,
        mute: Some(micyou_protocol::micyou::MuteMessage { is_muted }),
        plugin_sync: None,
        ping: None,
        pong: None,
    };

    let lock = state.connection_tx.lock().await;
    if let Some(tx) = lock.as_ref() {
        tx.send(mute_msg).await.map_err(|e| e.to_string())?;
        Ok(())
    } else {
        Err("No active connection".to_string())
    }
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
pub async fn install_vbcable(app: tauri::AppHandle) -> Result<crate::vbcable::VBCableResult, String> {
    Ok(crate::vbcable::install(app).await)
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
