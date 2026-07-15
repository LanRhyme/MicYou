use tauri::State;
use crate::server::{ServerState, NetworkInfo, NetworkInterfaceInfo, query_network_interfaces};
use crate::adb_manager;

#[tauri::command]
pub fn enable_usb_mode(port: u16, device_serial: Option<String>) -> Result<adb_manager::UsbModeResult, String> {
    adb_manager::enable_usb_mode(port, device_serial.as_deref())
}

#[tauri::command]
pub fn list_adb_devices() -> Result<Vec<adb_manager::AdbDevice>, String> {
    adb_manager::list_adb_devices()
}

#[tauri::command]
pub fn get_network_info() -> NetworkInfo {
    let interfaces = query_network_interfaces();
    let ips: Vec<String> = interfaces.iter().map(|i| i.ip.clone()).collect();
    NetworkInfo {
        ips,
        port: micyou_protocol::PORT,
    }
}

#[tauri::command]
pub fn get_network_interfaces() -> Vec<NetworkInterfaceInfo> {
    query_network_interfaces()
}

#[derive(serde::Serialize)]
pub struct WebStatus {
    pub running: bool,
    pub client_count: u32,
}

#[cfg(feature = "web-server")]
#[tauri::command]
pub async fn get_web_status(state: State<'_, ServerState>) -> Result<WebStatus, String> {
    let lock = state.web_server.lock().await;
    if let Some(web) = lock.as_ref() {
        Ok(WebStatus {
            running: web.is_running(),
            client_count: web.client_count() as u32,
        })
    } else {
        Ok(WebStatus {
            running: false,
            client_count: 0,
        })
    }
}

#[cfg(not(feature = "web-server"))]
#[tauri::command]
pub async fn get_web_status(_state: State<'_, ServerState>) -> Result<WebStatus, String> {
    Ok(WebStatus {
        running: false,
        client_count: 0,
    })
}
