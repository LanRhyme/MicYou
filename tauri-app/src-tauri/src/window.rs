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

//! Main window management and the custom (frameless) window chrome.

use tauri::window::Effect;
use tauri::{AppHandle, Manager};

#[cfg(target_os = "macos")]
#[allow(unexpected_cfgs)]
pub fn apply_macos_vibrancy(win: &tauri::WebviewWindow) {
    use window_vibrancy::{apply_vibrancy, NSVisualEffectMaterial, NSVisualEffectState};

    let _ = apply_vibrancy(
        win,
        NSVisualEffectMaterial::Sidebar,
        Some(NSVisualEffectState::Active),
        None,
    );

    use objc::runtime::{Class, Object, NO};
    use objc::{msg_send, sel, sel_impl};

    if let Ok(ptr) = win.ns_window() {
        #[allow(unexpected_cfgs)]
        unsafe {
            let ns_window = ptr as *mut Object;
            if let Some(ns_color) = Class::get("NSColor") {
                let clear: *mut Object = msg_send![ns_color, clearColor];
                let _: () = msg_send![ns_window, setOpaque: NO];
                let _: () = msg_send![ns_window, setBackgroundColor: clear];
            }
        }
    }
}

#[cfg(not(target_os = "macos"))]
pub fn apply_macos_vibrancy(_: &tauri::WebviewWindow) {}

fn main_window<R: tauri::Runtime>(app: &AppHandle<R>) -> Result<tauri::WebviewWindow<R>, String> {
    app.get_webview_window("main")
        .ok_or_else(|| "main window not found".to_string())
}

#[tauri::command]
pub fn set_window_effects(app: AppHandle, enabled: bool) -> Result<(), String> {
    use tauri::window::EffectsBuilder;

    let window = app
        .get_webview_window("main")
        .ok_or_else(|| "Main window not found".to_string())?;

    if enabled {
        window
            .set_effects(EffectsBuilder::new().effect(Effect::Acrylic).build())
            .map_err(|e| e.to_string())?;
    } else {
        window
            .set_effects(None::<tauri::utils::config::WindowEffectsConfig>)
            .map_err(|e| e.to_string())?;
    }

    Ok(())
}

/// Windows-specific: custom window drag using raw Win32 API.
#[cfg(windows)]
#[tauri::command]
pub async fn start_window_drag(app: AppHandle) -> Result<(), String> {
    use winapi::um::winuser::{
        GetAsyncKeyState, GetCursorPos, SetWindowPos, SWP_NOACTIVATE, SWP_NOSIZE,
        SWP_NOZORDER, VK_LBUTTON,
    };

    let mut cursor_pos: winapi::shared::windef::POINT = unsafe { std::mem::zeroed() };
    if unsafe { GetCursorPos(&mut cursor_pos as *mut _) } == 0 {
        return Err("GetCursorPos failed".to_string());
    }
    let start_cursor = (cursor_pos.x, cursor_pos.y);

    let window = app
        .get_webview_window("main")
        .ok_or_else(|| "Main window not found".to_string())?;
    let pos = window.outer_position().map_err(|e| e.to_string())?;
    let start_win = (pos.x, pos.y);

    let hwnd = window.hwnd().map_err(|e| e.to_string())?;

    let _ = window.set_effects(None::<tauri::utils::config::WindowEffectsConfig>);
    drop(window);

    let app_clone = app.clone();
    let flags = SWP_NOSIZE | SWP_NOZORDER | SWP_NOACTIVATE;
    let hwnd_raw = hwnd.0 as isize;

    std::thread::spawn(move || {
        loop {
            unsafe {
                if GetAsyncKeyState(VK_LBUTTON) as i16 >= 0 {
                    break;
                }

                let mut cur: winapi::shared::windef::POINT = std::mem::zeroed();
                if GetCursorPos(&mut cur as *mut _) == 0 {
                    break;
                }

                let dx = cur.x - start_cursor.0;
                let dy = cur.y - start_cursor.1;

                SetWindowPos(
                    hwnd_raw as *mut _,
                    std::ptr::null_mut(),
                    start_win.0 + dx as i32,
                    start_win.1 + dy as i32,
                    0,
                    0,
                    flags,
                );
            }
            std::thread::sleep(std::time::Duration::from_millis(8));
        }

        if let Some(win) = app_clone.get_webview_window("main") {
            let _ = restore_acrylic(&win);
        }
    });

    Ok(())
}

