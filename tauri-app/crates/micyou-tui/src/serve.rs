use crate::config;
use crate::events::{Event, TuiEventSink};
use std::sync::mpsc::channel;
use std::sync::Arc;
use tauri_app_lib::commands::system::{start_server_inner, stop_server_inner};
use tauri_app_lib::mode_lock::RunMode;
use tauri_app_lib::server::ServerState;

pub struct ServeArgs {
    pub port: Option<u16>,
    pub mode: Option<String>,
    pub device: Option<String>,
    pub bind: Option<String>,
}

/// Start the audio server and own it for the lifetime of the terminal UI.
pub async fn run(args: ServeArgs) -> Result<(), String> {
    tauri_app_lib::mode_lock::acquire(RunMode::Tui)?;

    let prefs = tauri_app_lib::app_config::load_server_prefs();
    let port = args.port.unwrap_or(prefs.port);
    let bind = args.bind.or_else(|| {
        if prefs.auto_bind || prefs.bind_address.is_empty() || prefs.bind_address == "0.0.0.0" {
            None
        } else {
            Some(prefs.bind_address.clone())
        }
    });
    let device = args.device.or_else(|| {
        let device = prefs.output_device.trim();
        if device.is_empty() || device == "auto" || device == "default" {
            None
        } else {
            Some(device.to_string())
        }
    });
    let mode = match args.mode.as_deref().unwrap_or(&prefs.mode) {
        "wifi" | "usb" | "web" => args.mode.unwrap_or_else(|| prefs.mode.clone()),
        other => {
            tauri_app_lib::mode_lock::release();
            return Err(format!(
                "invalid mode '{other}' (expected wifi, usb or web)"
            ));
        }
    };

    if mode == "usb" {
        if let Err(error) = tauri_app_lib::commands::network::enable_usb_mode(port, None) {
            tauri_app_lib::mode_lock::release();
            return Err(format!("enable_usb_mode failed: {error}"));
        }
    }

    let state = build_state();
    let (tx, rx) = channel::<Event>();
    let events: Arc<dyn tauri_app_lib::events::ServerEvents> = Arc::new(TuiEventSink::new(tx));

    if let Err(error) = start_server_inner(
        &state,
        port,
        mode.clone(),
        bind,
        device,
        None,
        events.clone(),
    )
    .await
    {
        tauri_app_lib::mode_lock::release();
        return Err(error);
    }

    let tui_result = crate::tui::run_tui(rx, state.clone(), port, mode);
    let _ = stop_server_inner(&state, events).await;
    tauri_app_lib::mode_lock::release();
    tui_result
}

fn build_state() -> Arc<ServerState> {
    Arc::new(ServerState {
        dsp_settings: Arc::new(std::sync::RwLock::new(config::load_settings())),
        is_monitoring: Arc::new(std::sync::atomic::AtomicBool::new(false)),
        spectrum_streaming_enabled: Arc::new(std::sync::atomic::AtomicBool::new(true)),
        ..ServerState::default()
    })
}
