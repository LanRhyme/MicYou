use micyou_protocol::micyou::AudioPacketMessageOrdered;
use std::path::PathBuf;
use std::sync::{Arc, RwLock};
use tokio::sync::{mpsc, oneshot};

use crate::dsp::{AudioDspSettings, DspProcessor};
use crate::engine::AudioOutputManager;
use crate::formats::{decode_to_f32, AudioFormat};
use crate::jitter::JitterBuffer;

/// Events emitted by the audio pipeline for the frontend.
#[derive(Debug, Clone)]
pub enum PipelineEvent {
    AudioLevel(u32),
    AudioSpectrum { raw: Vec<f32>, processed: Vec<f32> },
}

pub struct AudioPipeline {
    tx: mpsc::Sender<AudioPacketMessageOrdered>,
}

pub struct PipelineConfig {
    pub output_device: Option<String>,
    pub dsp_settings: Arc<RwLock<AudioDspSettings>>,
    pub model_dir: Option<PathBuf>,
    pub is_web_mode: bool,
}

impl AudioPipeline {
    /// Spawn the audio processing thread.
    /// Returns (handle, ready_signal). The ready signal resolves once AudioOutputManager is started.
    pub fn spawn(
        config: PipelineConfig,
        event_tx: mpsc::Sender<PipelineEvent>,
    ) -> (Self, oneshot::Receiver<Result<(), String>>) {
        let (audio_tx, mut audio_rx) = mpsc::channel(1024);
        let (ready_tx, ready_rx) = oneshot::channel();

        std::thread::spawn(move || {
            let mut audio_manager = AudioOutputManager::new();
            if let Err(e) = audio_manager.start(config.output_device) {
                let _ = ready_tx.send(Err(e.to_string()));
                return;
            }
            let _ = ready_tx.send(Ok(()));

            // Model directory resolution
            let resources_dir = config.model_dir.or_else(|| {
                let exe_dir = std::env::current_exe()
                    .ok()
                    .and_then(|p| p.parent().map(|d| d.to_path_buf()));
                exe_dir.as_ref().and_then(|d| {
                    if d.join("ulunas.onnx").exists() {
                        return Some(d.clone());
                    }
                    let res = d.join("resources");
                    if res.join("ulunas.onnx").exists() {
                        return Some(res);
                    }
                    let dev = PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("resources");
                    if dev.join("ulunas.onnx").exists() {
                        return Some(dev);
                    }
                    None
                })
            });

            let mut dsp = DspProcessor::new(config.dsp_settings, resources_dir);
            let mut jb = JitterBuffer::new(12);
            let mut frame_counter: u64 = 0;
            let mut input_resampler: Option<crate::RubatoResampler> = None;
            let mut current_input_sample_rate: u32 = 0;
            let mut resample_out_buf = Vec::new();
            let mut pcm_f32 = Vec::new();

            while let Some(packet) = audio_rx.blocking_recv() {
                jb.push(packet);
                let packets: Vec<_> = std::iter::from_fn(|| jb.pop()).collect();

                for ordered in packets {
                    if let Some(audio_data) = ordered.audio_packet {
                        pcm_f32.clear();
                        if let Some(format) = AudioFormat::from_i32(audio_data.audio_format) {
                            decode_to_f32(&audio_data.buffer, format, &mut pcm_f32);
                        }

                        if !pcm_f32.is_empty() {
                            let channels = audio_data.channel_count as usize;
                            let sample_rate = audio_data.sample_rate as u32;

                            // Resample if needed
                            if sample_rate > 0 && sample_rate != 48000 {
                                if current_input_sample_rate != sample_rate {
                                    match crate::RubatoResampler::new(
                                        sample_rate,
                                        48000,
                                        channels.max(1),
                                    ) {
                                        Ok(res) => {
                                            input_resampler = Some(res);
                                            current_input_sample_rate = sample_rate;
                                        }
                                        Err(e) => {
                                            log::error!(target: "audio", "Failed to create resampler: {}", e);
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

                            let processed_rms = if config.is_web_mode {
                                let sum: f32 = pcm_f32.iter().map(|x| x * x).sum();
                                (sum / pcm_f32.len() as f32).sqrt()
                            } else {
                                let (_raw, processed) =
                                    dsp.process(&mut pcm_f32, channels.max(1), queued_ms);
                                processed
                            };

                            audio_manager.push_audio_data(&pcm_f32, channels.max(1));

                            frame_counter += 1;
                            if frame_counter % 3 == 0 {
                                let level = (processed_rms * 500.0).min(100.0) as u32;
                                let _ = event_tx.try_send(PipelineEvent::AudioLevel(level));

                                let (raw, processed) = dsp.get_spectrums();
                                let _ = event_tx
                                    .try_send(PipelineEvent::AudioSpectrum { raw, processed });
                            }
                        }
                    }
                }
            }
        });

        (Self { tx: audio_tx }, ready_rx)
    }

    pub fn sender(&self) -> &mpsc::Sender<AudioPacketMessageOrdered> {
        &self.tx
    }
}
