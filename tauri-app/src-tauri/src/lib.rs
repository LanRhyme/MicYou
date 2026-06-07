pub mod protocol;
pub mod network;
pub mod tcp_server;
pub mod udp_server;
pub mod audio_engine;
pub mod jitter_buffer;

pub mod adb_manager;

use tauri::{Manager, Emitter, AppHandle, State};
use std::sync::Arc;
use tokio::sync::Mutex;
use tokio_util::sync::CancellationToken;

struct ServerState {
    cancel_token: Arc<Mutex<Option<CancellationToken>>>,
    mdns_manager: Arc<Mutex<Option<network::NetworkManager>>>,
}

#[tauri::command]
fn greet(name: &str) -> String {
    format!("Hello, {}! You've been greeted from Rust!", name)
}

#[tauri::command]
fn enable_usb_mode(port: u16) -> Result<String, String> {
    match adb_manager::run_adb_reverse(port) {
        Ok(_) => Ok("ADB reverse successful. USB mode ready.".to_string()),
        Err(e) => Err(e),
    }
}

#[derive(serde::Serialize)]
struct NetworkInfo {
    ips: Vec<String>,
    port: u16,
}

#[tauri::command]
fn get_network_info() -> NetworkInfo {
    let mut ips = Vec::new();
    if let Ok(network_interfaces) = local_ip_address::list_afinet_netifas() {
        for (_, ip) in network_interfaces.into_iter() {
            if ip.is_ipv4() && !ip.is_loopback() {
                ips.push(ip.to_string());
            }
        }
    }
    if ips.is_empty() {
        ips.push("127.0.0.1".to_string());
    }
    NetworkInfo {
        ips,
        port: protocol::PORT,
    }
}

#[tauri::command]
async fn start_server(app_handle: AppHandle, state: State<'_, ServerState>, port: u16, mode: String) -> Result<String, String> {
    let mut token_lock = state.cancel_token.lock().await;
    if token_lock.is_some() {
        return Err("Server is already running".to_string());
    }

    let cancel_token = CancellationToken::new();
    *token_lock = Some(cancel_token.clone());

    // Start mDNS
    let mut mdns_lock = state.mdns_manager.lock().await;
    match network::NetworkManager::start_mdns(port) {
        Ok(manager) => {
            *mdns_lock = Some(manager);
        }
        Err(e) => {
            eprintln!("Failed to start mDNS: {}", e);
        }
    }

    let (audio_tx, mut audio_rx) = tokio::sync::mpsc::channel(1024);
    
    let app_handle_audio = app_handle.clone();
    std::thread::spawn(move || {
        let mut audio_manager = audio_engine::AudioOutputManager::new();
        if let Err(e) = audio_manager.start() {
            eprintln!("Failed to start audio output: {}", e);
            return;
        }

        let mut decoder = audiopus::coder::Decoder::new(
            audiopus::SampleRate::Hz48000, 
            audiopus::Channels::Stereo
        ).expect("Failed to create Opus decoder");
        
        let mut jb = jitter_buffer::JitterBuffer::new(50);
        let mut pcm_buf = vec![0f32; 5760];
        
        let mut frame_counter = 0;

        let rt = tokio::runtime::Handle::current();
        rt.block_on(async {
            while let Some(packet) = audio_rx.recv().await {
                jb.push(packet);
                while let Some(ordered_packet) = jb.pop() {
                    if let Some(audio_data) = ordered_packet.audio_packet {
                        match decoder.decode_float(Some(&audio_data.buffer), &mut pcm_buf, false) {
                            Ok(len) => {
                                let samples = len * 2;
                                audio_manager.push_audio_data(&pcm_buf[0..samples]);
                                
                                frame_counter += 1;
                                if frame_counter % 3 == 0 {
                                    let mut sum_squares = 0.0;
                                    for &sample in &pcm_buf[0..samples] {
                                        sum_squares += sample * sample;
                                    }
                                    let rms = (sum_squares / samples as f32).sqrt();
                                    let level = (rms * 500.0).min(100.0) as u32;
                                    let _ = app_handle_audio.emit("audio-level", level);
                                }
                            }
                            Err(e) => {
                                eprintln!("Opus decode error: {}", e);
                            }
                        }
                    }
                }
            }
        });
    });

    let app_handle_tcp = app_handle.clone();
    let token_tcp = cancel_token.clone();
    let port_tcp = port;
    tauri::async_runtime::spawn(async move {
        if let Err(e) = tcp_server::start_tcp_server(app_handle_tcp, port_tcp, token_tcp).await {
            eprintln!("TCP Server error: {}", e);
        }
    });

    let token_udp = cancel_token.clone();
    let port_udp = port + 1;
    tauri::async_runtime::spawn(async move {
        if let Err(e) = udp_server::start_udp_server(audio_tx, port_udp, token_udp).await {
            eprintln!("UDP Server error: {}", e);
        }
    });

    Ok(format!("Server started on port {}", port))
}

#[tauri::command]
async fn stop_server(state: State<'_, ServerState>) -> Result<String, String> {
    let mut mdns_lock = state.mdns_manager.lock().await;
    if let Some(mdns) = mdns_lock.take() {
        mdns.stop_mdns();
    }

    let mut token_lock = state.cancel_token.lock().await;
    if let Some(token) = token_lock.take() {
        token.cancel();
        Ok("Server stopped".to_string())
    } else {
        Err("Server is not running".to_string())
    }
}

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    tauri::Builder::default()
        .manage(ServerState {
            cancel_token: Arc::new(Mutex::new(None)),
            mdns_manager: Arc::new(Mutex::new(None)),
        })
        .plugin(tauri_plugin_opener::init())
        .setup(|app| {
            Ok(())
        })
        .invoke_handler(tauri::generate_handler![greet, enable_usb_mode, get_network_info, start_server, stop_server])
        .run(tauri::generate_context!())
        .expect("error while running tauri application");
}
