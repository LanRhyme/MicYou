use crate::error::AppError;
use crate::server::state::ServerState;
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
        available: crate::platform::linux_pipewire::is_available(),
        setup: crate::platform::linux_pipewire::is_setup(),
        device_exists: crate::platform::linux_pipewire::device_exists(),
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
    settings: AudioDspSettings,
) -> Result<String, AppError> {
    match state.dsp_settings.write() {
        Ok(mut current) => {
            *current = settings;
            Ok("Settings updated".to_string())
        }
        Err(e) => Err(AppError::audio(format!("Failed to update settings: {}", e))),
    }
}

#[tauri::command]
pub async fn set_mute_state(
    _app: AppHandle,
    state: State<'_, ServerState>,
    is_muted: bool,
) -> Result<(), AppError> {
    let mute_msg = micyou_protocol::micyou::MessageWrapper {
        audio_packet: None,
        connect: None,
        mute: Some(micyou_protocol::micyou::MuteMessage { is_muted }),
        plugin_sync: None,
        ping: None,
        pong: None,
    };

    let tx_opt = {
        let handle_lock = state.server_handle.lock().await;
        handle_lock.as_ref().map(|h| h.connection_sender())
    };
    if let Some(conn_arc) = tx_opt {
        let result = {
            let tx_guard = conn_arc.lock().await;
            match tx_guard.as_ref() {
                Some(tx) => tx
                    .send(mute_msg)
                    .await
                    .map_err(|e| AppError::network(e.to_string())),
                None => Err(AppError::no_connection()),
            }
        };
        result
    } else {
        Err(AppError::server_not_running())
    }
}

#[cfg(target_os = "macos")]
#[tauri::command]
pub async fn check_blackhole() -> Result<crate::platform::macos_blackhole::BlackHoleStatus, AppError>
{
    crate::platform::macos_blackhole::check_blackhole()
        .await
        .map_err(AppError::platform)
}

#[cfg(not(target_os = "macos"))]
#[tauri::command]
pub async fn check_blackhole() -> Result<crate::platform::macos_blackhole::BlackHoleStatus, AppError>
{
    Err(AppError::platform("BlackHole is only supported on macOS"))
}

#[cfg(target_os = "macos")]
#[tauri::command]
pub async fn set_blackhole_as_input(
) -> Result<crate::platform::macos_blackhole::BlackHoleResult, AppError> {
    crate::platform::macos_blackhole::set_blackhole_as_input()
        .await
        .map_err(AppError::platform)
}

#[cfg(not(target_os = "macos"))]
#[tauri::command]
pub async fn set_blackhole_as_input(
) -> Result<crate::platform::macos_blackhole::BlackHoleResult, AppError> {
    Err(AppError::platform("BlackHole is only supported on macOS"))
}

#[cfg(target_os = "macos")]
#[tauri::command]
pub async fn restore_input_device(
) -> Result<crate::platform::macos_blackhole::BlackHoleResult, AppError> {
    crate::platform::macos_blackhole::restore_input_device()
        .await
        .map_err(AppError::platform)
}

#[cfg(not(target_os = "macos"))]
#[tauri::command]
pub async fn restore_input_device(
) -> Result<crate::platform::macos_blackhole::BlackHoleResult, AppError> {
    Err(AppError::platform("BlackHole is only supported on macOS"))
}

#[cfg(target_os = "windows")]
#[tauri::command]
pub async fn check_vbcable() -> Result<bool, AppError> {
    Ok(crate::platform::windows_vbcable::is_installed())
}

#[cfg(not(target_os = "windows"))]
#[tauri::command]
pub async fn check_vbcable() -> Result<bool, AppError> {
    Ok(false)
}

#[cfg(all(feature = "vbcable", target_os = "windows"))]
#[tauri::command]
pub async fn install_vbcable(
    app: tauri::AppHandle,
) -> Result<crate::platform::windows_vbcable::VBCableResult, AppError> {
    Ok(crate::platform::windows_vbcable::install(app).await)
}

#[cfg(not(all(feature = "vbcable", target_os = "windows")))]
#[tauri::command]
pub fn install_vbcable() -> Result<crate::platform::windows_vbcable::VBCableResult, AppError> {
    Ok(crate::platform::windows_vbcable::VBCableResult {
        success: false,
        error_type: Some("feature_disabled".to_string()),
        message: Some("VB-Cable installation is only supported on Windows".to_string()),
    })
}
