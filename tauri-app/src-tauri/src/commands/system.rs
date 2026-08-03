use tauri::window::Effect;
use tauri::{AppHandle, Manager, State};
use tokio_util::sync::CancellationToken;

use crate::audio_stream::{validate_audio_packet, AudioStreamEvent, ExpectedAudioSession};
use crate::server::{await_startup_ready, ServerState, AUDIO_JOIN_TIMEOUT, STARTUP_TIMEOUT};
use crate::udp_server::ActiveAudioSession;

const NETWORK_TASK_JOIN_TIMEOUT: std::time::Duration = std::time::Duration::from_secs(3);

fn validate_server_port(port: u16, mode: &str) -> Result<Option<u16>, String> {
    if mode == "web" {
        return if port == 0 {
            Err("Web server port must be between 1 and 65535".to_string())
        } else {
            Ok(None)
        };
    }

    if port == 0 {
        return Err("Audio server port must be between 1 and 65534".to_string());
    }

    port.checked_add(1).map(Some).ok_or_else(|| {
        "Audio server port must be between 1 and 65534 so the following UDP port is valid"
            .to_string()
    })
}

fn disable_aec_runtime(
    settings: &std::sync::Arc<std::sync::RwLock<micyou_audio::dsp::AudioDspSettings>>,
    events: &crate::events::SharedEvents,
    reason: String,
) {
    let mut changed = false;
    if let Ok(mut current) = settings.write() {
        if current.aec_enabled {
            current.aec_enabled = false;
            let _ = crate::app_config::save_dsp_settings(&current);
            changed = true;
        }
    }
    if changed {
        events.aec_status_changed(crate::events::AecStatus {
            available: false,
            enabled: false,
            reason: Some(reason),
        });
    }
}

fn should_capture_loopback(
    transport_active: bool,
    audio_received: bool,
    aec_enabled: bool,
) -> bool {
    transport_active && audio_received && aec_enabled
}

async fn join_tasks_bounded(
    mut tasks: Vec<tokio::task::JoinHandle<()>>,
    timeout_duration: std::time::Duration,
) {
    let deadline = tokio::time::Instant::now() + timeout_duration;
    while let Some(mut task) = tasks.pop() {
        if tokio::time::timeout_at(deadline, &mut task).await.is_err() {
            task.abort();
            let _ = task.await;
            for task in &tasks {
                task.abort();
            }
            for task in tasks {
                let _ = task.await;
            }
            break;
        }
    }
}

async fn rollback_start(
    state: &ServerState,
    cancel_token: &CancellationToken,
    tasks: Vec<tokio::task::JoinHandle<()>>,
) -> Result<(), String> {
    cancel_token.cancel();
    crate::tcp_server::cleanup_session_state(&state.active_connection, &state.active_audio_session)
        .await;
    join_tasks_bounded(tasks, NETWORK_TASK_JOIN_TIMEOUT).await;
    state.cancel_token.lock().await.take();
    if let Some(mdns) = state.mdns_manager.lock().await.take() {
        mdns.stop_mdns();
    }
    let mut lifecycle = state.lifecycle.lock().await;
    lifecycle.begin_stopping();
    lifecycle.join_audio_bounded(AUDIO_JOIN_TIMEOUT).await
}
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
    let events: crate::events::SharedEvents =
        std::sync::Arc::new(crate::events::TauriEventSink(app_handle));
    // Reload shared settings.json before starting so CLI-side changes apply
    let file_settings = crate::app_config::load_dsp_settings();
    if let Ok(mut current) = state.dsp_settings.write() {
        *current = file_settings;
    }
    start_server_inner(&state, port, mode, bind_address, output_device, events).await
}

