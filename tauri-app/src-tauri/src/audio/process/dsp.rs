use std::sync::{Arc, RwLock};
use std::path::PathBuf;

use crate::audio::process::settings::AudioDspSettings;
use crate::audio::process::resampler::ResamplerEffect;
use crate::audio::process::equalizer::EqualizerEffect;
use crate::audio::process::agc::AgcEffect;
use crate::audio::process::vad::VadEffect;
use crate::audio::process::dereverb::DereverbEffect;

#[cfg(feature = "dsp")]
use crate::audio::process::noise::rnnoise::RnnoiseDenoiser;
#[cfg(feature = "noise-suppression")]
use crate::audio::process::noise::ulunas::UlunasProcessor;
#[cfg(feature = "dsp")]
use crate::audio::process::noise::speex::SpeexDenoiser;
use crate::audio::process::noise::lightweight::LightweightDenoiser;

/// The main DSP processor. Operates on f32 PCM samples at 48kHz.
/// Internally accumulates to 480-sample aligned frames before processing
/// (noise reduction requires exactly 480-sample frames).
pub struct DspProcessor {
    settings: Arc<RwLock<AudioDspSettings>>,
    #[cfg(feature = "dsp")]
    rnnoise: RnnoiseDenoiser,
    #[cfg(feature = "noise-suppression")]
    ulunas_left: Option<UlunasProcessor>,
    #[cfg(feature = "noise-suppression")]
    ulunas_right: Option<UlunasProcessor>,
    #[cfg(feature = "noise-suppression")]
    ulunas_buffer_left: Vec<f32>,
    #[cfg(feature = "noise-suppression")]
    ulunas_buffer_right: Vec<f32>,
    #[cfg(feature = "noise-suppression")]
    ulunas_model_path: Option<PathBuf>,
    #[cfg(feature = "dsp")]
    speex: SpeexDenoiser,
    lightweight: LightweightDenoiser,
    equalizer: EqualizerEffect,
    resampler: ResamplerEffect,
    dereverb: DereverbEffect,
    agc: AgcEffect,
    vad: VadEffect,
    raw_spectrum: Vec<f32>,
    processed_spectrum: Vec<f32>,
    accum_buffer: Vec<f32>,
}

const RNNOISE_FRAME_SIZE: usize = 480;

impl DspProcessor {
    pub fn new(settings: Arc<RwLock<AudioDspSettings>>, _model_dir: Option<PathBuf>) -> Self {
        Self {
            settings: settings.clone(),
            #[cfg(feature = "noise-suppression")]
            ulunas_model_path: _model_dir.map(|d| d.join("ulunas.onnx")),
            #[cfg(feature = "dsp")]
            rnnoise: RnnoiseDenoiser::new(),
            #[cfg(feature = "noise-suppression")]
            ulunas_left: None,
            #[cfg(feature = "noise-suppression")]
            ulunas_right: None,
            #[cfg(feature = "noise-suppression")]
            ulunas_buffer_left: Vec::with_capacity(RNNOISE_FRAME_SIZE * 2),
            #[cfg(feature = "noise-suppression")]
            ulunas_buffer_right: Vec::with_capacity(RNNOISE_FRAME_SIZE * 2),
            #[cfg(feature = "dsp")]
            speex: SpeexDenoiser::new(),
            lightweight: LightweightDenoiser::new(),
            equalizer: EqualizerEffect::new(),
            resampler: ResamplerEffect::new(),
            dereverb: DereverbEffect::new(),
            agc: AgcEffect::new(),
            vad: VadEffect::new(),
            raw_spectrum: vec![0.0; 64],
            processed_spectrum: vec![0.0; 64],
            accum_buffer: Vec::new(),
        }
    }

