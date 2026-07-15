mod lightweight;
mod speex;

#[cfg(feature = "noise-suppression")]
mod purevox;
#[cfg(feature = "dsp")]
mod rnnoise;

use lightweight::LightweightNS;
#[cfg(feature = "dsp")]
use speex::SpeexStyleNS;

#[cfg(feature = "noise-suppression")]
use purevox::PureVoxProcessor;
#[cfg(feature = "dsp")]
use rnnoise::RnnoiseDenoiser;

use std::path::PathBuf;

/// Holds all noise reduction engines and dispatches based on `ns_type`.
pub struct NoiseReducer {
    // ── Lightweight ──────────────────────────────────────────────────────────
    lightweight: LightweightNS,

    // ── RNNoise ─────────────────────────────────────────────────────────────
    #[cfg(feature = "dsp")]
    rnnoise_left: RnnoiseDenoiser,
    #[cfg(feature = "dsp")]
    rnnoise_right: RnnoiseDenoiser,

    // ── Speexdsp ────────────────────────────────────────────────────────────
    #[cfg(feature = "dsp")]
    speex_ns: SpeexStyleNS,

    // ── PureVox (ONNX) ──────────────────────────────────────────────────────
    #[cfg(feature = "noise-suppression")]
    purevox_left: Option<PureVoxProcessor>,
    #[cfg(feature = "noise-suppression")]
    purevox_right: Option<PureVoxProcessor>,

    #[cfg(feature = "noise-suppression")]
    purevox_model_path: Option<PathBuf>,
}

impl NoiseReducer {
    pub fn new(model_dir: Option<PathBuf>) -> Self {
        Self {
            lightweight: LightweightNS::new(),

            #[cfg(feature = "dsp")]
            rnnoise_left: RnnoiseDenoiser::new(),
            #[cfg(feature = "dsp")]
            rnnoise_right: RnnoiseDenoiser::new(),

            #[cfg(feature = "dsp")]
            speex_ns: SpeexStyleNS::new(),

            #[cfg(feature = "noise-suppression")]
            purevox_left: None,
            #[cfg(feature = "noise-suppression")]
            purevox_right: None,
            #[cfg(feature = "noise-suppression")]
            purevox_model_path: model_dir.as_ref().map(|d| d.join("purevox6.onnx")),
        }
    }

    /// Dispatch to the appropriate noise reduction engine based on settings.
    pub fn process(
        &mut self,
        data: &mut Vec<f32>,
        channels: usize,
        settings: &super::AudioDspSettings,
    ) {
        match settings.ns_type.as_str() {
            #[cfg(feature = "dsp")]
            "RNNoise" => self.apply_rnnoise(data, channels, settings.ns_intensity),
            #[cfg(feature = "noise-suppression")]
            "PureVox" => self.apply_purevox(data, channels, settings.ns_intensity),
            #[cfg(feature = "dsp")]
            "Speexdsp" => self.apply_speex(data, channels, settings.ns_intensity),
            "Lightweight" => self.apply_lightweight(data, settings.ns_intensity),
            _ => {}
        }
    }

    // ── Lightweight (Noise Gate) ─────────────────────────────────────────────

    fn apply_lightweight(&mut self, data: &mut Vec<f32>, intensity: f32) {
        self.lightweight.process(data, intensity);
    }

    // ── RNNoise (nnnoiseless) ───────────────────────────────────────────────

    #[cfg(feature = "dsp")]
    fn apply_rnnoise(&mut self, data: &mut Vec<f32>, channels: usize, intensity: f32) {
        if data.is_empty() || channels == 0 {
            return;
        }

        if channels >= 2 {
            let frames = data.len() / 2;
            let mut left: Vec<f32> = Vec::with_capacity(frames);
            let mut right: Vec<f32> = Vec::with_capacity(frames);
            for i in 0..frames {
                left.push(data[i * 2]);
                right.push(data[i * 2 + 1]);
            }

            self.rnnoise_left.process(&mut left, intensity);
            self.rnnoise_right.process(&mut right, intensity);

            data.clear();
            for i in 0..frames {
                data.push(left[i]);
                data.push(right[i]);
            }
        } else {
            self.rnnoise_left.process(data, intensity);
        }
    }

