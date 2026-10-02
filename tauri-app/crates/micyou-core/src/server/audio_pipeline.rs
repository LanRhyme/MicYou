/*
 * MicYou — Turns your Android device into a high-quality PC microphone.
 * Copyright (C) 2026 LanRhyme <https://github.com/MicYou-Dev/MicYou>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version, with the MicYou Plugin Exception.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 */

//! The per-server audio thread: reorders and FEC-recovers packets, decodes
//! PCM/Opus, resamples to 48 kHz, runs the DSP chain and feeds the output.

use crate::events::{AecStatus, SharedEvents, SpectrumPayload};
use crate::plugins::PluginHost;
use crate::server::output::{self, AudioOutputHandle};
use crate::stats::NetworkStats;
use crate::transport::jitter_buffer::JitterBuffer;
use crate::transport::opus;
use crate::transport::session::AudioStreamEvent;
use crate::transport::udp::{ActiveAudioSession, SharedActiveAudioSession};
use micyou_audio::dsp::{AudioDspSettings, DspProcessor};
use micyou_audio::AecFailure;
use std::path::PathBuf;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, RwLock};
use tokio::sync::{mpsc, oneshot};

/// Output sample rate of the whole pipeline.
const OUTPUT_RATE: u32 = 48_000;

pub(super) struct Pipeline {
    pub audio_rx: mpsc::Receiver<AudioStreamEvent>,
    pub ready_tx: oneshot::Sender<Result<(), String>>,
    pub audio_output: Arc<AudioOutputHandle>,
    pub output_device: Option<String>,
    pub output_buffer_ms: usize,
    pub resource_dir: Option<PathBuf>,
    pub dsp_settings: Arc<RwLock<AudioDspSettings>>,
    pub plugins: Arc<PluginHost>,
    pub events: SharedEvents,
    pub active_audio_session: SharedActiveAudioSession,
    pub is_monitoring: Arc<AtomicBool>,
    pub spectrum_streaming_enabled: Arc<AtomicBool>,
    pub stats: Arc<NetworkStats>,
    /// Web clients send already-clean browser audio: skip the DSP chain.
    pub bypass_dsp: bool,
}

pub(super) fn spawn(pipeline: Pipeline) -> std::thread::JoinHandle<()> {
    std::thread::Builder::new()
        .name("micyou-audio".into())
        .spawn(move || run(pipeline))
        .expect("spawning the audio thread only fails on resource exhaustion")
}

fn disable_aec_runtime(runtime_available: &mut bool, events: &SharedEvents, reason: AecFailure) {
    if !std::mem::replace(runtime_available, false) {
        return;
    }
    events.aec_status_changed(AecStatus {
        available: false,
        enabled: false,
        reason: Some(reason),
    });
}

fn restore_aec_runtime(
    runtime_available: &mut bool,
    settings: &RwLock<AudioDspSettings>,
    events: &SharedEvents,
) {
    *runtime_available = true;
    let enabled = settings
        .read()
        .unwrap_or_else(|poisoned| poisoned.into_inner())
        .aec_enabled;
    events.aec_status_changed(AecStatus {
        available: true,
        enabled,
        reason: None,
    });
}

fn should_capture_loopback(
    transport_active: bool,
    audio_received: bool,
    aec_enabled: bool,
    runtime_available: bool,
) -> bool {
    transport_active && audio_received && aec_enabled && runtime_available
}

/// Decode little-endian PCM into f32 samples. Returns false for an unknown
/// `audio_format` (Android AudioFormat encoding constant).
fn decode_pcm(audio_format: i32, buffer: &[u8], out: &mut Vec<f32>) -> bool {
    out.clear();
    match audio_format {
        // ENCODING_PCM_16BIT
        2 => out.extend(
            buffer
                .as_chunks::<2>().0.iter()
                .map(|c| i16::from_le_bytes([c[0], c[1]]) as f32 / 32768.0),
        ),
        // ENCODING_PCM_8BIT (unsigned)
        3 => out.extend(buffer.iter().map(|&b| (b as f32 - 128.0) / 128.0)),
        // ENCODING_PCM_FLOAT
        4 => out.extend(
            buffer
                .as_chunks::<4>().0.iter()
                .map(|c| f32::from_le_bytes([c[0], c[1], c[2], c[3]])),
        ),
        // ENCODING_PCM_24BIT_PACKED
        6 => out.extend(buffer.as_chunks::<3>().0.iter().map(|c| {
            let sample = (c[0] as i32) | ((c[1] as i32) << 8) | ((c[2] as i8 as i32) << 16);
            sample as f32 / 8_388_608.0
        })),
        _ => return false,
    }
    true
}