    /// Process a chunk of f32 PCM audio in-place.
    /// Returns (raw_rms, processed_rms) for level metering.
    pub fn process(&mut self, data: &mut Vec<f32>, channels: usize, queued_ms: f64) -> (f32, f32) {
        if data.is_empty() {
            return (0.0, 0.0);
        }

        let raw_rms = compute_rms(data);
        self.compute_spectrum(data, true);

        // Frame accumulation: align to 480*channels before noise reduction
        self.accum_buffer.extend_from_slice(data);
        let samples_per_frame = RNNOISE_FRAME_SIZE * channels.max(1);
        let frame_count = self.accum_buffer.len() / samples_per_frame;

        if frame_count == 0 {
            data.iter_mut().for_each(|s| *s = 0.0);
            return (raw_rms, 0.0);
        }

        let process_count = frame_count * samples_per_frame;
        let mut to_process: Vec<f32> = self.accum_buffer[..process_count].to_vec();
        self.accum_buffer = self.accum_buffer[process_count..].to_vec();

        let settings = self.settings.read().unwrap().clone();
        self.equalizer.update_filters(&settings.equalizer);

        // Dynamic Processing Chain
        for effect in &settings.processing_chain {
            match effect.as_str() {
                "NoiseReduction" => {
                    if settings.ns_enabled {
                        self.apply_noise_reduction(&mut to_process, channels.max(1), &settings);
                    }
                }
                "Dereverb" => {
                    if settings.dereverb_enabled {
                        self.dereverb.process(&mut to_process, channels.max(1), settings.dereverb_level);
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
                        self.agc.process(&mut to_process, settings.agc_target, attack_rate, decay_rate);
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

        self.resampler.update_playback_ratio(queued_ms);
        let resampled = self.resampler.process(&to_process, channels);
        *data = resampled;

        for sample in data.iter_mut() {
            *sample = sample.clamp(-1.0, 1.0);
        }

        let processed_rms = compute_rms(data);
        self.compute_spectrum(data, false);

        (raw_rms, processed_rms)
    }

    pub fn get_spectrums(&self) -> (Vec<f32>, Vec<f32>) {
        (self.raw_spectrum.clone(), self.processed_spectrum.clone())
    }

    // ── Noise Reduction Dispatcher ─────────────────────────────────────────

    fn apply_noise_reduction(&mut self, data: &mut Vec<f32>, channels: usize, settings: &AudioDspSettings) {
        match settings.ns_type.as_str() {
            #[cfg(feature = "dsp")]
            "RNNoise" => self.rnnoise.process(data, channels, settings.ns_intensity),
            #[cfg(feature = "noise-suppression")]
            "Ulunas" => {
                let intensity = settings.ns_intensity;
                // Lazy init
                if self.ulunas_left.is_none() {
                    if let Some(path) = &self.ulunas_model_path {
                        if path.exists() {
                            match UlunasProcessor::new(path.to_str().unwrap_or("")) {
                                Ok(proc) => {
                                    log::info!("[DSP] Ulunas ONNX model loaded (L): {:?}", path);
                                    self.ulunas_left = Some(proc);
                                }
                                Err(e) => log::error!("[DSP] Failed to load Ulunas model: {}", e),
                            }
                        }
                    }
                }
                if channels >= 2 && self.ulunas_right.is_none() {
                    if let Some(path) = &self.ulunas_model_path {
                        if path.exists() {
                            if let Ok(proc) = UlunasProcessor::new(path.to_str().unwrap_or("")) {
                                log::info!("[DSP] Ulunas ONNX model loaded (R): {:?}", path);
                                self.ulunas_right = Some(proc);
                            }
                        }
                    }
                }
                if channels >= 2 {
                    let frames = data.len() / 2;
                    let mut left: Vec<f32> = Vec::with_capacity(frames);
                    let mut right: Vec<f32> = Vec::with_capacity(frames);
                    for i in 0..frames {
                        left.push(data[i * 2]);
                        right.push(data[i * 2 + 1]);
                    }
                    Self::process_ulunas_single(&mut left, &mut self.ulunas_buffer_left, &mut self.ulunas_left, intensity);
                    Self::process_ulunas_single(&mut right, &mut self.ulunas_buffer_right, &mut self.ulunas_right, intensity);
                    data.clear();
                    for i in 0..frames {
                        data.push(left[i]);
                        data.push(right[i]);
                    }
                } else {
                    Self::process_ulunas_single(data, &mut self.ulunas_buffer_left, &mut self.ulunas_left, intensity);
                }
            }
            #[cfg(feature = "dsp")]
            "Speexdsp" => self.speex.process(data, settings.ns_intensity),
            "Lightweight" => self.lightweight.process(data, settings.ns_intensity),
            _ => {}
        }
    }

    #[cfg(feature = "noise-suppression")]
    fn process_ulunas_single(
        data: &mut Vec<f32>,
        buffer: &mut Vec<f32>,
        ulunas: &mut Option<UlunasProcessor>,
        intensity: f32,
    ) {
        let mix = (intensity / 100.0).clamp(0.0, 1.0);
        let input_len = data.len();
        buffer.extend_from_slice(data);

        let mut output = Vec::with_capacity(input_len);

        while buffer.len() >= RNNOISE_FRAME_SIZE {
            let frame: Vec<f32> = buffer.drain(..RNNOISE_FRAME_SIZE).collect();
            if let Some(proc) = ulunas {
                let processed = proc.process(&frame);
                for i in 0..RNNOISE_FRAME_SIZE {
                    let clean = if i < processed.len() { processed[i] } else { frame[i] };
                    output.push(frame[i] * (1.0 - mix) + clean * mix);
                }
            } else {
                output.extend_from_slice(&frame);
            }
        }

        for sample in buffer.drain(..) {
            output.push(sample);
        }

        output.truncate(input_len);
        while output.len() < input_len {
            output.push(0.0);
        }

        *data = output;
    }

    // ── Spectrum ──────────────────────────────────────────────────────────

    fn compute_spectrum(&mut self, data: &[f32], is_raw: bool) {
        let bands = 64;
        let target = if is_raw { &mut self.raw_spectrum } else { &mut self.processed_spectrum };
        if target.len() != bands {
            target.resize(bands, 0.0);
        }
        if data.is_empty() {
            for v in target.iter_mut() { *v = 0.0; }
            return;
        }

        for (band_idx, band_val) in target.iter_mut().enumerate() {
            let start = (band_idx as f32 / bands as f32).powf(1.5) * data.len() as f32;
            let end = (((band_idx + 1) as f32) / bands as f32).powf(1.5) * data.len() as f32;
            let start = start as usize;
            let end = (end as usize).min(data.len());
            if start >= end {
                *band_val *= 0.85;
                continue;
            }
            let mut sum = 0.0_f32;
            for i in start..end { sum += data[i] * data[i]; }
            let rms = (sum / (end - start) as f32).sqrt();
            let db = if rms > 1e-10 { 20.0 * rms.log10() } else { -100.0 };
            let normalized = ((db + 60.0) / 60.0).clamp(0.0, 1.0);
            if normalized > *band_val {
                *band_val = normalized;
            } else {
                *band_val = *band_val * 0.85 + normalized * 0.15;
            }
        }
    }
}

pub fn compute_rms(data: &[f32]) -> f32 {
    if data.is_empty() { return 0.0; }
    let sum: f32 = data.iter().map(|s| s * s).sum();
    (sum / data.len() as f32).sqrt()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_gain_positive() {
        let settings = Arc::new(RwLock::new(AudioDspSettings {
            gain: 20.0,
            ..Default::default()
        }));
        let mut processor = DspProcessor::new(settings, None);
        let mut data = vec![0.1; 480];
        processor.process(&mut data, 1, 80.0);
        assert!(data[0] > 0.9, "Expected amplified sample, got {}", data[0]);
    }

    #[test]
    fn test_gain_negative() {
        let settings = Arc::new(RwLock::new(AudioDspSettings {
            gain: -20.0,
            ..Default::default()
        }));
        let mut processor = DspProcessor::new(settings, None);
        let mut data = vec![0.5; 480];
        processor.process(&mut data, 1, 80.0);
        assert!(data[0] < 0.1, "Expected attenuated sample, got {}", data[0]);
    }

    #[test]
    fn test_vad_mutes_quiet() {
        let settings = Arc::new(RwLock::new(AudioDspSettings {
            vad_enabled: true,
            vad_threshold: -10.0,
            ..Default::default()
        }));
        let mut processor = DspProcessor::new(settings, None);
        let mut data = vec![0.001; 960];
        for _ in 0..20 { processor.process(&mut data, 1, 80.0); }
        assert!(data[data.len() - 1].abs() < 0.01, "Expected muted, got {}", data[data.len() - 1]);
    }

    #[test]
    fn test_agc_boosts_quiet() {
        let settings = Arc::new(RwLock::new(AudioDspSettings {
            agc_enabled: true,
            agc_target: 16000.0,
            agc_attack: 90.0,
            agc_decay: 10.0,
            ..Default::default()
        }));
        let mut processor = DspProcessor::new(settings, None);
        let mut data: Vec<f32> = vec![0.01; 4800];
        for _ in 0..10 { processor.process(&mut data, 1, 80.0); }
        assert!(data[data.len() - 1].abs() > 0.01, "AGC should have amplified the signal");
    }
}
