use crate::config;
use crate::events::CliEventSink;
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

/// Run the audio server in the foreground.
/// CLI flags override the shared server.json; otherwise the shared values are used
/// so GUI and CLI stay in sync.
pub async fn run(args: ServeArgs) -> Result<(), String> {
    tauri_app_lib::mode_lock::acquire(RunMode::Cli)?;

    // Merge shared server.json prefs with explicit CLI flags
    let prefs = tauri_app_lib::app_config::load_server_prefs();
    let port = args.port.unwrap_or(prefs.port);
    let bind = args.bind.or_else(|| {
        if prefs.auto_bind || prefs.bind_address.is_empty() || prefs.bind_address == "0.0.0.0" {
            None
        } else {
            Some(prefs.bind_address.clone())
        }
    });
    // The GUI writes "auto"/"default" to mean "no explicit device" — normalize
    // those to None so the CLI behaves identically to the GUI.
    let device = args.device.or_else(|| {
        let d = prefs.output_device.trim();
        if d.is_empty() || d == "auto" || d == "default" {
            None
        } else {
            Some(d.to_string())
        }
    });
    // Validate / normalize the connection mode (wifi | usb | web)
    let mode = match args.mode.as_deref().unwrap_or(&prefs.mode) {
        "wifi" | "usb" | "web" => args.mode.unwrap_or_else(|| prefs.mode.clone()),
        other => {
            return Err(format!(
                "invalid mode '{other}' (expected wifi, usb or web)"
            ));
        }
    };

    // USB mode: set up adb port forwarding before starting the server
    if mode == "usb" {
        println!("Setting up USB (adb) mode on port {port}");
        tauri_app_lib::commands::network::enable_usb_mode(port, None)
            .map_err(|e| format!("enable_usb_mode failed: {e}"))?;
    }

    let state = build_state();
    let events: Arc<dyn tauri_app_lib::events::ServerEvents> = Arc::new(CliEventSink);

    let result =
        start_server_inner(&state, port, mode.clone(), bind, device, None, events.clone()).await;

    match result {
        Ok(message) => println!("{message}"),
        Err(e) => {
            tauri_app_lib::mode_lock::release();
            return Err(e);
        }
    }

    println!("Press Ctrl+C to stop");
    let _ = tokio::signal::ctrl_c().await;
    println!("Stopping server...");
    let _ = stop_server_inner(&state, events).await;
    tauri_app_lib::mode_lock::release();
    Ok(())
}

/// Build a ServerState from the shared settings file.
pub fn build_state() -> Arc<ServerState> {
    let settings = config::load_settings();
    Arc::new(ServerState {
        dsp_settings: Arc::new(std::sync::RwLock::new(settings)),
        is_monitoring: Arc::new(std::sync::atomic::AtomicBool::new(false)),
        spectrum_streaming_enabled: Arc::new(std::sync::atomic::AtomicBool::new(true)),
        ..ServerState::default()
    })
}
