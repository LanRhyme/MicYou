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
pub mod tray;

use tauri::{Emitter, AppHandle, Manager, State};
use std::sync::Arc;
use tokio::sync::Mutex;
use std::sync::RwLock;
use tokio_util::sync::CancellationToken;

use dsp::{AudioDspSettings, DspProcessor};
use stats::NetworkStats;
use crate::tray::{TrayContext, TrayMenuStrings, TrayState};

pub struct ServerState {
    pub cancel_token: Arc<Mutex<Option<CancellationToken>>>,
    pub mdns_manager: Arc<Mutex<Option<network::NetworkManager>>>,
    pub dsp_settings: Arc<RwLock<AudioDspSettings>>,
    pub network_stats: Arc<NetworkStats>,
    pub connection_tx: Arc<Mutex<Option<tokio::sync::mpsc::Sender<crate::protocol::micyou::MessageWrapper>>>>,
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

#[derive(serde::Serialize, Clone)]
struct NetworkInterfaceInfo {
    ip: String,
    interface_name: String,
}

const VIRTUAL_KEYWORDS: &[&str] = &[
    "vmware", "virtualbox", "hyper-v", "vethernet", "wsl", "docker",
    "tunnel", "teredo", "isatap", "vpn", "tailscale", "clash", "flclash",
];

fn score_ip(ip: &str) -> i32 {
    if ip.starts_with("192.168.") {
        100
    } else if ip.starts_with("172.") {
        if let Some(second) = ip.split('.').nth(1) {
            if let Ok(n) = second.parse::<u32>() {
                if (16..=31).contains(&n) {
                    return 80;
                }
            }
        }
        0
    } else if ip.starts_with("10.") {
        50
    } else if ip.starts_with("198.18.") {
        -10
    } else if ip.starts_with("169.254.") {
        -20
    } else {
        0
    }
}

fn query_network_interfaces() -> Vec<NetworkInterfaceInfo> {
    let mut candidates: Vec<(std::net::IpAddr, String)> = Vec::new();
    if let Ok(interfaces) = local_ip_address::list_afinet_netifas() {
        for (name, ip) in interfaces {
            if ip.is_loopback() || !ip.is_ipv4() {
                continue;
            }
            let name_lower = name.to_lowercase();
            if VIRTUAL_KEYWORDS.iter().any(|kw| name_lower.contains(kw)) {
                continue;
            }
            candidates.push((ip, name));
        }
    }

    candidates.sort_by(|a, b| {
        let score_a = score_ip(&a.0.to_string());
        let score_b = score_ip(&b.0.to_string());
        score_b.cmp(&score_a)
            .then_with(|| a.0.to_string().cmp(&b.0.to_string()))
            .then_with(|| a.1.cmp(&b.1))
    });

    let result: Vec<NetworkInterfaceInfo> = candidates
        .into_iter()
        .map(|(ip, name)| NetworkInterfaceInfo {
            ip: ip.to_string(),
            interface_name: name,
        })
        .collect();

    if result.is_empty() {
        vec![NetworkInterfaceInfo {
            ip: "127.0.0.1".to_string(),
            interface_name: "Local".to_string(),
        }]
    } else {
        result
    }
}

#[tauri::command]
fn get_network_info() -> NetworkInfo {
    let interfaces = query_network_interfaces();
    let ips: Vec<String> = interfaces.iter().map(|i| i.ip.clone()).collect();
    NetworkInfo {
        ips,
        port: protocol::PORT,
    }
}

#[tauri::command]
fn get_network_interfaces() -> Vec<NetworkInterfaceInfo> {
    query_network_interfaces()
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
async fn start_server(app_handle: AppHandle, state: State<'_, ServerState>, port: u16, _mode: String, bind_address: Option<String>, output_device: Option<String>) -> Result<String, String> {
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
        let mut jb = jitter_buffer::JitterBuffer::new(12);
        let mut frame_counter = 0;
        // Input resampler: converts from input sample rate to 48kHz for DSP processing
        let mut input_resampler: Option<audio_engine::RubatoResampler> = None;
        let mut current_input_sample_rate: u32 = 0;

        while let Some(packet) = audio_rx.blocking_recv() {
            jb.push(packet);
            while let Some(ordered_packet) = jb.pop() {
                if let Some(audio_data) = ordered_packet.audio_packet {
                    let mut pcm_f32 = Vec::new();
                    match audio_data.audio_format {
                        2 => { // PCM 16-bit
                            for chunk in audio_data.buffer.chunks_exact(2) {
                                let sample_i16 = i16::from_le_bytes([chunk[0], chunk[1]]);
                                pcm_f32.push(sample_i16 as f32 / 32768.0);
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
                        6 => { // PCM 24-bit
                            for chunk in audio_data.buffer.chunks_exact(3) {
                                let sample24 = (chunk[0] as i32) | ((chunk[1] as i32) << 8) | ((chunk[2] as i32) << 16);
                                // 24-bit signed to f32
                                let sample_f32 = (sample24 as f32) / 8388608.0;
                                pcm_f32.push(sample_f32);
                            }
                        }
                        _ => {
                            eprintln!("Unsupported audio format: {}", audio_data.audio_format);
                        }
                    }

                    if !pcm_f32.is_empty() {
                        let channels = audio_data.channel_count as usize;
                        let sample_rate = audio_data.sample_rate as u32;

                        // Resample input to 48kHz if needed (DSP assumes 48kHz)
                        if sample_rate > 0 && sample_rate != 48000 {
                            // Recreate resampler if sample rate changed
                            if current_input_sample_rate != sample_rate {
                                input_resampler = Some(audio_engine::RubatoResampler::new(
                                    sample_rate, 48000, channels.max(1)
                                ));
                                current_input_sample_rate = sample_rate;
                            }
                            if let Some(ref mut resampler) = input_resampler {
                                pcm_f32 = resampler.resample(&pcm_f32, channels.max(1));
                            }
                        } else {
                            input_resampler = None;
                            current_input_sample_rate = 48000;
                        }

                        let queued_samples = audio_manager.queued_samples();
                        let queued_ms = if channels > 0 {
                            (queued_samples as f64 / channels as f64) / 48.0
                        } else {
                            0.0
                        };

                        let (_raw_rms, processed_rms) = dsp_processor.process(&mut pcm_f32, channels.max(1), queued_ms);

                        audio_manager.push_audio_data(&pcm_f32, channels.max(1));

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
    let bind_addr_tcp = bind_addr.clone();
    let connection_tx_tcp = state.connection_tx.clone();
    tauri::async_runtime::spawn(async move {
        if let Err(e) = tcp_server::start_tcp_server(app_handle_tcp, port_tcp, bind_addr_tcp, token_tcp, audio_tx_tcp, stats_tcp, mode_tcp, connection_tx_tcp).await {
            eprintln!("TCP Server error: {}", e);
        }
    });

    let token_udp = cancel_token.clone();
    let port_udp = port + 1;
    let stats_udp = state.network_stats.clone();
    let bind_addr_udp = bind_addr.clone();
    tauri::async_runtime::spawn(async move {
        if let Err(e) = udp_server::start_udp_server(audio_tx, port_udp, bind_addr_udp, token_udp, stats_udp).await {
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
        let mut conn_tx_lock = state.connection_tx.lock().await;
        *conn_tx_lock = None;
        Ok("Server stopped".to_string())
    } else {
        Err("Server is not running".to_string())
    }
}

#[tauri::command]
fn set_tray_strings(app: AppHandle, strings: TrayMenuStrings) -> Result<(), String> {
    {
        let ctx = app.state::<TrayContext>();
        *ctx.strings.lock().map_err(|e| e.to_string())? = strings;
    }
    crate::tray::rebuild_menu(&app).map_err(|e| e.to_string())
}

#[tauri::command]
fn set_tray_state(app: AppHandle, state: TrayState) -> Result<(), String> {
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
fn show_main_window(app: AppHandle) -> Result<(), String> {
    let win = main_window(&app)?;
    let _ = win.unminimize();
    win.show().map_err(|e| e.to_string())?;
    win.set_focus().map_err(|e| e.to_string())?;
    Ok(())
}

#[tauri::command]
fn hide_main_window(app: AppHandle) -> Result<(), String> {
    let win = main_window(&app)?;
    win.hide().map_err(|e| e.to_string())?;
    Ok(())
}

#[tauri::command]
fn exit_app(app: AppHandle, state: State<'_, ServerState>) -> Result<(), String> {
    let rt = tauri::async_runtime::handle();
    rt.block_on(async {
        let _ = stop_server(state).await;
    });
    // TODO: restore default audio once VirtualAudioDeviceManager is ported.
    log::info!(target: "tray", "exit_app: stopping application");
    app.exit(0);
    Ok(())
}

#[tauri::command]
async fn set_mute_state(app: AppHandle, state: State<'_, ServerState>, is_muted: bool) -> Result<(), String> {
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

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    tauri::Builder::default()
        .manage(ServerState {
            cancel_token: Arc::new(Mutex::new(None)),
            mdns_manager: Arc::new(Mutex::new(None)),
            dsp_settings: Arc::new(RwLock::new(AudioDspSettings::default())),
            network_stats: Arc::new(NetworkStats::default()),
            connection_tx: Arc::new(Mutex::new(None)),
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
        .setup(|app| {
            app.manage(TrayContext::default());
            if let Err(e) = crate::tray::build_tray(app.handle()) {
                log::warn!(target: "tray", "failed to build tray: {e}");
            }
            Ok(())
        })
        .invoke_handler(tauri::generate_handler![
            greet,
            enable_usb_mode,
            get_network_info,
            get_network_interfaces,
            get_audio_devices,
            update_audio_settings,
            start_server,
            stop_server,
            commands::about::get_sponsors,
            commands::about::export_log,
            commands::about::get_app_version,
            set_tray_strings,
            set_tray_state,
            show_main_window,
            hide_main_window,
            exit_app,
            set_mute_state,
        ])
        .run(tauri::generate_context!())
        .expect("error while running tauri application");
}
