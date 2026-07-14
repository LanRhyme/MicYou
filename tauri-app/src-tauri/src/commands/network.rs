use crate::error::AppError;
use crate::network::interface::{list_interfaces, NetworkInfo, NetworkInterfaceInfo};
use crate::platform::adb;
use crate::server::state::ServerState;
use tauri::State;

#[cfg(feature = "adb")]
#[tauri::command]
pub fn enable_usb_mode(
    port: u16,
    device_serial: Option<String>,
) -> Result<adb::UsbModeResult, AppError> {
    adb::enable_usb_mode(port, device_serial.as_deref()).map_err(AppError::adb)
}

#[cfg(not(feature = "adb"))]
#[tauri::command]
pub fn enable_usb_mode(
    _port: u16,
    _device_serial: Option<String>,
) -> Result<adb::UsbModeResult, AppError> {
    Err(AppError::platform("ADB feature not enabled"))
}

#[cfg(feature = "adb")]
#[tauri::command]
pub fn list_adb_devices() -> Result<Vec<adb::AdbDevice>, AppError> {
    adb::list_adb_devices().map_err(AppError::adb)
}

#[cfg(not(feature = "adb"))]
#[tauri::command]
pub fn list_adb_devices() -> Result<Vec<adb::AdbDevice>, AppError> {
    Err(AppError::platform("ADB feature not enabled"))
}

#[tauri::command]
pub fn get_network_info() -> NetworkInfo {
    let interfaces = list_interfaces();
    let ips: Vec<String> = interfaces.iter().map(|i| i.ip.clone()).collect();
    NetworkInfo {
        ips,
        port: micyou_protocol::PORT,
    }
}

#[tauri::command]
pub fn get_network_interfaces() -> Vec<NetworkInterfaceInfo> {
    list_interfaces()
}

#[derive(serde::Serialize)]
pub struct WebStatus {
    pub running: bool,
    pub client_count: u32,
}

#[cfg(feature = "web-server")]
#[tauri::command]
pub async fn get_web_status(state: State<'_, ServerState>) -> Result<WebStatus, AppError> {
    let handle_lock = state.server_handle.lock().await;
    let (running, client_count) = handle_lock
        .as_ref()
        .map(|h| h.web_status())
        .unwrap_or((false, 0));
    Ok(WebStatus {
        running,
        client_count,
    })
}

#[cfg(not(feature = "web-server"))]
#[tauri::command]
pub async fn get_web_status(_state: State<'_, ServerState>) -> Result<WebStatus, AppError> {
    Ok(WebStatus {
        running: false,
        client_count: 0,
    })
}
