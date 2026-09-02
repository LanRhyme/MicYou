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

#![allow(unexpected_cfgs)]

pub mod adb_manager;
pub mod app_config;
pub mod audio_output;
pub mod audio_stream;
pub mod blackhole;
pub mod commands;
pub mod events;
pub mod jitter_buffer;
pub mod mode_lock;
pub mod network;
pub mod opus;
#[cfg(target_os = "linux")]
pub mod pipewire;
pub mod plugins;
pub mod sound_player;
pub mod server;
pub mod stats;
pub mod tcp_server;
pub mod tray;
pub mod udp_server;
pub mod vbcable;
#[cfg(feature = "web-server")]
pub mod web_server;

use std::sync::Arc;
use std::sync::RwLock;
use tauri::Manager;
use tokio::sync::Mutex;

use crate::tray::TrayContext;
use stats::NetworkStats;

#[cfg(target_os = "macos")]
#[allow(unexpected_cfgs)]
fn apply_macos_vibrancy(win: &tauri::WebviewWindow) {
    use window_vibrancy::{apply_vibrancy, NSVisualEffectMaterial, NSVisualEffectState};

    // Apply native NSVisualEffectView frosted glass effect (Sidebar material)
    let _ = apply_vibrancy(
        win,
        NSVisualEffectMaterial::Sidebar,
        Some(NSVisualEffectState::Active),
        None,
    );

    // Make NSWindow fully transparent so the vibrancy shows through
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
fn apply_macos_vibrancy(_: &tauri::WebviewWindow) {}

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    let audio_output = crate::audio_output::AudioOutputHandle::spawn();
    tauri::Builder::default()
        .manage(server::ServerState {
            lifecycle_gate: server::ServerLifecycleGate::default(),
            lifecycle: Arc::new(Mutex::new(server::ServerLifecycleState::default())),
            cancel_token: Arc::new(Mutex::new(None)),
            background_tasks: Arc::new(Mutex::new(Vec::new())),
            mdns_manager: Arc::new(Mutex::new(None)),
            dsp_settings: Arc::new(RwLock::new(crate::app_config::load_dsp_settings())),
            is_monitoring: Arc::new(std::sync::atomic::AtomicBool::new(false)),
            spectrum_streaming_enabled: Arc::new(std::sync::atomic::AtomicBool::new(false)),
            network_stats: Arc::new(NetworkStats::default()),
            active_connection: Arc::new(Mutex::new(None)),
            takeover_lock: Arc::new(Mutex::new(())),
            active_audio_session: Arc::new(RwLock::new(Default::default())),
            audio_output: audio_output.clone(),
            plugins: Arc::new(crate::plugins::PluginHost::new(audio_output.clone())),
            #[cfg(feature = "web-server")]
            web_server: Arc::new(Mutex::new(None)),
            #[cfg(feature = "web-server")]
            web_mdns: Arc::new(Mutex::new(None)),
        })
        .plugin(
            tauri_plugin_log::Builder::new()
                .level(log::LevelFilter::Info)
                .build(),
        )
        .plugin(tauri_plugin_dialog::init())
        .plugin(tauri_plugin_opener::init())
        .plugin(tauri_plugin_global_shortcut::Builder::new().build())
        .plugin(tauri_plugin_autostart::init(
            tauri_plugin_autostart::MacosLauncher::LaunchAgent,
            Some(vec!["--minimized"]),
        ))
        .plugin(tauri_plugin_notification::init())
        .setup(|app| {
            app.manage(TrayContext::default());
            if let Err(e) = crate::tray::build_tray(app.handle()) {
                log::warn!(target: "tray", "failed to build tray: {e}");
            }

            // Scan the plugins directory and auto-enable plugins that were
            // enabled in a previous session.
            {
                let state = app.state::<server::ServerState>();
                state.plugins.hotkeys.init(app.handle());
                state.plugins.window.init(app.handle());
            }

            // Scan & enable active plugins on startup
            {
                let plugins = app.state::<server::ServerState>().plugins.clone();
                plugins.load_saved_plugins();
            }

            // Acquire the GUI mode lock so the CLI/TUI knows the GUI is running.
            // A live terminal-mode lock does not block the GUI; the frontend
            // reads `get_mode_status` to show the active mode notice.
            match crate::mode_lock::acquire(crate::mode_lock::RunMode::Gui) {
                Ok(()) => log::info!(target: "mode", "GUI mode lock acquired"),
                Err(e) => log::warn!(target: "mode", "GUI mode lock not acquired: {e}"),
            }

            // Apply native macOS frosted glass vibrancy
            if let Some(win) = app.get_webview_window("main") {
                apply_macos_vibrancy(&win);
            }

            // Create the virtual audio device at program startup (PipeWire
            // virtual sink/source on Linux + the cpal output stream). It stays
            // open until the app exits; phone connect/disconnect and server
            // start/stop never tear it down.
            {
                let handle = app.handle().clone();
                std::thread::spawn(move || {
                    let state = handle.state::<server::ServerState>();
                    let prefs = crate::app_config::load_server_prefs();
                    let device =
                        crate::commands::system::normalize_output_device(&prefs.output_device);
                    let buffer_ms = state
                        .dsp_settings
                        .read()
                        .map(|s| (s.output_buffer_ms as usize).clamp(100, 1200))
                        .unwrap_or(800);
                    let resource_dir = handle.path().resource_dir().ok();
                    let started = crate::commands::system::ensure_audio_output_started(
                        &state.audio_output,
                        device,
                        buffer_ms,
                        resource_dir.as_deref(),
                    );
                    if started {
                        log::info!("[Audio] Virtual device ready at app startup");
                    }
                });
            }

            Ok(())
        })
        .invoke_handler(tauri::generate_handler![
            commands::set_window_effects,
            commands::get_system_accent_color,
            commands::theme::install_theme,
            commands::theme::list_installed_themes,
            commands::theme::get_installed_theme,
            commands::theme::remove_installed_theme,
            commands::start_window_drag,
            commands::enable_usb_mode,
            commands::list_adb_devices,
            commands::get_network_info,
            commands::get_network_interfaces,
            commands::get_audio_devices,
            commands::update_audio_settings,
            commands::start_server,
            commands::stop_server,
            commands::about::get_sponsors,
            commands::about::export_log,
            commands::about::get_log_path,
            commands::about::get_log_content,
            commands::about::open_log_dir,
            commands::about::get_app_version,
            commands::set_tray_strings,
            commands::set_tray_state,
            commands::show_main_window,
            commands::minimize_main_window,
            commands::hide_main_window,
            commands::show_floating_window,
            commands::hide_floating_window,
            commands::toggle_floating_window,
            commands::is_floating_window_visible,
            commands::move_floating_window_delta,
            commands::allow_firewall,
            commands::exit_app,
            commands::set_mute_state,
            commands::get_streaming_status,
            commands::set_monitoring,
            commands::set_spectrum_streaming,
            commands::get_web_status,
            commands::check_vbcable,
            commands::install_vbcable,
            commands::check_blackhole,
            commands::set_blackhole_as_input,
            commands::restore_input_device,
            commands::check_pipewire,
            commands::mode::get_mode_status,
            commands::mode::release_gui_lock,
            commands::mode::switch_to_cli,
            commands::mode::switch_to_tui,
            commands::mode::save_ui_prefs,
            commands::mode::save_theme_colors,
            commands::mode::get_theme_colors,
            commands::get_audio_settings,
            commands::server_prefs_exists,
            commands::get_server_prefs,
            commands::save_server_prefs,
            commands::plugins::list_plugins,
            commands::plugins::set_plugin_enabled,
            commands::plugins::uninstall_plugin,
            commands::plugins::get_plugin_config,
            commands::plugins::set_plugin_config,
            commands::plugins::get_plugin_logs,
            commands::plugins::get_plugin_sync_status,
            commands::plugins::open_plugins_dir,
            commands::plugins::preview_plugin_zip,
            commands::plugins::preview_plugin_from_url,
            commands::plugins::install_plugin_from_url,
            commands::plugins::check_plugin_updates,
            commands::plugins::get_plugin_panel_icons,
            commands::plugins::get_app_locale,
            commands::plugins::update_plugin,
            commands::plugins::import_plugin,
            commands::plugins::plugin_trigger,
            commands::plugins::get_plugin_panel,
            commands::plugins::open_plugin_window,
        ])
        .build(tauri::generate_context!())
        .expect("error while building tauri application")
        .run(|app_handle, event| {
            // Tear down the persistent virtual audio device only when the
            // process exits, never on server stop or connection close.
            if let tauri::RunEvent::Exit = event {
                let state = app_handle.state::<server::ServerState>();
                commands::system::shutdown_audio_output(state.inner());
            }
        });
}