fn run(pipeline: Pipeline) {
    let Pipeline {
        mut audio_rx,
        ready_tx,
        audio_output: audio_output_shared,
        output_device: resolved_output_device,
        output_buffer_ms,
        resource_dir: resource_root,
        dsp_settings,
        plugins: plugins_shared,
        events: events_audio,
        active_audio_session: active_audio_session_audio,
        is_monitoring: is_monitoring_flag,
        spectrum_streaming_enabled,
        stats: stats_audio,
        bypass_dsp: is_web_mode,
    } = pipeline;

    // Ensure the virtual device is open. This is normally a no-op (already
    // opened at app startup); it also covers CLI/TUI first run and the rare
    // case where opening failed earlier and a later attempt succeeds.
    if !output::ensure_started(
        &audio_output_shared,
        resolved_output_device,
        output_buffer_ms,
        resource_root.as_deref(),
    ) {
        log::error!("[Audio] Output device unavailable; audio will be silent");
    }
    let _ = ready_tx.send(Ok(()));
    let mut dsp_processor = DspProcessor::new(dsp_settings.clone(), resource_root);
    // Attach the plugin DSP stage (runs at the legacy "Plugins" node and
    // at per-plugin "Plugin:<id>" nodes, issue #347).
    dsp_processor.set_external_hook(Some(plugins_shared.dsp_hook()));
    let mut jb = JitterBuffer::new(12);
    let mut frame_counter: u32 = 0;
    let mut input_resampler: Option<micyou_audio::RubatoResampler> = None;
    let mut current_input_sample_rate: u32 = 0;
    let mut resample_out_buf = Vec::new();
    let mut pcm_f32 = Vec::new();
    // Opus decoder is keyed by (sample_rate, channel_count); recreated whenever
    // those change or a new transport session starts (stateful codec).
    let mut opus_decoder: Option<(u32, usize, opus::Decoder)> = None;
    let mut opus_float_buf: Vec<f32> = Vec::new();

    // Speaker loopback capture for the AEC far-end reference. Windows uses
    // WASAPI loopback; Linux records the default physical playback sink.
    // Both start lazily only after an AEC-enabled session sends audio.
    #[cfg(any(target_os = "windows", target_os = "linux"))]
    let loopback: Option<micyou_audio::LoopbackCapture> =
        Some(micyou_audio::LoopbackCapture::new());
    #[cfg(not(any(target_os = "windows", target_os = "linux")))]
    let loopback: Option<micyou_audio::LoopbackCapture> = None;

    let mut audio_received_for_session = false;
    let mut aec_runtime_available = true;
    // A newly started server always begins with a fresh runtime state, even
    // before the first client session arrives.
    if loopback.is_some() {
        restore_aec_runtime(&mut aec_runtime_available, &dsp_settings, &events_audio);
    }
    // Sync the AEC far-end capture with actual audio flow. A control session
    // alone is not enough: while waiting for the first valid audio packet,
    // there is no microphone stream that needs an echo reference.
    let sync_loopback = |audio_received: &mut bool, runtime_available: &mut bool| {
        let transport_active = !matches!(
            *active_audio_session_audio
                .read()
                .unwrap_or_else(|p| p.into_inner()),
            ActiveAudioSession::Inactive
        );
        if !transport_active {
            *audio_received = false;
        }

        let Some(lb) = &loopback else {
            return transport_active;
        };
        let aec_enabled = dsp_settings
            .read()
            .unwrap_or_else(|poisoned| poisoned.into_inner())
            .aec_enabled;
        let should_capture = should_capture_loopback(
            transport_active,
            *audio_received,
            aec_enabled,
            *runtime_available,
        );

        if !should_capture {
            if lb.is_active() {
                lb.stop();
            }
            return transport_active;
        }
        if lb.is_active() {
            return transport_active;
        }

        let failure = lb.take_failure_reason().or_else(|| lb.start().err());
        if let Some(reason) = failure {
            disable_aec_runtime(runtime_available, &events_audio, reason);
        } else {
            log::info!("[Audio] Starting speaker loopback capture for AEC");
        }
        transport_active
    };

    loop {
        // Idle heartbeat every 500ms: with no device session the loopback
        // capture stream stays stopped (biggest idle CPU win).
        match audio_rx.try_recv() {
            Err(mpsc::error::TryRecvError::Disconnected) => break,
            Err(mpsc::error::TryRecvError::Empty) => {
                // Poll fast (10ms) while a session is active: audio packets
                // can arrive after a silence gap and must not sit in the
                // channel for up to 500ms (that caused audible dropouts at
                // the start of each utterance). Idle servers sleep 500ms.
                let session_active =
                    sync_loopback(&mut audio_received_for_session, &mut aec_runtime_available);
                std::thread::sleep(std::time::Duration::from_millis(if session_active {
                    10
                } else {
                    500
                }));
            }
            Ok(event) => {
                audio_output_shared.set_monitoring(
                    is_monitoring_flag.load(Ordering::Relaxed),
                );
                match event {
                    AudioStreamEvent::SessionStarting { expected, epoch } => {
                        audio_received_for_session = false;
                        if let Some(lb) = &loopback {
                            lb.reset_session();
                        }
                        dsp_processor.reset_aec_session();
                        opus_decoder = None;
                        if loopback.is_some() {
                            restore_aec_runtime(
                                &mut aec_runtime_available,
                                &dsp_settings,
                                &events_audio,
                            );
                        }
                        jb.prepare_transport_session_epoch(expected, epoch);
                        continue;
                    }
                    AudioStreamEvent::Packet { packet, epoch } => {
                        audio_received_for_session = true;
                        sync_loopback(
                            &mut audio_received_for_session,
                            &mut aec_runtime_available,
                        );
                        jb.push_epoch(packet, epoch);
                    }
                }
                let packets: Vec<_> = std::iter::from_fn(|| jb.pop()).collect();

                for ordered_packet in packets {
                    if let Some(audio_data) = ordered_packet.audio_packet {
                        if audio_data.codec == micyou_protocol::CODEC_OPUS {
                            // Opus decode: reorder on flags/sample-rate changes, then
                            // decode directly into f32 so it feeds the DSP chain intact.
                            let channels = audio_data.channel_count as usize;
                            let sample_rate = audio_data.sample_rate as u32;
                            let needs_decoder = match &opus_decoder {
                                Some((sr, ch, _)) => *sr != sample_rate || *ch != channels,
                                None => true,
                            };
                            if needs_decoder {
                                let created =
                                    opus::Channels::from_channel_count(channels)
                                        .and_then(|ch| {
                                            opus::Decoder::new(sample_rate, ch).ok()
                                        });
                                opus_decoder = created.map(|dec| (sample_rate, channels, dec));
                                if opus_decoder.is_none() {
                                    log::error!(
                                        "[Audio] Failed to create Opus decoder for {sample_rate}Hz/{channels}ch"
                                    );
                                }
                            }
                            if let Some((_, _, decoder)) = opus_decoder.as_mut() {
                                let target_frames = (sample_rate as usize / 50) * channels; // 20ms
                                if opus_float_buf.len() != target_frames {
                                    opus_float_buf.resize(target_frames, 0.0);
                                }
                                match decoder
                                    .decode_float(&audio_data.buffer, &mut opus_float_buf)
                                {
                                    Ok(frames) => {
                                        pcm_f32.clear();
                                        pcm_f32.extend_from_slice(
                                            &opus_float_buf[..frames * channels],
                                        );
                                    }
                                    Err(e) => {
                                        log::warn!("[Audio] Opus decode error: {e}");
                                        pcm_f32.clear();
                                    }
                                }
                            } else {
                                pcm_f32.clear();
                            }
                        } else if !decode_pcm(
                            audio_data.audio_format,
                            &audio_data.buffer,
                            &mut pcm_f32,
                        ) {
                            log::warn!(
                                "[Audio] Unsupported audio format: {}",
                                audio_data.audio_format
                            );
                        }
                        if !pcm_f32.is_empty() {
                            let channels = audio_data.channel_count as usize;
                            let sample_rate = audio_data.sample_rate as u32;

                            if sample_rate > 0 && sample_rate != OUTPUT_RATE {
                                if current_input_sample_rate != sample_rate {
                                    match micyou_audio::RubatoResampler::new(
                                        sample_rate,
                                        OUTPUT_RATE,
                                        channels.max(1),
                                    ) {
                                        Ok(res) => {
                                            input_resampler = Some(res);
                                            current_input_sample_rate = sample_rate;
                                        }
                                        Err(e) => {
                                            log::error!("[Audio] Failed to create resampler: {e}");
                                            input_resampler = None;
                                            current_input_sample_rate = OUTPUT_RATE;
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
                                current_input_sample_rate = OUTPUT_RATE;
                            }

                            let queued_samples = audio_output_shared.queued_samples();
                            let queued_ms = if channels > 0 {
                                (queued_samples as f64 / channels as f64) / 48.0
                            } else {
                                0.0
                            };

                            // Web mode: skip DSP for now, output raw audio directly
                            let (input_rms, processed_rms) = if is_web_mode {
                                let sum: f32 = pcm_f32.iter().map(|x| x * x).sum();
                                let rms = (sum / pcm_f32.len() as f32).sqrt();
                                (rms, rms)
                            } else {
                                // Read speaker loopback for AEC far-end reference.
                                // This captures the ACTUAL speaker output (WASAPI/BlackHole/PipeWire),
                                // which is the true echo source the phone mic picks up.
                                // Feed one mono reference sample for each near-end frame.
                                // Matching the processed frame count prevents drift when
                                // packet sizes or input sample rates vary.
                                let near_frames = pcm_f32.len() / channels.max(1);
                                if let Some(far_data) = loopback
                                    .as_ref()
                                    .filter(|capture| capture.is_active())
                                    .map(|capture| capture.read(near_frames))
                                {
                                    dsp_processor.set_far_end_audio(&far_data);
                                }
                                let (raw, processed) = dsp_processor.process(
                                    &mut pcm_f32,
                                    channels.max(1),
                                    queued_ms,
                                );
                                if let Some(reason) = dsp_processor.take_aec_failure() {
                                    disable_aec_runtime(
                                        &mut aec_runtime_available,
                                        &events_audio,
                                        reason,
                                    );
                                }
                                (raw, processed)
                            };

                            // Local hard-mute: the output engine drops the
                            // audio itself; additionally report silence so
                            // UI meters and plugin snapshots read zero
                            // levels while muted.
                            let muted_now = stats_audio.is_muted();
                            let (input_rms, processed_rms) = if muted_now {
                                (0.0, 0.0)
                            } else {
                                (input_rms, processed_rms)
                            };

                            // 写入精确的 RMS 供插件 API 读取
                            stats_audio.set_levels(input_rms, processed_rms);

                            audio_output_shared.push(pcm_f32.clone(), channels.max(1));

                            frame_counter = frame_counter.wrapping_add(1);
                            if frame_counter.is_multiple_of(6) {
                                let level = (processed_rms * 500.0).min(100.0) as u32;
                                events_audio.audio_level(level);

                                if spectrum_streaming_enabled
                                    .load(Ordering::Acquire)
                                {
                                    let (mut raw_spec, mut proc_spec) =
                                        dsp_processor.get_spectrums();
                                    if muted_now {
                                        raw_spec.iter_mut().for_each(|v| *v = 0.0);
                                        proc_spec.iter_mut().for_each(|v| *v = 0.0);
                                    }
                                    events_audio.audio_spectrum(SpectrumPayload {
                                    raw: raw_spec,
                                    processed: proc_spec,
                                });
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
}

#[cfg(test)]
mod tests {
    use super::{decode_pcm, should_capture_loopback};

    #[test]
    fn loopback_waits_for_first_audio_packet() {
        assert!(!should_capture_loopback(false, false, true, true));
        assert!(!should_capture_loopback(true, false, true, true));
        assert!(!should_capture_loopback(true, true, false, true));
        assert!(!should_capture_loopback(true, true, true, false));
        assert!(should_capture_loopback(true, true, true, true));
    }

    #[test]
    fn decodes_supported_pcm_formats() {
        let mut out = Vec::new();
        assert!(decode_pcm(2, &[0x00, 0x40], &mut out));
        assert_eq!(out, vec![0.5]);
        assert!(decode_pcm(3, &[128], &mut out));
        assert_eq!(out, vec![0.0]);
        assert!(decode_pcm(4, &1.0f32.to_le_bytes(), &mut out));
        assert_eq!(out, vec![1.0]);
        assert!(decode_pcm(6, &[0x00, 0x00, 0xc0], &mut out));
        assert_eq!(out, vec![-0.5]);
        assert!(!decode_pcm(9, &[1, 2], &mut out));
        assert!(out.is_empty());
    }
}
