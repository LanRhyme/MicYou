//! Audio output pipeline — runs on a dedicated thread.
//! Decodes incoming packets → jitter buffer → resampling → DSP → audio device.

use tauri::{AppHandle, Emitter};
use std::sync::Arc;
use std::sync::RwLock;

use crate::audio::process::settings::AudioDspSettings;
use crate::audio::process::dsp::DspProcessor;
use crate::audio::{AudioOutputManager, RubatoResampler};
use crate::audio::jitter_buffer::JitterBuffer;
use crate::protocol::micyou::AudioPacketMessageOrdered;

#[derive(serde::Serialize, Clone)]
pub struct SpectrumPayload {
    pub raw: Vec<f32>,
    pub processed: Vec<f32>,
}

/// Find the ONNX model directory, checking exe dir → resources dir → dev dir.
fn find_model_dir() -> Option<std::path::PathBuf> {
    let exe_dir = std::env::current_exe()
        .ok()
        .and_then(|p| p.parent().map(|d| d.to_path_buf()));
    exe_dir.as_ref().and_then(|d| {
        let model_direct = d.join("ulunas.onnx");
        if model_direct.exists() {
            return Some(d.clone());
        }
        let res_dir = d.join("resources");
        if res_dir.join("ulunas.onnx").exists() {
            return Some(res_dir);
        }
        let dev_res = std::path::PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("resources");
        if dev_res.join("ulunas.onnx").exists() {
            return Some(dev_res);
        }
        None
    })
}

/// Decode raw bytes to f32 PCM based on format tag.
fn decode_pcm(buffer: &[u8], format: i32) -> Vec<f32> {
    let mut pcm = Vec::new();
    match format {
        2 => {
            // i16 PCM
            for chunk in buffer.chunks_exact(2) {
                let sample_i16 = i16::from_le_bytes([chunk[0], chunk[1]]);
                pcm.push(sample_i16 as f32 / 32768.0);
            }
        }
        3 => {
            // u8 PCM
            for &byte in buffer {
                pcm.push((byte as f32 - 128.0) / 128.0);
            }
        }
        4 => {
            // f32 PCM
            for chunk in buffer.chunks_exact(4) {
                let sample_f32 = f32::from_le_bytes([chunk[0], chunk[1], chunk[2], chunk[3]]);
                pcm.push(sample_f32);
            }
        }
        6 => {
            // 24-bit PCM
            for chunk in buffer.chunks_exact(3) {
                let sample24 = (chunk[0] as i32) | ((chunk[1] as i32) << 8) | ((chunk[2] as i32) << 16);
                pcm.push((sample24 as f32) / 8388608.0);
            }
        }
        _ => {
            eprintln!("Unsupported audio format: {}", format);
        }
    }
    pcm
}

/// Spawn the audio output processing thread.
///
/// * `app_handle` - for emitting audio-level / audio-spectrum events
/// * `audio_rx` - receives ordered audio packets from TCP/UDP/web
/// * `output_device` - optional target device name
/// * `dsp_settings` - shared DSP settings
/// * `is_web_mode` - if true, skips DSP processing
pub fn spawn_audio_pipeline(
    app_handle: AppHandle,
    mut audio_rx: tokio::sync::mpsc::Receiver<AudioPacketMessageOrdered>,
    output_device: Option<String>,
    dsp_settings: Arc<RwLock<AudioDspSettings>>,
    is_web_mode: bool,
) {
    std::thread::spawn(move || {
        let mut audio_manager = AudioOutputManager::new();
        if let Err(e) = audio_manager.start(output_device) {
            eprintln!("Failed to start audio output: {}", e);
            return;
        }

        let resources_dir = find_model_dir();
        let mut dsp_processor = DspProcessor::new(dsp_settings, resources_dir);
        let mut jb = JitterBuffer::new(12);
        let mut frame_counter = 0u32;
        let mut input_resampler: Option<RubatoResampler> = None;
        let mut current_input_sample_rate: u32 = 0;

        while let Some(packet) = audio_rx.blocking_recv() {
            jb.push(packet);
            let packets: Vec<_> = std::iter::from_fn(|| jb.pop()).collect();

            for ordered_packet in packets {
                if let Some(audio_data) = ordered_packet.audio_packet {
                    let mut pcm_f32 = decode_pcm(&audio_data.buffer, audio_data.audio_format);

                    if !pcm_f32.is_empty() {
                        let channels = audio_data.channel_count as usize;
                        let sample_rate = audio_data.sample_rate as u32;

                        // Input resampling to 48kHz
                        if sample_rate > 0 && sample_rate != 48000 {
                            if current_input_sample_rate != sample_rate {
                                input_resampler = Some(RubatoResampler::new(
                                    sample_rate, 48000, channels.max(1),
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

                        let processed_rms = if is_web_mode {
                            let sum: f32 = pcm_f32.iter().map(|x| x * x).sum();
                            (sum / pcm_f32.len() as f32).sqrt()
                        } else {
                            let (_raw, processed) = dsp_processor.process(&mut pcm_f32, channels.max(1), queued_ms);
                            processed
                        };

                        audio_manager.push_audio_data(&pcm_f32, channels.max(1));

                        frame_counter += 1;
                        if frame_counter % 3 == 0 {
                            let level = (processed_rms * 500.0).min(100.0) as u32;
                            let _ = app_handle.emit("audio-level", level);

                            let (raw_spec, proc_spec) = dsp_processor.get_spectrums();
                            let _ = app_handle.emit("audio-spectrum", SpectrumPayload {
                                raw: raw_spec,
                                processed: proc_spec,
                            });
                        }
                    }
                }
            }
        }
    });
}
