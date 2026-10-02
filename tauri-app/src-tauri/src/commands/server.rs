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

use micyou_core::server::service::{ConnectionMode, StartRequest};
use micyou_core::server::ServerState;
use micyou_core::settings::StreamingStatus;
use tauri::{AppHandle, State};

#[tauri::command]
pub async fn start_server(
    state: State<'_, ServerState>,
    port: u16,
    mode: String,
    bind_address: Option<String>,
    output_device: Option<String>,
) -> Result<String, String> {
    let request = StartRequest {
        port,
        mode: mode.parse::<ConnectionMode>()?,
        bind_address,
        output_device,
    };
    micyou_core::server::start_server(&state, request).await
}

#[tauri::command]
pub async fn stop_server(state: State<'_, ServerState>) -> Result<String, String> {
    micyou_core::server::stop_server(&state).await
}

#[tauri::command]
pub async fn get_streaming_status(state: State<'_, ServerState>) -> Result<StreamingStatus, String> {
    Ok(state.streaming_status().await)
}

#[tauri::command]
pub fn set_mute_state(state: State<'_, ServerState>, is_muted: bool) {
    state.controls().set_muted(is_muted);
}

#[tauri::command]
pub fn set_monitoring(state: State<'_, ServerState>, enabled: bool) {
    state.controls().set_monitoring(enabled);
}

#[tauri::command]
pub fn set_spectrum_streaming(state: State<'_, ServerState>, enabled: bool) {
    state.set_spectrum_streaming(enabled);
}

#[tauri::command]
pub async fn exit_app(app: AppHandle, state: State<'_, ServerState>) -> Result<(), String> {
    if let Err(e) = micyou_core::server::stop_server(&state).await {
        log::info!(target: "tray", "exit_app: server was not running: {e}");
    }
    log::info!(target: "tray", "exit_app: stopping application");
    micyou_core::mode_lock::release();
    app.exit(0);
    Ok(())
}
