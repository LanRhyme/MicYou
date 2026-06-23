pub mod about;

use tauri::{AppHandle, Emitter, Manager, State};
use tokio_util::sync::CancellationToken;

use crate::audio::process::settings::AudioDspSettings;
use crate::tray::{TrayContext, TrayMenuStrings, TrayState};
use crate::protocol;
use crate::network;

#[derive(serde::Serialize)]
pub struct NetworkInfo {
    pub ips: Vec<String>,
    pub port: u16,
}

#[derive(serde::Serialize)]
pub struct WebStatus {
    pub running: bool,
    pub client_count: u32,
}

#[tauri::command]
pub fn greet(name: &str) -> String {
    format!("Hello, {}! You've been greeted from Rust!", name)
}

#[tauri::command]
pub fn enable_usb_mode(port: u16, device_serial: Option<String>) -> Result<crate::platform::adb_manager::UsbModeResult, String> {
    crate::platform::adb_manager::enable_usb_mode(port, device_serial.as_deref())
}

#[tauri::command]
pub fn list_adb_devices() -> Result<Vec<crate::platform::adb_manager::AdbDevice>, String> {
    crate::platform::adb_manager::list_adb_devices()
}

#[tauri::command]
pub fn get_network_info() -> NetworkInfo {
    let interfaces = network::discovery::query_network_interfaces();
    let ips: Vec<String> = interfaces.iter().map(|i| i.ip.clone()).collect();
    NetworkInfo {
        ips,
        port: protocol::PORT,
    }
}

#[tauri::command]
pub fn get_network_interfaces() -> Vec<network::NetworkInterfaceInfo> {
    network::discovery::query_network_interfaces()
}

use cpal::traits::{DeviceTrait, HostTrait};

#[tauri::command]
pub fn get_audio_devices() -> Vec<String> {
    let mut names = Vec::new();
    let host = cpal::default_host();
    if let Ok(devices) = host.output_devices() {
        for dev in devices {
            if let Ok(name) = dev.name() {
                names.push(name);
            }
        }
    }
    names.sort();
    names.dedup();
    names
}

#[tauri::command]
pub fn update_audio_settings(state: State<'_, crate::ServerState>, settings: AudioDspSettings) -> Result<String, String> {
    match state.dsp_settings.write() {
        Ok(mut current) => {
            *current = settings;
            Ok("Settings updated".to_string())
        }
        Err(e) => Err(format!("Failed to update settings: {}", e)),
    }
}

#[tauri::command]
pub async fn start_server(app_handle: AppHandle, state: State<'_, crate::ServerState>, port: u16, _mode: String, bind_address: Option<String>, output_device: Option<String>) -> Result<String, String> {
    let bind_addr = bind_address.unwrap_or_else(|| "0.0.0.0".to_string());
    let mut token_lock = state.cancel_token.lock().await;
    if token_lock.is_some() {
        return Err("Server is already running".to_string());
    }

    let cancel_token = CancellationToken::new();
    *token_lock = Some(cancel_token.clone());

    // Start mDNS
    let mut mdns_lock = state.mdns_manager.lock().await;
    match network::NetworkManager::start_mdns(port, &bind_addr) {
        Ok(manager) => {
            *mdns_lock = Some(manager);
        }
        Err(e) => {
            eprintln!("Failed to start mDNS: {}", e);
        }
    }

    let (audio_tx, audio_rx) = tokio::sync::mpsc::channel(1024);
    let is_web_mode = _mode == "web";

    // Start audio output pipeline
    crate::audio::pipeline::spawn_audio_pipeline(
        app_handle.clone(),
        audio_rx,
        output_device,
        state.dsp_settings.clone(),
        is_web_mode,
    );

    // Web mode: start web server and return (skip TCP/UDP)
    #[cfg(feature = "web-server")]
    if _mode == "web" {
        let web_port = port;
        let web_server_instance = network::web_server::WebServer::new();

        let (web_audio_tx, mut web_audio_rx) = tokio::sync::mpsc::channel::<crate::protocol::micyou::AudioPacketMessage>(1024);

        web_server_instance.start(web_port, app_handle.clone(), web_audio_tx).await
            .map_err(|e| format!("Failed to start web server: {}", e))?;

        let mut web_mdns_lock = state.web_mdns.lock().await;
        match network::NetworkManager::start_web_mdns(web_port, &bind_addr) {
            Ok(manager) => *web_mdns_lock = Some(manager),
            Err(e) => eprintln!("Failed to start web mDNS: {}", e),
        }

        *state.web_server.lock().await = Some(web_server_instance);

        let audio_tx_web = audio_tx;
        tokio::spawn(async move {
            let mut seq: i32 = 0;
            while let Some(packet) = web_audio_rx.recv().await {
                let ordered = crate::protocol::micyou::AudioPacketMessageOrdered {
                    sequence_number: seq,
                    audio_packet: Some(packet),
                    timestamp: 0,
                    fec_buffer: Vec::new(),
                    fec_sequence_number: -1,
                };
                seq += 1;
                if audio_tx_web.send(ordered).await.is_err() {
                    break;
                }
            }
        });

        return Ok(format!("Web server started on port {}", web_port));
    }

    #[cfg(not(feature = "web-server"))]
    if _mode == "web" {
        return Err("Web server feature not enabled".to_string());
    }

    let app_handle_tcp = app_handle.clone();
    let token_tcp = cancel_token.clone();
    let port_tcp = port;
    let audio_tx_tcp = audio_tx.clone();
    let stats_tcp = state.network_stats.clone();
    let mode_tcp = _mode.clone();
    let bind_addr_tcp = bind_addr.clone();
    let connection_tx_tcp = state.connection_tx.clone();
    let active_socket_handle_tcp = state.active_socket_handle.clone();
    tauri::async_runtime::spawn(async move {
        if let Err(e) = network::tcp_server::start_tcp_server(app_handle_tcp, port_tcp, bind_addr_tcp, token_tcp, audio_tx_tcp, stats_tcp, mode_tcp, connection_tx_tcp, active_socket_handle_tcp).await {
            eprintln!("TCP Server error: {}", e);
        }
    });

    let token_udp = cancel_token.clone();
    let port_udp = port + 1;
    let stats_udp = state.network_stats.clone();
    let bind_addr_udp = bind_addr.clone();
    tauri::async_runtime::spawn(async move {
        if let Err(e) = network::udp_server::start_udp_server(audio_tx, port_udp, bind_addr_udp, token_udp, stats_udp).await {
            eprintln!("UDP Server error: {}", e);
        }
    });

    Ok(format!("Server started on port {}", port))
}

