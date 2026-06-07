pub mod protocol;
pub mod network;
pub mod tcp_server;
pub mod udp_server;
pub mod audio_engine;
pub mod jitter_buffer;
pub mod dsp;
pub mod commands;
pub mod adb_manager;
pub mod stats;

use tauri::{Emitter, AppHandle, State};
use std::sync::Arc;
use tokio::sync::Mutex;
use std::sync::RwLock;
use tokio_util::sync::CancellationToken;

use dsp::{AudioDspSettings, DspProcessor};
use stats::NetworkStats;

pub struct ServerState {
    pub cancel_token: Arc<Mutex<Option<CancellationToken>>>,
    pub mdns_manager: Arc<Mutex<Option<network::NetworkManager>>>,
    pub dsp_settings: Arc<RwLock<AudioDspSettings>>,
    pub network_stats: Arc<NetworkStats>,
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

use cpal::traits::{DeviceTrait, HostTrait};

#[tauri::command]
fn get_audio_devices() -> Vec<String> {
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
fn update_audio_settings(state: State<'_, ServerState>, settings: AudioDspSettings) -> Result<String, String> {
    match state.dsp_settings.write() {
        Ok(mut current) => {
            *current = settings;
            Ok("Settings updated".to_string())
        }
        Err(e) => Err(format!("Failed to update settings: {}", e)),
    }
}

/// Spectrum data sent to the frontend for visualization.
#[derive(serde::Serialize, Clone)]
struct SpectrumPayload {
    raw: Vec<f32>,
    processed: Vec<f32>,
}

#[tauri::command]
async fn start_server(app_handle: AppHandle, state: State<'_, ServerState>, port: u16, _mode: String, output_device: Option<String>) -> Result<String, String> {
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

    let dsp_settings = state.dsp_settings.clone();

    let (audio_tx, mut audio_rx) = tokio::sync::mpsc::channel(1024);
    let app_handle_audio = app_handle.clone();
    std::thread::spawn(move || {
        let mut audio_manager = audio_engine::AudioOutputManager::new();
        if let Err(e) = audio_manager.start(output_device) {
            eprintln!("Failed to start audio output: {}", e);
            return;
        }

        let mut dsp_processor = {
            // Try to find resources dir next to the executable
            let exe_dir = std::env::current_exe()
                .ok()
                .and_then(|p| p.parent().map(|d| d.to_path_buf()));
            let resources_dir = exe_dir.as_ref().and_then(|d| {
                // Check next to exe first, then in resources subdir
                let model_direct = d.join("ulunas.onnx");
                if model_direct.exists() {
                    return Some(d.clone());
                }
                let res_dir = d.join("resources");
                if res_dir.join("ulunas.onnx").exists() {
                    return Some(res_dir);
                }
                // Check in src-tauri/resources during development
                let dev_res = std::path::PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("resources");
                if dev_res.join("ulunas.onnx").exists() {
                    return Some(dev_res);
                }
                None
            });
            DspProcessor::new(dsp_settings, resources_dir)
        };
        let mut jb = jitter_buffer::JitterBuffer::new(50);
        let mut frame_counter = 0;

        while let Some(packet) = audio_rx.blocking_recv() {
            jb.push(packet);
            while let Some(ordered_packet) = jb.pop() {
                if let Some(audio_data) = ordered_packet.audio_packet {
                    let mut pcm_f32 = Vec::new();
                    match audio_data.audio_format {
                        2 => { // PCM 16-bit
                            for chunk in audio_data.buffer.chunks_exact(2) {
                                let sample_i16 = i16::from_le_bytes([chunk[0], chunk[1]]);
                                pcm_f32.push(sample_i16 as f32 / i16::MAX as f32);
                            }
                        }
                        3 => { // PCM 8-bit
                            for &byte in &audio_data.buffer {
                                let sample_f32 = (byte as f32 - 128.0) / 128.0;
                                pcm_f32.push(sample_f32);
                            }
                        }
                        4 => { // PCM Float
                            for chunk in audio_data.buffer.chunks_exact(4) {
                                let sample_f32 = f32::from_le_bytes([chunk[0], chunk[1], chunk[2], chunk[3]]);
                                pcm_f32.push(sample_f32);
                            }
                        }
                        _ => {
                            // Ignore other formats
                        }
                    }

                    if !pcm_f32.is_empty() {
                        // Run DSP pipeline
                        let (_raw_rms, processed_rms) = dsp_processor.process(&mut pcm_f32);

                        audio_manager.push_audio_data(&pcm_f32);
                        
                        frame_counter += 1;
                        if frame_counter % 3 == 0 {
                            let level = (processed_rms * 500.0).min(100.0) as u32;
                            let _ = app_handle_audio.emit("audio-level", level);

                            // Emit spectrum data
                            let (raw_spec, proc_spec) = dsp_processor.get_spectrums();
                            let _ = app_handle_audio.emit("audio-spectrum", SpectrumPayload {
                                raw: raw_spec,
                                processed: proc_spec,
                            });
                        }
                    }
                }
            }
        }
    });

    let app_handle_tcp = app_handle.clone();
    let token_tcp = cancel_token.clone();
    let port_tcp = port;
    let audio_tx_tcp = audio_tx.clone();
    let stats_tcp = state.network_stats.clone();
    let mode_tcp = _mode.clone();
    tauri::async_runtime::spawn(async move {
        if let Err(e) = tcp_server::start_tcp_server(app_handle_tcp, port_tcp, token_tcp, audio_tx_tcp, stats_tcp, mode_tcp).await {
            eprintln!("TCP Server error: {}", e);
        }
    });

    let token_udp = cancel_token.clone();
    let port_udp = port + 1;
    let stats_udp = state.network_stats.clone();
    tauri::async_runtime::spawn(async move {
        if let Err(e) = udp_server::start_udp_server(audio_tx, port_udp, token_udp, stats_udp).await {
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
            dsp_settings: Arc::new(RwLock::new(AudioDspSettings::default())),
            network_stats: Arc::new(NetworkStats::default()),
        })
        .plugin(tauri_plugin_log::Builder::new().build())
        .plugin(tauri_plugin_dialog::init())
        .plugin(tauri_plugin_opener::init())
        .setup(|_app| {
            Ok(())
        })
        .invoke_handler(tauri::generate_handler![
            greet, 
            enable_usb_mode, 
            get_network_info, 
            get_audio_devices, 
            update_audio_settings, 
            start_server, 
            stop_server,
            commands::about::get_sponsors,
            commands::about::export_log,
            commands::about::get_app_version
        ])
        .run(tauri::generate_context!())
        .expect("error while running tauri application");
}
