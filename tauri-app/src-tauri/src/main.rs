/*
 * MicYou — Turns your Android device into a high-quality PC microphone.
 * Copyright (C) 2026 LanRhyme <https://github.com/LanRhyme/MicYou>
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

#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

#[cfg(target_os = "linux")]
fn configure_renderer() {
    let software_requested = std::env::args_os().any(|arg| arg == "--software-rendering")
        || std::env::var("MICYOU_RENDERER")
            .is_ok_and(|value| value.eq_ignore_ascii_case("software"));

    // Compatibility fallback for drivers/compositors where WebKitGTK's
    // accelerated DMA-BUF path produces a blank/transparent window or crashes (Issue #323).
    if std::env::var_os("WEBKIT_DISABLE_DMABUF_RENDERER").is_none() {
        std::env::set_var("WEBKIT_DISABLE_DMABUF_RENDERER", "1");
    }

    if software_requested {
        std::env::set_var("WEBKIT_DISABLE_COMPOSITING_MODE", "1");
        eprintln!("[Renderer] Software rendering fallback enabled");
    }
}

fn main() {
    #[cfg(target_os = "linux")]
    configure_renderer();

    tauri_app_lib::run()
}