#[tauri::command]
pub async fn stop_server(app: AppHandle, state: State<'_, crate::ServerState>) -> Result<String, String> {
    {
        let mut handle_lock = state.active_socket_handle.lock().await;
        if let Some(raw) = handle_lock.take() {
            network::tcp_server::force_close_socket(raw);
        }
    }

    {
        let mut conn_tx_lock = state.connection_tx.lock().await;
        *conn_tx_lock = None;
    }

    #[cfg(feature = "web-server")]
    {
        let mut web_lock = state.web_server.lock().await;
        if let Some(web) = web_lock.take() {
            web.stop();
        }
    }
    #[cfg(feature = "web-server")]
    {
        let mut web_mdns_lock = state.web_mdns.lock().await;
        if let Some(web_mdns) = web_mdns_lock.take() {
            web_mdns.stop_mdns();
        }
    }

    let mut mdns_lock = state.mdns_manager.lock().await;
    if let Some(mdns) = mdns_lock.take() {
        mdns.stop_mdns();
    }

    let mut token_lock = state.cancel_token.lock().await;
    if let Some(token) = token_lock.take() {
        token.cancel();
        #[cfg(target_os = "macos")]
        {
            let _ = crate::platform::blackhole::do_restore_input_device().await;
        }
        let _ = app.emit("server-stopped", ());
        Ok("Server stopped".to_string())
    } else {
        Err("Server is not running".to_string())
    }
}

#[tauri::command]
pub fn set_tray_strings(app: AppHandle, strings: TrayMenuStrings) -> Result<(), String> {
    {
        let ctx = app.state::<TrayContext>();
        *ctx.strings.lock().map_err(|e| e.to_string())? = strings;
    }
    crate::tray::rebuild_menu(&app).map_err(|e| e.to_string())
}

#[tauri::command]
pub fn set_tray_state(app: AppHandle, state: TrayState) -> Result<(), String> {
    {
        let ctx = app.state::<TrayContext>();
        *ctx.state.lock().map_err(|e| e.to_string())? = state;
    }
    crate::tray::rebuild_menu(&app).map_err(|e| e.to_string())
}

fn main_window<R: tauri::Runtime>(app: &AppHandle<R>) -> Result<tauri::WebviewWindow<R>, String> {
    app.get_webview_window("main")
        .ok_or_else(|| "main window not found".to_string())
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
pub fn hide_main_window(app: AppHandle) -> Result<(), String> {
    let win = main_window(&app)?;
    win.hide().map_err(|e| e.to_string())?;
    Ok(())
}

#[tauri::command]
pub fn exit_app(app: AppHandle, state: State<'_, crate::ServerState>) -> Result<(), String> {
    let rt = tauri::async_runtime::handle();
    rt.block_on(async {
        let _ = stop_server(app.clone(), state).await;
    });
    log::info!(target: "tray", "exit_app: stopping application");
    app.exit(0);
    Ok(())
}

#[tauri::command]
pub async fn set_mute_state(_app: AppHandle, state: State<'_, crate::ServerState>, is_muted: bool) -> Result<(), String> {
    let mute_msg = crate::protocol::micyou::MessageWrapper {
        audio_packet: None,
        connect: None,
        mute: Some(crate::protocol::micyou::MuteMessage { is_muted }),
        plugin_sync: None,
        ping: None,
        pong: None,
    };

    let lock = state.connection_tx.lock().await;
    if let Some(tx) = lock.as_ref() {
        tx.send(mute_msg).await.map_err(|e| e.to_string())?;
        Ok(())
    } else {
        Err("No active connection".to_string())
    }
}

#[cfg(feature = "web-server")]
#[tauri::command]
pub async fn get_web_status(state: State<'_, crate::ServerState>) -> Result<WebStatus, String> {
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
pub async fn get_web_status(_state: State<'_, crate::ServerState>) -> Result<WebStatus, String> {
    Ok(WebStatus {
        running: false,
        client_count: 0,
    })
}
