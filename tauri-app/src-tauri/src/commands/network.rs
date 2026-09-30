/*
 * MicYou — Turns your Android device into a high-quality PC microphone.
 * Copyright (C) 2026 LanRhyme <https://github.com/MicYou-Dev/MicYou>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version, with the MicYou Plugin Exception.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 */

use micyou_core::discovery::{query_network_interfaces, NetworkInfo, NetworkInterfaceInfo};
use micyou_core::platform::adb;
use micyou_core::server::ServerState;
use serde::Serialize;
use tauri::State;

#[tauri::command]
pub fn enable_usb_mode(port: u16, device_serial: Option<String>) -> Result<adb::UsbModeResult, String> {
    adb::enable_usb_mode(port, device_serial.as_deref())
}

#[tauri::command]
pub fn list_adb_devices() -> Result<Vec<adb::AdbDevice>, String> {
    adb::list_adb_devices()
}

#[tauri::command]
pub fn get_network_info() -> NetworkInfo {
    NetworkInfo {
        ips: query_network_interfaces().into_iter().map(|i| i.ip).collect(),
        port: micyou_core::micyou_protocol::PORT,
    }
}

#[tauri::command]
pub fn get_network_interfaces() -> Vec<NetworkInterfaceInfo> {
    query_network_interfaces()
}

#[tauri::command]
pub async fn allow_firewall() -> Result<(), String> {
    micyou_core::platform::firewall::allow_inbound()
}

#[derive(Serialize)]
pub struct WebStatus {
    pub running: bool,
    pub client_count: u32,
}

#[tauri::command]
pub async fn get_web_status(state: State<'_, ServerState>) -> Result<WebStatus, String> {
    #[cfg(feature = "web-server")]
    if let Some(web) = state.web_server.lock().await.as_ref() {
        return Ok(WebStatus {
            running: web.is_running(),
            client_count: web.client_count() as u32,
        });
    }
    #[cfg(not(feature = "web-server"))]
    let _ = state;
    Ok(WebStatus {
        running: false,
        client_count: 0,
    })
}
