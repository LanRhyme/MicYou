use tauri::window::Effect;
use tauri::{AppHandle, Emitter, Manager, State};
use tokio_util::sync::CancellationToken;

use crate::server::ServerState;
use crate::tray::{TrayContext, TrayMenuStrings, TrayState};
use micyou_audio::dsp::DspProcessor;

#[derive(serde::Serialize, Clone)]
pub struct SpectrumPayload {
    pub raw: Vec<f32>,
    pub processed: Vec<f32>,
}

#[tauri::command]
pub async fn start_server(
    app_handle: AppHandle,
    state: State<'_, ServerState>,
    port: u16,
    mode: String,
    bind_address: Option<String>,
    output_device: Option<String>,
) -> Result<String, String> {
    let bind_addr = bind_address.unwrap_or_else(|| "0.0.0.0".to_string());
    let cancel_token = {
        let mut token_lock = state.cancel_token.lock().await;
        if token_lock.is_some() {
            return Err("Server is already running".to_string());
        }
        let token = CancellationToken::new();
        *token_lock = Some(token.clone());
        token
    };

    // Start mDNS
    {
        let mut mdns_lock = state.mdns_manager.lock().await;
        match crate::network::NetworkManager::start_mdns(port, &bind_addr) {
            Ok(manager) => {
                *mdns_lock = Some(manager);
            }
            Err(e) => {
                eprintln!("Failed to start mDNS: {}", e);
            }
        }
    }

    let dsp_settings = state.dsp_settings.clone();

    // On Linux, set up PipeWire virtual audio device before starting audio output.
    #[cfg(target_os = "linux")]
    if output_device.is_none() {
        if crate::pipewire::is_available() {
            if !crate::pipewire::is_setup() {
                log::info!("[PipeWire] Setting up virtual audio device...");
                if crate::pipewire::setup() {
                    log::info!("[PipeWire] Virtual device ready, ALSA will route to virtual sink");
                } else {
                    log::warn!("[PipeWire] Setup failed, falling back to default device");
                }
            }
        } else {
            log::info!("[PipeWire] Not available, using default audio device");
        }
    }

    let resolved_output_device = output_device;
    let (audio_tx, mut audio_rx) = tokio::sync::mpsc::channel(1024);

    // Start audio output pipeline (shared by all modes)
    let app_handle_audio = app_handle.clone();
    let is_web_mode = mode == "web";
    let (ready_tx, ready_rx) = tokio::sync::oneshot::channel();

    std::thread::spawn(move || {
        let mut audio_manager = micyou_audio::AudioOutputManager::new();
        if let Err(e) = audio_manager.start(resolved_output_device) {
            let _ = ready_tx.send(Err(e.to_string()));
            return;
        }
        let _ = ready_tx.send(Ok(()));

        let mut dsp_processor = {
            let exe_dir = std::env::current_exe()
                .ok()
                .and_then(|p| p.parent().map(|d| d.to_path_buf()));
            let resources_dir = exe_dir.as_ref().and_then(|d| {
                let model_direct = d.join("ulunas.onnx");
                if model_direct.exists() {
                    return Some(d.clone());
                }
                let res_dir = d.join("resources");
                if res_dir.join("ulunas.onnx").exists() {
                    return Some(res_dir);
                }
                let dev_res =
                    std::path::PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("resources");
                if dev_res.join("ulunas.onnx").exists() {
                    return Some(dev_res);
                }
                None
            });
            DspProcessor::new(dsp_settings, resources_dir)
        };
        let mut jb = crate::jitter_buffer::JitterBuffer::new(12);
        let mut frame_counter = 0;
        let mut previous_output: Vec<f32> = Vec::new(); // far-end reference for AEC
        let mut input_resampler: Option<micyou_audio::RubatoResampler> = None;
        let mut current_input_sample_rate: u32 = 0;
        let mut resample_out_buf = Vec::new();
        let mut pcm_f32 = Vec::new();

        while let Some(packet) = audio_rx.blocking_recv() {
            jb.push(packet);
            let packets: Vec<_> = std::iter::from_fn(|| jb.pop()).collect();

            for ordered_packet in packets {
                if let Some(audio_data) = ordered_packet.audio_packet {
                    let capacity = match audio_data.audio_format {
                        2 => audio_data.buffer.len() / 2,
                        3 => audio_data.buffer.len(),
                        4 => audio_data.buffer.len() / 4,
                        6 => audio_data.buffer.len() / 3,
                        _ => 0,
                    };
                    pcm_f32.clear();
                    pcm_f32.reserve(capacity);
                    match audio_data.audio_format {
                        2 => {
                            for chunk in audio_data.buffer.chunks_exact(2) {
                                let sample_i16 = i16::from_le_bytes([chunk[0], chunk[1]]);
                                pcm_f32.push(sample_i16 as f32 / 32768.0);
                            }
                        }
                        3 => {
                            for &byte in &audio_data.buffer {
                                let sample_f32 = (byte as f32 - 128.0) / 128.0;
                                pcm_f32.push(sample_f32);
                            }
                        }
                        4 => {
                            for chunk in audio_data.buffer.chunks_exact(4) {
                                let sample_f32 =
                                    f32::from_le_bytes([chunk[0], chunk[1], chunk[2], chunk[3]]);
                                pcm_f32.push(sample_f32);
                            }
                        }
                        6 => {
                            for chunk in audio_data.buffer.chunks_exact(3) {
                                let sample24 = (chunk[0] as i32)
                                    | ((chunk[1] as i32) << 8)
                                    | ((chunk[2] as i8 as i32) << 16);
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

                        if sample_rate > 0 && sample_rate != 48000 {
                            if current_input_sample_rate != sample_rate {
                                match micyou_audio::RubatoResampler::new(
                                    sample_rate,
                                    48000,
                                    channels.max(1),
                                ) {
                                    Ok(res) => {
                                        input_resampler = Some(res);
                                        current_input_sample_rate = sample_rate;
                                    }
                                    Err(e) => {
                                        eprintln!("Failed to create resampler: {}", e);
                                        input_resampler = None;
                                        current_input_sample_rate = 48000;
                                    }
                                }
                            }
                            if let Some(ref mut resampler) = input_resampler {
                                resampler.resample(
                                    &pcm_f32,
                                    channels.max(1),
                                    &mut resample_out_buf,
                                );
                                pcm_f32.clear();
                                pcm_f32.extend_from_slice(&resample_out_buf);
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

                        // Web mode: skip DSP for now, output raw audio directly
                        let processed_rms = if is_web_mode {
                            let sum: f32 = pcm_f32.iter().map(|x| x * x).sum();
                            (sum / pcm_f32.len() as f32).sqrt()
                        } else {
                            // Feed previous output as far-end reference for AEC
                            if !previous_output.is_empty() {
                                dsp_processor.set_far_end_audio(&previous_output);
                            }
                            let (_raw, processed) =
                                dsp_processor.process(&mut pcm_f32, channels.max(1), queued_ms);
                            // Store output as far-end reference for next frame
                            previous_output = pcm_f32.clone();
                            processed
                        };

                        audio_manager.push_audio_data(&pcm_f32, channels.max(1));

                        frame_counter += 1;
                        if frame_counter % 3 == 0 {
                            let level = (processed_rms * 500.0).min(100.0) as u32;
                            let _ = app_handle_audio.emit("audio-level", level);

                            let (raw_spec, proc_spec) = dsp_processor.get_spectrums();
                            let _ = app_handle_audio.emit(
                                "audio-spectrum",
                                SpectrumPayload {
                                    raw: raw_spec,
                                    processed: proc_spec,
                                },
                            );
                        }
                    }
                }
            }
        }
    });

    ready_rx
        .await
        .map_err(|_| "Audio thread panicked during startup".to_string())?
        .map_err(|e| format!("Failed to start audio output: {}", e))?;

    // Web mode: start web server and return (skip TCP/UDP)
    #[cfg(feature = "web-server")]
    if mode == "web" {
        let web_port = port;
        let web_server_instance = crate::web_server::WebServer::new();

        let (web_audio_tx, mut web_audio_rx) =
            tokio::sync::mpsc::channel::<micyou_protocol::micyou::AudioPacketMessage>(1024);

        web_server_instance
            .start(web_port, app_handle.clone(), web_audio_tx)
            .await
            .map_err(|e| format!("Failed to start web server: {}", e))?;

        let mut web_mdns_lock = state.web_mdns.lock().await;
        match crate::network::NetworkManager::start_web_mdns(web_port, &bind_addr) {
            Ok(manager) => *web_mdns_lock = Some(manager),
            Err(e) => eprintln!("Failed to start web mDNS: {}", e),
        }

        *state.web_server.lock().await = Some(web_server_instance);

        let audio_tx_web = audio_tx;
        tokio::spawn(async move {
            let mut seq: i32 = 0;
            while let Some(packet) = web_audio_rx.recv().await {
                let ordered = micyou_protocol::micyou::AudioPacketMessageOrdered {
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
    if mode == "web" {
        return Err("Web server feature not enabled".to_string());
    }

    let app_handle_tcp = app_handle.clone();
    let token_tcp = cancel_token.clone();
    let port_tcp = port;
    let audio_tx_tcp = audio_tx.clone();
    let stats_tcp = state.network_stats.clone();
    let mode_tcp = mode.clone();
    let bind_addr_tcp = bind_addr.clone();
    let connection_tx_tcp = state.connection_tx.clone();
    let active_socket_handle_tcp = state.active_socket_handle.clone();
    tauri::async_runtime::spawn(async move {
        if let Err(e) = crate::tcp_server::start_tcp_server(
            app_handle_tcp,
            port_tcp,
            bind_addr_tcp,
            token_tcp,
            audio_tx_tcp,
            stats_tcp,
            mode_tcp,
            connection_tx_tcp,
            active_socket_handle_tcp,
        )
        .await
        {
            eprintln!("TCP Server error: {}", e);
        }
    });

    let token_udp = cancel_token.clone();
    let port_udp = port + 1;
    let stats_udp = state.network_stats.clone();
    let bind_addr_udp = bind_addr.clone();
    tauri::async_runtime::spawn(async move {
        if let Err(e) = crate::udp_server::start_udp_server(
            audio_tx,
            port_udp,
            bind_addr_udp,
            token_udp,
            stats_udp,
        )
        .await
        {
            eprintln!("UDP Server error: {}", e);
        }
    });

    Ok(format!("Server started on port {}", port))
}

#[tauri::command]
pub async fn stop_server(app: AppHandle, state: State<'_, ServerState>) -> Result<String, String> {
    {
        let mut handle_lock = state.active_socket_handle.lock().await;
        if let Some(raw) = handle_lock.take() {
            crate::tcp_server::force_close_socket(raw);
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
        // Restore the original input device on macOS (BlackHole cleanup)
        #[cfg(target_os = "macos")]
        {
            let _ = crate::blackhole::do_restore_input_device().await;
        }
        // Clean up PipeWire virtual devices on Linux
        #[cfg(target_os = "linux")]
        {
            crate::pipewire::cleanup();
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
    use std::ffi::CString;
    use winapi::um::winuser::{
        FindWindowA, GetAsyncKeyState, GetCursorPos, SetWindowPos, SWP_NOACTIVATE, SWP_NOSIZE,
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
pub fn hide_main_window(app: AppHandle) -> Result<(), String> {
    let win = main_window(&app)?;
    win.hide().map_err(|e| e.to_string())?;
    Ok(())
}

#[tauri::command]
pub async fn exit_app(app: AppHandle, state: State<'_, ServerState>) -> Result<(), String> {
    let _ = stop_server(app.clone(), state).await;
    log::info!(target: "tray", "exit_app: stopping application");
    app.exit(0);
    Ok(())
}
