#![allow(unexpected_cfgs)]

pub mod network;
pub mod tcp_server;
pub mod udp_server;
#[cfg(feature = "web-server")]
pub mod web_server;
pub mod commands;
pub mod adb_manager;
pub mod stats;
pub mod tray;
pub mod vbcable;
pub mod blackhole;
pub mod jitter_buffer;
#[cfg(target_os = "linux")]
pub mod pipewire;
pub mod server;

use tauri::Manager;
use std::sync::Arc;
use tokio::sync::Mutex;
use std::sync::RwLock;

use micyou_audio::dsp::AudioDspSettings;
use stats::NetworkStats;
use crate::tray::TrayContext;

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
    tauri::Builder::default()
        .manage(server::ServerState {
            cancel_token: Arc::new(Mutex::new(None)),
            mdns_manager: Arc::new(Mutex::new(None)),
            dsp_settings: Arc::new(RwLock::new(AudioDspSettings::default())),
            network_stats: Arc::new(NetworkStats::default()),
            connection_tx: Arc::new(Mutex::new(None)),
            active_socket_handle: Arc::new(Mutex::new(None)),
            #[cfg(feature = "web-server")]
            web_server: Arc::new(Mutex::new(None)),
            #[cfg(feature = "web-server")]
            web_mdns: Arc::new(Mutex::new(None)),
        })
        .plugin(tauri_plugin_log::Builder::new()
            .level(log::LevelFilter::Info)
            .build())
        .plugin(tauri_plugin_dialog::init())
        .plugin(tauri_plugin_opener::init())
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

            // Apply native macOS frosted glass vibrancy
            if let Some(win) = app.get_webview_window("main") {
                apply_macos_vibrancy(&win);
            }

            Ok(())
        })
        .invoke_handler(tauri::generate_handler![
            commands::set_window_effects,
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
            commands::about::get_app_version,
            commands::set_tray_strings,
            commands::set_tray_state,
            commands::show_main_window,
            commands::hide_main_window,
            commands::exit_app,
            commands::set_mute_state,
            commands::get_web_status,
            commands::check_vbcable,
            commands::install_vbcable,
            commands::check_blackhole,
            commands::set_blackhole_as_input,
            commands::restore_input_device,
            commands::check_pipewire,
        ])
        .run(tauri::generate_context!())
        .expect("error while running tauri application");
}

