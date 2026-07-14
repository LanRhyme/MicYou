pub mod agc;
pub mod buffer;
pub mod dereverb;
pub mod equalizer;
pub mod equalizer_config;
pub mod noise;
pub mod settings;
pub mod spectrum;
pub mod util;
pub mod vad;

use std::path::PathBuf;
use std::sync::{Arc, RwLock};

use self::agc::AgcState;
use self::buffer::BufferLevelManager;
use self::dereverb::DereverbState;
use self::equalizer::EqualizerEffect;
use self::noise::NoiseReducer;
use self::spectrum::SpectrumState;
use self::util::{compute_rms, soft_clip};
use self::vad::VadState;

// Re-export public types
pub use self::equalizer_config::EqualizerConfig;
pub use self::settings::AudioDspSettings;

const RNNOISE_FRAME_SIZE: usize = 480;

/// Main DSP processor orchestrating the audio processing chain.
pub struct DspProcessor {
    settings: Arc<RwLock<AudioDspSettings>>,
    noise_reducer: NoiseReducer,
    equalizer: EqualizerEffect,
    buffer_manager: BufferLevelManager,
    dereverb: DereverbState,
    agc: AgcState,
    vad: VadState,
    spectrum: SpectrumState,
    // Frame accumulation buffer (align to 480-sample frames for noise reduction)
    accum_buffer: Vec<f32>,
    output_buffer: Vec<f32>,
    to_process_buf: Vec<f32>,
}

impl DspProcessor {
    pub fn new(settings: Arc<RwLock<AudioDspSettings>>, model_dir: Option<PathBuf>) -> Self {
        Self {
            settings: settings.clone(),
            noise_reducer: NoiseReducer::new(model_dir),
            equalizer: EqualizerEffect::new(),
            buffer_manager: BufferLevelManager::new(),
            dereverb: DereverbState::new(480),
            agc: AgcState::new(),
            vad: VadState::new(),
            spectrum: SpectrumState::new(64),
            accum_buffer: Vec::new(),
            output_buffer: vec![0.0; 960],
            to_process_buf: Vec::new(),
        }
    }

    /// Process a chunk of f32 PCM audio in-place.
    /// Returns (raw_rms, processed_rms) for level metering.
    /// Internally accumulates to 480-sample aligned frames before noise reduction,
    /// matching the KMP AudioProcessorPipeline behavior.
    pub fn process(&mut self, data: &mut Vec<f32>, channels: usize, queued_ms: f64) -> (f32, f32) {
        if data.is_empty() {
            return (0.0, 0.0);
        }

        let raw_rms = compute_rms(data);
        self.spectrum.compute(data, true);

        // Frame accumulation: align to 480*channels samples before processing
        self.accum_buffer.extend_from_slice(data);
        let samples_per_frame = RNNOISE_FRAME_SIZE * channels.max(1);
        let frame_count = self.accum_buffer.len() / samples_per_frame;

        if frame_count == 0 {
            data.clear();
            return (raw_rms, 0.0);
        }

        let process_count = frame_count * samples_per_frame;
        self.to_process_buf.clear();
        self.to_process_buf
            .extend_from_slice(&self.accum_buffer[..process_count]);
        self.accum_buffer.drain(..process_count);

        let mut to_process = std::mem::take(&mut self.to_process_buf);

        let settings = self.settings.read().unwrap().clone();
        self.equalizer.update_filters(&settings.equalizer);

        for effect in &settings.processing_chain {
            match effect.as_str() {
                "NoiseReduction" => {
                    if settings.ns_enabled {
                        self.noise_reducer
                            .process(&mut to_process, channels.max(1), &settings);
                    }
                }
                "Dereverb" => {
                    if settings.dereverb_enabled {
                        self.dereverb.process(
                            &mut to_process,
                            channels.max(1),
                            settings.dereverb_level,
                        );
                    }
                }
                "Equalizer" => {
                    if settings.equalizer.enabled {
                        self.equalizer.process(&mut to_process, channels);
                    }
                }
                "Amplifier" => {
                    if settings.gain.abs() > 0.01 {
                        let gain_linear = 10.0_f32.powf(settings.gain / 20.0);
                        for sample in to_process.iter_mut() {
                            *sample *= gain_linear;
                        }
                    }
                }
                "AGC" => {
                    if settings.agc_enabled {
                        let attack_rate = settings.agc_attack / 1000.0;
                        let decay_rate = settings.agc_decay / 10000.0;
                        self.agc.process(
                            &mut to_process,
                            settings.agc_target,
                            attack_rate,
                            decay_rate,
                        );
                    }
                }
                "VAD" => {
                    if settings.vad_enabled {
                        self.vad.process(&mut to_process, settings.vad_threshold);
                    }
                }
                _ => {}
            }
        }

        self.buffer_manager
            .process(&to_process, channels, queued_ms, &mut self.output_buffer);

        // Soft clip
        for sample in self.output_buffer.iter_mut() {
            *sample = soft_clip(*sample);
        }

        self.to_process_buf = to_process;

        data.clear();
        data.extend_from_slice(&self.output_buffer);

        let processed_rms = compute_rms(data);
        self.spectrum.compute(data, false);

        (raw_rms, processed_rms)
    }

    pub fn get_spectrums(&self) -> (Vec<f32>, Vec<f32>) {
        self.spectrum.get_spectrums()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn make_settings() -> Arc<RwLock<AudioDspSettings>> {
        Arc::new(RwLock::new(AudioDspSettings::default()))
    }

    #[test]
    fn test_gain_positive() {
        let mut dsp = DspProcessor::new(make_settings(), None);
        let mut data = vec![0.5f32; 480];
        let settings = AudioDspSettings {
            gain: 6.0,
            processing_chain: vec!["Amplifier".to_string()],
            ..AudioDspSettings::default()
        };
        *dsp.settings.write().unwrap() = settings;
        dsp.process(&mut data, 1, 50.0);
        let rms = compute_rms(&data);
        // +6dB ≈ ×2 amplitude, but we have soft clipping and processing chain
        assert!(rms > 0.5, "gain should increase amplitude, got rms={}", rms);
    }

    #[test]
    fn test_gain_negative() {
        let mut dsp = DspProcessor::new(make_settings(), None);
        let mut data = vec![0.5f32; 480];
        let settings = AudioDspSettings {
            gain: -6.0,
            processing_chain: vec!["Amplifier".to_string()],
            ..AudioDspSettings::default()
        };
        *dsp.settings.write().unwrap() = settings;
        dsp.process(&mut data, 1, 50.0);
        let rms = compute_rms(&data);
        assert!(
            rms < 0.3,
            "negative gain should decrease amplitude, got rms={}",
            rms
        );
    }
}