#[cfg(not(windows))]
#[tauri::command]
pub async fn start_window_drag(_app: AppHandle) -> Result<(), String> {
    Err("Window drag is only supported on Windows".to_string())
}

#[cfg(windows)]
fn restore_acrylic(window: &tauri::WebviewWindow) -> Result<(), String> {
    use tauri::window::EffectsBuilder;
    window
        .set_effects(EffectsBuilder::new().effect(Effect::Acrylic).build())
        .map_err(|e| e.to_string())
}

#[tauri::command]
pub fn show_main_window(app: AppHandle) -> Result<(), String> {
    let win = main_window(&app)?;
    let _ = win.unminimize();
    win.show().map_err(|e| e.to_string())?;
    win.set_focus().map_err(|e| e.to_string())?;
    Ok(())
}

#[tauri::command]
pub fn minimize_main_window(app: AppHandle) -> Result<(), String> {
    let win = main_window(&app)?;

    // Wayland compositors may ignore xdg_toplevel.set_minimized. Hiding the
    // window keeps the minimize-to-tray action reliable across Linux WMs.
    #[cfg(target_os = "linux")]
    return win.hide().map_err(|e| e.to_string());

    #[cfg(not(target_os = "linux"))]
    win.minimize().map_err(|e| e.to_string())
}

#[tauri::command]
pub fn hide_main_window(app: AppHandle) -> Result<(), String> {
    let win = main_window(&app)?;
    win.hide().map_err(|e| e.to_string())?;
    Ok(())
}

/// Places the macOS traffic lights for the active header layout. No-op elsewhere.
#[tauri::command]
pub fn apply_macos_window_layout(app: AppHandle, mode: String) -> Result<(), String> {
    let mode = crate::macos_window::Mode::parse(&mode);
    let win = main_window(&app)?;
    crate::macos_window::apply(&win.as_ref().window(), mode)
}

/// Sets the macOS Dock badge label (`None` clears it). No-op elsewhere.
#[tauri::command]
pub fn set_dock_badge(app: AppHandle, label: Option<String>) -> Result<(), String> {
    let win = main_window(&app)?;
    crate::macos_dock::set_badge(&win.as_ref().window(), label)
}

/// Replaces the app-wide menu with the descriptor the frontend sent. No-op elsewhere.
#[tauri::command]
pub fn set_app_menu(app: AppHandle, menu: Vec<crate::menubar::MenuNode>) -> Result<(), String> {
    crate::menubar::apply(&app, &menu)
}

pub const FLOATING_WINDOW_LABEL: &str = "floating-window";

#[tauri::command]
pub fn show_floating_window(_app: AppHandle) -> Result<(), String> {
    // Temporarily disabled (Issue #307 postponed)
    log::info!("Floating window is temporarily disabled");
    Ok(())
}

#[tauri::command]
pub fn hide_floating_window(app: AppHandle) -> Result<(), String> {
    if let Some(win) = app.get_webview_window(FLOATING_WINDOW_LABEL) {
        let _ = win.hide();
    }
    Ok(())
}

#[tauri::command]
pub fn toggle_floating_window(_app: AppHandle) -> Result<bool, String> {
    // Temporarily disabled (Issue #307 postponed)
    log::info!("Floating window is temporarily disabled");
    Ok(false)
}

#[tauri::command]
pub fn is_floating_window_visible(app: AppHandle) -> Result<bool, String> {
    if let Some(win) = app.get_webview_window(FLOATING_WINDOW_LABEL) {
        Ok(win.is_visible().unwrap_or(false))
    } else {
        Ok(false)
    }
}

#[tauri::command]
pub fn move_floating_window_delta(app: AppHandle, delta_x: f64, delta_y: f64) -> Result<(), String> {
    if let Some(win) = app.get_webview_window(FLOATING_WINDOW_LABEL) {
        if let Ok(pos) = win.outer_position() {
            let _ = win.set_position(tauri::Position::Physical(tauri::PhysicalPosition::new(
                pos.x + delta_x as i32,
                pos.y + delta_y as i32,
            )));
        }
    }
    Ok(())
}
