#![allow(unexpected_cfgs)]

pub mod app;
pub mod commands;
pub mod error;
pub mod network;
pub mod platform;
pub mod server;
pub mod tray;
pub mod util;

use crate::app::vibrancy::apply_macos_vibrancy;
use crate::tray::TrayContext;
use tauri::Manager;

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    tauri::Builder::default()
        .manage(crate::server::state::ServerState {
            dsp_settings: std::sync::Arc::new(std::sync::RwLock::new(
                micyou_audio::dsp::AudioDspSettings::default(),
            )),
            network_stats: std::sync::Arc::new(crate::network::stats::NetworkStats::default()),
            server_handle: std::sync::Arc::new(tokio::sync::Mutex::new(None)),
        })
        .plugin(
            tauri_plugin_log::Builder::new()
                .level(log::LevelFilter::Info)
                .build(),
        )
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