/// Core server startup, independent of the Tauri runtime.
/// Shared by the GUI, CLI (`micyou serve`) and TUI (`micyou-tui`).
pub async fn start_server_inner(
    state: &ServerState,
    port: u16,
    mode: String,
    bind_address: Option<String>,
    output_device: Option<String>,
    events: crate::events::SharedEvents,
) -> Result<String, String> {
    let udp_port = validate_server_port(port, &mode)?;

    let _lifecycle_guard = state.lifecycle_gate.enter().await;
    state.lifecycle.lock().await.begin_start().await?;
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
    let output_buffer_ms = dsp_settings
        .read()
        .map(|s| (s.output_buffer_ms as usize).clamp(100, 1200))
        .unwrap_or(800);

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
    // Bound queued latency: Android packets are ~7 ms, so 128 slots provide ample
    // scheduling headroom without retaining seconds of stale audio.
    let (audio_tx, mut audio_rx) = tokio::sync::mpsc::channel(128);

    // Start audio output pipeline (shared by all modes)
    let events_audio = events.clone();
    let is_web_mode = mode == "web";
    let (ready_tx, ready_rx) = tokio::sync::oneshot::channel();

    let is_monitoring_flag = state.is_monitoring.clone();
    let spectrum_streaming_enabled = state.spectrum_streaming_enabled.clone();
    let active_audio_session_audio = state.active_audio_session.clone();

    let audio_thread = std::thread::spawn(move || {
        let mut audio_manager = micyou_audio::AudioOutputManager::new();
        if let Err(e) = audio_manager.start(resolved_output_device, output_buffer_ms) {
            let _ = ready_tx.send(Err(e.to_string()));
            return;
        }
        let _ = ready_tx.send(Ok(()));

        let mut dsp_processor = {
            let exe_dir = std::env::current_exe()
                .ok()
                .and_then(|p| p.parent().map(|d| d.to_path_buf()));
            let resources_dir = exe_dir.as_ref().and_then(|d| {
                let model_direct = d.join("purevox6.onnx");
                let aec_direct = d.join("aec7_ep0185.onnx");
                if model_direct.exists() || aec_direct.exists() {
                    return Some(d.clone());
                }
                let res_dir = d.join("resources");
                if res_dir.join("purevox6.onnx").exists()
                    || res_dir.join("aec7_ep0185.onnx").exists()
                {
                    return Some(res_dir);
                }
                let dev_res =
                    std::path::PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("resources");
                if dev_res.join("purevox6.onnx").exists()
                    || dev_res.join("aec7_ep0185.onnx").exists()
                {
                    return Some(dev_res);
                }
                None
            });
            DspProcessor::new(dsp_settings.clone(), resources_dir)
        };
        let mut jb = crate::jitter_buffer::JitterBuffer::new(12);
        let mut frame_counter: u32 = 0;
        let mut input_resampler: Option<micyou_audio::RubatoResampler> = None;
        let mut current_input_sample_rate: u32 = 0;
        let mut resample_out_buf = Vec::new();
        let mut pcm_f32 = Vec::new();

        // Speaker loopback capture for the AEC far-end reference. Windows uses
        // WASAPI loopback; Linux uses the PipeWire source created above. Both
        // are started lazily only after an AEC-enabled session sends audio.
        #[cfg(any(target_os = "windows", target_os = "linux"))]
        let loopback: Option<micyou_audio::LoopbackCapture> =
            Some(micyou_audio::LoopbackCapture::new());
        #[cfg(not(any(target_os = "windows", target_os = "linux")))]
        let loopback: Option<micyou_audio::LoopbackCapture> = None;

        let mut audio_received_for_session = false;
        // Sync the AEC far-end capture with actual audio flow. A control session
        // alone is not enough: while waiting for the first valid audio packet,
        // there is no microphone stream that needs an echo reference.
        let sync_loopback = |loopback: &Option<micyou_audio::LoopbackCapture>,
                             audio_received: &mut bool| {
            let transport_active = !matches!(
                *active_audio_session_audio
                    .read()
                    .unwrap_or_else(|p| p.into_inner()),
                ActiveAudioSession::Inactive
            );
            if !transport_active {
                *audio_received = false;
            }
            let mut failure = None;
            if let Some(lb) = loopback {
                let aec_enabled = dsp_settings
                    .read()
                    .map(|settings| settings.aec_enabled)
                    .unwrap_or(false);
                let should_capture =
                    should_capture_loopback(transport_active, *audio_received, aec_enabled);
                if should_capture && !lb.is_active() {
                    if let Some(reason) = lb.take_failure_reason() {
                        failure = Some(reason);
                    } else if lb.start() {
                        log::info!("[Audio] Starting speaker loopback capture for AEC");
                    } else {
                        failure = lb
                            .take_failure_reason()
                            .or_else(|| Some("reference_lost".to_string()));
                    }
                } else if !should_capture && lb.is_active() {
                    lb.stop();
                }
            }
            (transport_active, failure)
        };

        loop {
            // Idle heartbeat every 500ms: with no device session the loopback
            // capture stream stays stopped (biggest idle CPU win).
            match audio_rx.try_recv() {
                Err(tokio::sync::mpsc::error::TryRecvError::Disconnected) => break,
                Err(tokio::sync::mpsc::error::TryRecvError::Empty) => {
                    // Poll fast (10ms) while a session is active: audio packets
                    // can arrive after a silence gap and must not sit in the
                    // channel for up to 500ms (that caused audible dropouts at
                    // the start of each utterance). Idle servers sleep 500ms.
                    let (session_active, failure) =
                        sync_loopback(&loopback, &mut audio_received_for_session);
                    if let Some(reason) = failure {
                        disable_aec_runtime(&dsp_settings, &events_audio, reason);
                    }
                    std::thread::sleep(std::time::Duration::from_millis(if session_active {
                        10
                    } else {
                        500
                    }));
                }
                Ok(event) => {
                    audio_manager.set_monitoring(
                        is_monitoring_flag.load(std::sync::atomic::Ordering::Relaxed),
                    );
                    match event {
                        AudioStreamEvent::SessionStarting { expected, epoch } => {
                            audio_received_for_session = false;
                            dsp_processor.reset_aec_session();
                            let (_, failure) =
                                sync_loopback(&loopback, &mut audio_received_for_session);
                            if let Some(reason) = failure {
                                disable_aec_runtime(&dsp_settings, &events_audio, reason);
                            }
                            jb.prepare_transport_session_epoch(expected, epoch);
                            continue;
                        }
                        AudioStreamEvent::Packet { packet, epoch } => {
                            audio_received_for_session = true;
                            let (_, failure) =
                                sync_loopback(&loopback, &mut audio_received_for_session);
                            if let Some(reason) = failure {
                                disable_aec_runtime(&dsp_settings, &events_audio, reason);
                            }
                            jb.push_epoch(packet, epoch);
                        }
                    }
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
                                        let sample_f32 = f32::from_le_bytes([
                                            chunk[0], chunk[1], chunk[2], chunk[3],
                                        ]);
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
                                    eprintln!(
                                        "Unsupported audio format: {}",
                                        audio_data.audio_format
                                    );
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
                                    // Read speaker loopback for AEC far-end reference.
                                    // This captures the ACTUAL speaker output (WASAPI/BlackHole/PipeWire),
                                    // which is the true echo source the phone mic picks up.
                                    if let Some(lb) = &loopback {
                                        if lb.is_active() {
                                            // Feed one mono reference sample for each near-end
                                            // frame entering the DSP. Packet sizes and input
                                            // sample rates vary, so consuming a fixed hop per
                                            // network packet makes the two streams drift.
                                            let near_frames = pcm_f32.len() / channels.max(1);
                                            if let Some(far_data) = lb.read(near_frames) {
                                                dsp_processor.set_far_end_audio(&far_data);
                                            }
                                        }
                                    }
                                    let (_raw, processed) = dsp_processor.process(
                                        &mut pcm_f32,
                                        channels.max(1),
                                        queued_ms,
                                    );
                                    if let Some(reason) = dsp_processor.take_aec_failure() {
                                        disable_aec_runtime(&dsp_settings, &events_audio, reason);
                                    }
                                    processed
                                };

                                audio_manager.push_audio_data(&pcm_f32, channels.max(1));

                                frame_counter = frame_counter.wrapping_add(1);
                                if frame_counter.is_multiple_of(6) {
                                    let level = (processed_rms * 500.0).min(100.0) as u32;
                                    events_audio.audio_level(level);

                                    if spectrum_streaming_enabled
                                        .load(std::sync::atomic::Ordering::Acquire)
                                    {
                                        let (raw_spec, proc_spec) = dsp_processor.get_spectrums();
                                        events_audio.audio_spectrum(raw_spec, proc_spec);
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        if let Some(lb) = &loopback {
            let was_active = lb.is_active();
            lb.stop();
            if was_active {
                log::info!("[Audio] Speaker loopback stopped");
            }
        }
    });

    state.lifecycle.lock().await.set_audio_thread(audio_thread);
    let audio_ready = await_startup_ready(ready_rx, "Audio output", STARTUP_TIMEOUT)
        .await
        .map_err(|error| format!("Failed to start audio output: {}", error));
    if let Err(error) = audio_ready {
        let rollback = rollback_start(state, &cancel_token, Vec::new()).await;
        return Err(match rollback {
            Ok(()) => error,
            Err(cleanup) => format!("{}; {}", error, cleanup),
        });
    }

    // Web mode: start web server and return (skip TCP/UDP)
    #[cfg(feature = "web-server")]
    if mode == "web" {
        let web_port = port;
        let web_server_instance = crate::web_server::WebServer::new();

        let (web_audio_tx, mut web_audio_rx) =
            tokio::sync::mpsc::channel::<(u64, micyou_protocol::micyou::AudioPacketMessage)>(128);

        if let Err(e) = web_server_instance
            .start(web_port, events.clone(), web_audio_tx)
            .await
        {
            let error = format!("Failed to start web server: {}", e);
            let rollback = rollback_start(state, &cancel_token, Vec::new()).await;
            return Err(match rollback {
                Ok(()) => error,
                Err(cleanup) => format!("{}; {}", error, cleanup),
            });
        }

        let mut web_mdns_lock = state.web_mdns.lock().await;
        match crate::network::NetworkManager::start_web_mdns(web_port, &bind_addr) {
            Ok(manager) => *web_mdns_lock = Some(manager),
            Err(e) => eprintln!("Failed to start web mDNS: {}", e),
        }

        *state.web_server.lock().await = Some(web_server_instance);

        let audio_tx_web = audio_tx;
        let web_audio_task = tokio::spawn(async move {
            let mut seq: i32 = 0;
            let mut active_generation = 0;
            while let Some((generation, packet)) = web_audio_rx.recv().await {
                if generation < active_generation {
                    continue;
                }
                if generation > active_generation {
                    active_generation = generation;
                    seq = 0;
                    if audio_tx_web
                        .send(AudioStreamEvent::SessionStarting {
                            expected: ExpectedAudioSession::Bound(0),
                            epoch: generation,
                        })
                        .await
                        .is_err()
                    {
                        break;
                    }
                }
                let ordered = micyou_protocol::micyou::AudioPacketMessageOrdered {
                    sequence_number: seq,
                    audio_packet: Some(packet),
                    timestamp: 0,
                    fec_buffer: Vec::new(),
                    fec_sequence_number: -1,
                    session_id: 0,
                    fec_packet_lengths: Vec::new(),
                };
                seq += 1;
                if !validate_audio_packet(&ordered) {
                    continue;
                }
                if audio_tx_web
                    .send(AudioStreamEvent::Packet {
                        packet: ordered,
                        epoch: generation,
                    })
                    .await
                    .is_err()
                {
                    break;
                }
            }
        });
        state.background_tasks.lock().await.push(web_audio_task);
        state.lifecycle.lock().await.mark_running();

        return Ok(format!("Web server started on port {}", web_port));
    }

    #[cfg(not(feature = "web-server"))]
    if mode == "web" {
        let error = "Web server feature not enabled".to_string();
        let rollback = rollback_start(&state, &cancel_token, Vec::new()).await;
        return Err(match rollback {
            Ok(()) => error,
            Err(cleanup) => format!("{}; {}", error, cleanup),
        });
    }

    let events_tcp = events.clone();
    let token_tcp = cancel_token.clone();
    let port_tcp = port;
    let audio_tx_tcp = audio_tx.clone();
    let stats_tcp = state.network_stats.clone();
    let mode_tcp = mode.clone();
    let bind_addr_tcp = bind_addr.clone();
    let active_connection_tcp = state.active_connection.clone();
    let takeover_lock_tcp = state.takeover_lock.clone();
    let active_audio_session_tcp = state.active_audio_session.clone();
    let (tcp_ready_tx, tcp_ready_rx) = tokio::sync::oneshot::channel();
    let tcp_task = tokio::spawn(async move {
        if let Err(e) = crate::tcp_server::start_tcp_server(
            events_tcp,
            port_tcp,
            bind_addr_tcp,
            token_tcp,
            audio_tx_tcp,
            stats_tcp,
            mode_tcp,
            active_connection_tcp,
            takeover_lock_tcp,
            active_audio_session_tcp,
            tcp_ready_tx,
        )
        .await
        {
            eprintln!("TCP Server error: {}", e);
        }
    });

    let token_udp = cancel_token.clone();
    let port_udp = udp_port.expect("non-web port validation must produce a UDP port");
    let stats_udp = state.network_stats.clone();
    let active_audio_session_udp = state.active_audio_session.clone();
    let bind_addr_udp = bind_addr.clone();
    let (udp_ready_tx, udp_ready_rx) = tokio::sync::oneshot::channel();
    let udp_task = tokio::spawn(async move {
        if let Err(e) = crate::udp_server::start_udp_server(
            audio_tx,
            port_udp,
            bind_addr_udp,
            token_udp,
            stats_udp,
            active_audio_session_udp,
            udp_ready_tx,
        )
        .await
        {
            eprintln!("UDP Server error: {}", e);
        }
    });

    let (tcp_ready, udp_ready) = tokio::join!(
        await_startup_ready(tcp_ready_rx, "TCP server", STARTUP_TIMEOUT),
        await_startup_ready(udp_ready_rx, "UDP server", STARTUP_TIMEOUT),
    );
    if let Err(error) = tcp_ready.and(udp_ready) {
        let error = format!("Failed to start network server: {}", error);
        let rollback = rollback_start(state, &cancel_token, vec![tcp_task, udp_task]).await;
        return Err(match rollback {
            Ok(()) => error,
            Err(cleanup) => format!("{}; {}", error, cleanup),
        });
    }
    state
        .background_tasks
        .lock()
        .await
        .extend([tcp_task, udp_task]);
    state.lifecycle.lock().await.mark_running();

    Ok(format!("Server started on port {}", port))
}

#[tauri::command]
pub async fn stop_server(app: AppHandle, state: State<'_, ServerState>) -> Result<String, String> {
    let events: crate::events::SharedEvents =
        std::sync::Arc::new(crate::events::TauriEventSink(app));
    stop_server_inner(&state, events).await
}

/// Core server shutdown, independent of the Tauri runtime.
pub async fn stop_server_inner(
    state: &ServerState,
    events: crate::events::SharedEvents,
) -> Result<String, String> {
    let _lifecycle_guard = state.lifecycle_gate.enter().await;
    state
        .spectrum_streaming_enabled
        .store(false, std::sync::atomic::Ordering::Release);

    #[cfg(feature = "web-server")]
    {
        let mut web_lock = state.web_server.lock().await;
        if let Some(web) = web_lock.take() {
            web.stop().await;
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

    let token = state.cancel_token.lock().await.take();
    let had_token = token.is_some();
    if let Some(token) = token {
        token.cancel();
    }
    state.lifecycle.lock().await.begin_stopping();
    let tasks = std::mem::take(&mut *state.background_tasks.lock().await);
    crate::tcp_server::cleanup_session_state(&state.active_connection, &state.active_audio_session)
        .await;
    join_tasks_bounded(tasks, NETWORK_TASK_JOIN_TIMEOUT).await;
    let audio_result = state
        .lifecycle
        .lock()
        .await
        .join_audio_bounded(AUDIO_JOIN_TIMEOUT)
        .await;
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
    audio_result?;
    if had_token {
        events.server_stopped();
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
    crate::mode_lock::release();
    app.exit(0);
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::{should_capture_loopback, validate_server_port};

    #[test]
    fn loopback_waits_for_first_audio_packet() {
        assert!(!should_capture_loopback(false, false, true));
        assert!(!should_capture_loopback(true, false, true));
        assert!(!should_capture_loopback(true, true, false));
        assert!(should_capture_loopback(true, true, true));
    }

    #[test]
    fn non_web_port_zero_is_rejected() {
        assert!(validate_server_port(0, "wifi").is_err());
    }

    #[test]
    fn non_web_port_65534_produces_last_udp_port() {
        assert_eq!(validate_server_port(65534, "wifi"), Ok(Some(65535)));
    }

    #[test]
    fn non_web_port_65535_is_rejected() {
        assert!(validate_server_port(65535, "wifi").is_err());
    }

    #[test]
    fn web_port_zero_is_rejected() {
        assert!(validate_server_port(0, "web").is_err());
    }
}