    // ── PureVox (ONNX) ──────────────────────────────────────────────

    #[cfg(feature = "noise-suppression")]
    fn apply_purevox(&mut self, data: &mut Vec<f32>, channels: usize, intensity: f32) {
        // Lazy init for both channels
        if self.purevox_left.is_none() {
            if let Some(path) = &self.purevox_model_path {
                if path.exists() {
                    match PureVoxProcessor::new(path.to_str().unwrap_or("")) {
                        Ok(proc) => {
                            log::info!("[DSP] PureVox ONNX model loaded (L): {:?}", path);
                            self.purevox_left = Some(proc);
                        }
                        Err(e) => {
                            log::error!("[DSP] Failed to load PureVox model: {}", e);
                            #[cfg(feature = "dsp")]
                            {
                                self.apply_rnnoise(data, channels, intensity);
                                return;
                            }
                            #[cfg(not(feature = "dsp"))]
                            return;
                        }
                    }
                } else {
                    log::warn!(
                        "[DSP] PureVox model not found at {:?}, falling back to RNNoise",
                        path
                    );
                    #[cfg(feature = "dsp")]
                    {
                        self.apply_rnnoise(data, channels, intensity);
                        return;
                    }
                    #[cfg(not(feature = "dsp"))]
                    return;
                }
            } else {
                #[cfg(feature = "dsp")]
                {
                    self.apply_rnnoise(data, channels, intensity);
                    return;
                }
                #[cfg(not(feature = "dsp"))]
                return;
            }
        }
        if channels >= 2 && self.purevox_right.is_none() {
            if let Some(path) = &self.purevox_model_path {
                if path.exists() {
                    match PureVoxProcessor::new(path.to_str().unwrap_or("")) {
                        Ok(proc) => {
                            log::info!("[DSP] PureVox ONNX model loaded (R): {:?}", path);
                            self.purevox_right = Some(proc);
                        }
                        Err(e) => {
                            log::error!("[DSP] Failed to load PureVox model for R channel: {}", e);
                        }
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

            Self::process_purevox_single_channel(&mut left, &mut self.purevox_left, intensity);
            Self::process_purevox_single_channel(&mut right, &mut self.purevox_right, intensity);

            data.clear();
            for i in 0..frames {
                data.push(left[i]);
                data.push(right[i]);
            }
        } else {
            Self::process_purevox_single_channel(data, &mut self.purevox_left, intensity);
        }
    }

    #[cfg(feature = "noise-suppression")]
    fn process_purevox_single_channel(
        data: &mut Vec<f32>,
        purevox: &mut Option<PureVoxProcessor>,
        intensity: f32,
    ) {
        let mix = (intensity / 100.0).clamp(0.0, 1.0);

        if let Some(proc) = purevox {
            let mut output = Vec::with_capacity(data.len());
            for chunk in data.chunks(480) {
                let clean = proc.process(chunk);
                for i in 0..chunk.len() {
                    let clean_sample = if i < clean.len() { clean[i] } else { chunk[i] };
                    output.push(chunk[i] * (1.0 - mix) + clean_sample * mix);
                }
            }
            data.copy_from_slice(&output);
        }
    }

    // ── Speexdsp ──────────────────────────────────────────────

    #[cfg(feature = "dsp")]
    fn apply_speex(&mut self, data: &mut Vec<f32>, channels: usize, intensity: f32) {
        let input_len = data.len();

        // Stereo: separate channels, process each independently, re-interleave
        if channels >= 2 && input_len >= 2 {
            let frames = input_len / 2;
            let mut left: Vec<f32> = Vec::with_capacity(frames);
            let mut right: Vec<f32> = Vec::with_capacity(frames);
            for i in 0..frames {
                left.push(data[i * 2]);
                right.push(data[i * 2 + 1]);
            }

            self.speex_ns.process(&mut left, intensity);
            self.speex_ns.process(&mut right, intensity);

            // Re-interleave
            data.clear();
            for i in 0..frames {
                data.push(left[i]);
                data.push(right[i]);
            }
        } else {
            // Mono
            self.speex_ns.process(data, intensity);
        }
    }
}
