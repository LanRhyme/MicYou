use std::path::PathBuf;
use std::sync::{Arc, RwLock};

#[cfg(feature = "dsp")]
use nnnoiseless::DenoiseState;

#[derive(Debug, Clone, serde::Deserialize, serde::Serialize)]
#[serde(rename_all = "camelCase")]
pub struct EqualizerConfig {
    pub enabled: bool,
    pub pre_amp: f32,
    pub gains: Vec<f32>, // 10 bands
}

impl Default for EqualizerConfig {
    fn default() -> Self {
        Self {
            enabled: false,
            pre_amp: 0.0,
            gains: vec![0.0; 10],
        }
    }
}

/// Audio DSP settings, synced from the frontend.
#[derive(Debug, Clone, serde::Deserialize, serde::Serialize)]
#[serde(rename_all = "camelCase")]
pub struct AudioDspSettings {
    pub gain: f32, // dB, -50 to +50
    pub ns_enabled: bool,
    pub ns_type: String,   // "PureVox", "RNNoise", "Ulunas", "Speexdsp"
    pub ns_intensity: f32, // 0..100
    pub dereverb_enabled: bool,
    pub dereverb_level: f32, // 0..100
    pub agc_enabled: bool,
    pub agc_target: f32, // 0..32767
    pub agc_attack: f32, // raw slider value 1..100, maps to 0.001..0.1
    pub agc_decay: f32,  // raw slider value 1..100, maps to 0.0001..0.01
    pub vad_enabled: bool,
    pub vad_threshold: f32, // dB, -100..0

    #[serde(default)]
    pub processing_chain: Vec<String>,
    #[serde(default)]
    pub equalizer: EqualizerConfig,
}

impl Default for AudioDspSettings {
    fn default() -> Self {
        Self {
            gain: 0.0,
            ns_enabled: false,
            ns_type: "PureVox".to_string(),
            ns_intensity: 50.0,
            dereverb_enabled: false,
            dereverb_level: 50.0,
            agc_enabled: false,
            agc_target: 16000.0,
            agc_attack: 50.0,
            agc_decay: 50.0,
            vad_enabled: false,
            vad_threshold: -40.0,
            processing_chain: vec![
                "NoiseReduction".to_string(),
                "Dereverb".to_string(),
                "Equalizer".to_string(),
                "Amplifier".to_string(),
                "AGC".to_string(),
                "VAD".to_string(),
            ],
            equalizer: EqualizerConfig::default(),
        }
    }
}

// ─── Ulunas ONNX Processor ─────────────────────────────────────────────────

/// Port of UlunasProcessor.kt - ONNX Runtime AI denoiser
#[cfg(feature = "noise-suppression")]
struct UlunasProcessor {
    session: ort::session::Session,
    frame_size: usize, // 960
    hop_length: usize, // 480
    window: Vec<f32>,
    ola_gain: f32,
    previous: Vec<f32>,
    ola_accumulator: Vec<f32>,
    state_data: Vec<Vec<f32>>,
    state_shapes: Vec<Vec<usize>>,
    fft_forward: std::sync::Arc<dyn rustfft::Fft<f32>>,
    fft_inverse: std::sync::Arc<dyn rustfft::Fft<f32>>,
}

#[cfg(feature = "noise-suppression")]
impl UlunasProcessor {
    fn new(model_path: &str) -> Result<Self, Box<dyn std::error::Error>> {
        let frame_size = 960;
        let hop_length = 480;

        let session = ort::session::Session::builder()?
            .with_intra_threads(1)?
            .with_inter_threads(1)?
            .commit_from_file(model_path)?;

        let window = Self::hanning_window(frame_size);
        let ola_gain = Self::calc_ola_gain(&window, hop_length);

        use rustfft::FftPlanner;
        let mut planner = FftPlanner::new();
        let fft_forward = planner.plan_fft_forward(frame_size);
        let fft_inverse = planner.plan_fft_inverse(frame_size);

        // State shapes
        let state_shapes: Vec<Vec<usize>> = vec![
            vec![1, 1, 2, 121],
            vec![1, 24, 1, 61],
            vec![1, 24, 1, 31],
            vec![1, 1, 24],
            vec![1, 1, 48],
            vec![1, 1, 48],
            vec![1, 1, 64],
            vec![1, 1, 32],
            vec![1, 31, 16],
            vec![1, 31, 16],
            vec![1, 24, 1, 31],
            vec![1, 12, 1, 31],
            vec![1, 12, 2, 61],
            vec![1, 1, 64],
            vec![1, 1, 48],
            vec![1, 1, 48],
            vec![1, 1, 24],
            vec![1, 1, 2],
        ];

        let state_data: Vec<Vec<f32>> = state_shapes
            .iter()
            .map(|shape| vec![0.0f32; shape.iter().product()])
            .collect();

        Ok(Self {
            session,
            frame_size,
            hop_length,
            window,
            ola_gain,
            previous: vec![0.0; hop_length],
            ola_accumulator: vec![0.0; frame_size],
            state_data,
            state_shapes,
            fft_forward,
            fft_inverse,
        })
    }

    fn hanning_window(size: usize) -> Vec<f32> {
        (0..size)
            .map(|i| {
                let v =
                    0.5 - 0.5 * (2.0 * std::f64::consts::PI * i as f64 / (size - 1) as f64).cos();
                v.sqrt() as f32
            })
            .collect()
    }

    fn calc_ola_gain(window: &[f32], hop_length: usize) -> f32 {
        let mut sum_sq = 0.0_f32;
        for i in 0..hop_length {
            let w1 = window[i];
            let w2 = window[i + hop_length];
            sum_sq += w1 * w1 + w2 * w2;
        }
        let avg = sum_sq / hop_length as f32;
        if avg > 0.001 {
            1.0 / avg.sqrt()
        } else {
            1.0
        }
    }

    fn process(&mut self, input: &[f32]) -> Vec<f32> {
        if input.len() != self.hop_length {
            return input.to_vec();
        }

        let frame_size = self.frame_size;
        let hop_length = self.hop_length;
        let spec_size = frame_size / 2 + 1;

        // Build frame: previous + current
        let mut fft_buffer = vec![0.0_f32; frame_size];
        fft_buffer[..hop_length].copy_from_slice(&self.previous);
        fft_buffer[hop_length..].copy_from_slice(input);
        self.previous.copy_from_slice(input);

        // Apply window
        for i in 0..frame_size {
            fft_buffer[i] *= self.window[i];
        }

        // FFT using cached planner
        use rustfft::num_complex::Complex;

        let mut complex_buf: Vec<Complex<f32>> =
            fft_buffer.iter().map(|&v| Complex::new(v, 0.0)).collect();
        self.fft_forward.process(&mut complex_buf);

        // Convert to model input format: flat vec for [1, spec_size, 1, 2]
        let mut spec_flat = vec![0.0f32; spec_size * 2];
        for i in 0..spec_size {
            spec_flat[i * 2] = complex_buf[i].re;
            spec_flat[i * 2 + 1] = complex_buf[i].im;
        }

        // Convert to ort Values using (shape, data) tuple
        let spec_shape = vec![1, spec_size, 1, 2];
        let val_spec = ort::value::Value::from_array((spec_shape, spec_flat)).unwrap();

        let val_states: Vec<_> = self
            .state_data
            .iter()
            .zip(self.state_shapes.iter())
            .map(|(data, shape)| {
                ort::value::Value::from_array((shape.clone(), data.clone())).unwrap()
            })
            .collect();

        // Run inference
        let outputs = match self.session.run(ort::inputs![
            &val_spec,
            &val_states[0],
            &val_states[1],
            &val_states[2],
            &val_states[3],
            &val_states[4],
            &val_states[5],
            &val_states[6],
            &val_states[7],
            &val_states[8],
            &val_states[9],
            &val_states[10],
            &val_states[11],
            &val_states[12],
            &val_states[13],
            &val_states[14],
            &val_states[15],
            &val_states[16],
            &val_states[17]
        ]) {
            Ok(o) => o,
            Err(e) => {
                eprintln!("ONNX inference failed: {}", e);
                return input.to_vec();
            }
        };

        // Extract output spectrum
        if let Ok(output_tensor) = outputs[0].try_extract_tensor::<f32>() {
            let output_data = output_tensor.1; // (Shape, slice)
            for i in 0..spec_size {
                complex_buf[i] = Complex::new(output_data[i * 2], output_data[i * 2 + 1]);
            }
            for i in spec_size..frame_size {
                complex_buf[i] = complex_buf[frame_size - i].conj();
            }
        }

        // Update states from outputs 1..18
        for i in 1..outputs.len().min(19) {
            if let Ok(state_tensor) = outputs[i].try_extract_tensor::<f32>() {
                let state_data = state_tensor.1;
                if i - 1 < self.state_data.len() && state_data.len() == self.state_data[i - 1].len()
                {
                    self.state_data[i - 1].copy_from_slice(state_data);
                }
            }
        }

        // IFFT
        self.fft_inverse.process(&mut complex_buf);
        let scale = 1.0 / frame_size as f32;
        for i in 0..frame_size {
            fft_buffer[i] = complex_buf[i].re * scale * self.window[i];
        }

        // OLA
        for i in 0..frame_size {
            self.ola_accumulator[i] += fft_buffer[i];
        }

        let mut output = vec![0.0_f32; hop_length];
        for i in 0..hop_length {
            output[i] = self.ola_accumulator[i] * self.ola_gain;
        }

        // Shift accumulator
        for i in 0..frame_size - hop_length {
            self.ola_accumulator[i] = self.ola_accumulator[i + hop_length];
        }
        for i in frame_size - hop_length..frame_size {
            self.ola_accumulator[i] = 0.0;
        }

        output
    }
}

// ─── PureVox6 ONNX noise suppression ────────────────────────────────────

#[cfg(feature = "noise-suppression")]
struct PureVoxProcessor {
    session: ort::session::Session,
    frame_size: usize, // 960
    hop_length: usize, // 480
    window: Vec<f32>,
    ola_gain: f32,
    previous: Vec<f32>,
    ola_accumulator: Vec<f32>,
    // PureVox6 has 4 independent cache states (flat 1D)
    enc_c: Vec<f32>,   // [7368]
    dec_c: Vec<f32>,   // [1440]
    tfa_c: Vec<f32>,   // [800]
    inter_c: Vec<f32>, // [4608]
    fft_forward: std::sync::Arc<dyn rustfft::Fft<f32>>,
    fft_inverse: std::sync::Arc<dyn rustfft::Fft<f32>>,
}

#[cfg(feature = "noise-suppression")]
impl PureVoxProcessor {
    fn new(model_path: &str) -> Result<Self, Box<dyn std::error::Error>> {
        let frame_size = 960;
        let hop_length = 480;

        let session = ort::session::Session::builder()?
            .with_intra_threads(1)?
            .with_inter_threads(1)?
            .commit_from_file(model_path)?;

        let window = UlunasProcessor::hanning_window(frame_size);
        let ola_gain = UlunasProcessor::calc_ola_gain(&window, hop_length);

        use rustfft::FftPlanner;
        let mut planner = FftPlanner::new();
        let fft_forward = planner.plan_fft_forward(frame_size);
        let fft_inverse = planner.plan_fft_inverse(frame_size);

        let enc_c = vec![0.0f32; 7368];
        let dec_c = vec![0.0f32; 1440];
        let tfa_c = vec![0.0f32; 800];
        let inter_c = vec![0.0f32; 4608];

        Ok(Self {
            session,
            frame_size,
            hop_length,
            window,
            ola_gain,
            previous: vec![0.0; hop_length],
            ola_accumulator: vec![0.0; frame_size],
            enc_c,
            dec_c,
            tfa_c,
            inter_c,
            fft_forward,
            fft_inverse,
        })
    }

    fn process(&mut self, input: &[f32]) -> Vec<f32> {
        if input.len() != self.hop_length {
            return input.to_vec();
        }

        let frame_size = self.frame_size;
        let hop_length = self.hop_length;
        let spec_size = frame_size / 2 + 1;

        // Build frame: previous + current
        let mut fft_buffer = vec![0.0_f32; frame_size];
        fft_buffer[..hop_length].copy_from_slice(&self.previous);
        fft_buffer[hop_length..].copy_from_slice(input);
        self.previous.copy_from_slice(input);

        // Apply window
        for i in 0..frame_size {
            fft_buffer[i] *= self.window[i];
        }

        // FFT
        use rustfft::num_complex::Complex;
        let mut complex_buf: Vec<Complex<f32>> =
            fft_buffer.iter().map(|&v| Complex::new(v, 0.0)).collect();
        self.fft_forward.process(&mut complex_buf);

        // Convert to model input format: flat vec for [1, spec_size, 1, 2]
        let spec_size_2 = spec_size * 2;
        let mut spec_flat = vec![0.0f32; spec_size_2];
        for i in 0..spec_size {
            spec_flat[i * 2] = complex_buf[i].re;
            spec_flat[i * 2 + 1] = complex_buf[i].im;
        }

        // Create ORT values for all 5 inputs
        let val_spec =
            ort::value::Value::from_array((vec![1, spec_size, 1, 2], spec_flat)).unwrap();
        let val_enc_c = ort::value::Value::from_array((vec![1, 7368], self.enc_c.clone())).unwrap();
        let val_dec_c = ort::value::Value::from_array((vec![1, 1440], self.dec_c.clone())).unwrap();
        let val_tfa_c = ort::value::Value::from_array((vec![1, 800], self.tfa_c.clone())).unwrap();
        let val_inter_c =
            ort::value::Value::from_array((vec![1, 4608], self.inter_c.clone())).unwrap();

        // Run inference
        let outputs = match self.session.run(ort::inputs![
            &val_spec,
            &val_enc_c,
            &val_dec_c,
            &val_tfa_c,
            &val_inter_c,
        ]) {
            Ok(o) => o,
            Err(e) => {
                eprintln!("PureVox ONNX inference failed: {}", e);
                return input.to_vec();
            }
        };

        // Extract output spectrum (output[0] = enhanced_spec)
        if let Ok(output_tensor) = outputs[0].try_extract_tensor::<f32>() {
            let output_data = output_tensor.1;
            for i in 0..spec_size {
                complex_buf[i] = Complex::new(output_data[i * 2], output_data[i * 2 + 1]);
            }
            for i in spec_size..frame_size {
                complex_buf[i] = complex_buf[frame_size - i].conj();
            }
        }

        // Update state caches from outputs 1..4
        if outputs.len() >= 5 {
            if let Ok(enc) = outputs[1].try_extract_tensor::<f32>() {
                if enc.1.len() == self.enc_c.len() {
                    self.enc_c.copy_from_slice(enc.1);
                }
            }
            if let Ok(dec) = outputs[2].try_extract_tensor::<f32>() {
                if dec.1.len() == self.dec_c.len() {
                    self.dec_c.copy_from_slice(dec.1);
                }
            }
            if let Ok(tfa) = outputs[3].try_extract_tensor::<f32>() {
                if tfa.1.len() == self.tfa_c.len() {
                    self.tfa_c.copy_from_slice(tfa.1);
                }
            }
            if let Ok(inter) = outputs[4].try_extract_tensor::<f32>() {
                if inter.1.len() == self.inter_c.len() {
                    self.inter_c.copy_from_slice(inter.1);
                }
            }
        }

        // IFFT
        self.fft_inverse.process(&mut complex_buf);
        let scale = 1.0 / frame_size as f32;
        for i in 0..frame_size {
            fft_buffer[i] = complex_buf[i].re * scale * self.window[i];
        }

        // OLA
        for i in 0..frame_size {
            self.ola_accumulator[i] += fft_buffer[i];
        }

        let mut output = vec![0.0_f32; hop_length];
        for i in 0..hop_length {
            output[i] = self.ola_accumulator[i] * self.ola_gain;
        }

        // Shift accumulator
        for i in 0..frame_size - hop_length {
            self.ola_accumulator[i] = self.ola_accumulator[i + hop_length];
        }
        for i in frame_size - hop_length..frame_size {
            self.ola_accumulator[i] = 0.0;
        }

        output
    }
}

// ─── Speexdsp-style spectral subtraction noise suppression ──────────────────

/// A simple spectral subtraction noise suppressor inspired by Speex's approach.
/// Uses FFT to estimate noise floor and subtract it from the signal.
#[cfg(feature = "dsp")]
struct SpeexStyleNS {
    frame_size: usize,
    noise_estimate: Vec<f32>, // Running noise floor estimate per frequency bin
    adaptation_rate: f32,
    fft_forward: std::sync::Arc<dyn rustfft::Fft<f32>>,
    fft_inverse: std::sync::Arc<dyn rustfft::Fft<f32>>,
}

#[cfg(feature = "dsp")]
impl SpeexStyleNS {
    fn new() -> Self {
        use rustfft::FftPlanner;
        let frame_size = 480;
        let mut planner = FftPlanner::new();
        let fft_forward = planner.plan_fft_forward(frame_size);
        let fft_inverse = planner.plan_fft_inverse(frame_size);
        Self {
            frame_size,
            noise_estimate: vec![0.0; frame_size / 2 + 1],
            adaptation_rate: 0.02,
            fft_forward,
            fft_inverse,
        }
    }

    fn process(&mut self, data: &mut [f32], intensity: f32) {
        use rustfft::num_complex::Complex;

        let len = data.len();
        if len < self.frame_size {
            return;
        }

        let num_frames = len / self.frame_size;
        let mix = (intensity / 100.0).clamp(0.0, 1.0);
        let spec_size = self.frame_size / 2 + 1;

        for frame_idx in 0..num_frames {
            let offset = frame_idx * self.frame_size;
            let frame = &data[offset..offset + self.frame_size];

            let mut complex: Vec<Complex<f32>> =
                frame.iter().map(|&s| Complex::new(s, 0.0)).collect();

            self.fft_forward.process(&mut complex);

            // Compute magnitude and update noise estimate
            for i in 0..spec_size {
                let mag = complex[i].norm();
                self.noise_estimate[i] = self.noise_estimate[i] * (1.0 - self.adaptation_rate)
                    + mag * self.adaptation_rate;
            }

            // Spectral subtraction
            for i in 0..spec_size {
                let mag = complex[i].norm();
                let phase = complex[i].arg();
                let noise = self.noise_estimate[i] * mix * 2.0;
                let clean_mag = (mag - noise).max(mag * 0.05);
                complex[i] = Complex::from_polar(clean_mag, phase);
                if i > 0 && i < self.frame_size - i {
                    complex[self.frame_size - i] = complex[i].conj();
                }
            }

            self.fft_inverse.process(&mut complex);

            let scale = 1.0 / self.frame_size as f32;
            for i in 0..self.frame_size {
                data[offset + i] = complex[i].re * scale;
            }
        }
    }
}

// ─── Equalizer (10-band Biquad Peaking EQ) ──────────────────────────────────

struct BiquadFilter {
    a0: f64,
    a1: f64,
    a2: f64,
    b1: f64,
    b2: f64,
    x1: f64,
    x2: f64,
    y1: f64,
    y2: f64,
}

impl BiquadFilter {
    fn new() -> Self {
        Self {
            a0: 1.0,
            a1: 0.0,
            a2: 0.0,
            b1: 0.0,
            b2: 0.0,
            x1: 0.0,
            x2: 0.0,
            y1: 0.0,
            y2: 0.0,
        }
    }

    fn set_peaking_eq(&mut self, sample_rate: f64, center_freq: f64, q: f64, db_gain: f64) {
        let w0 = 2.0 * std::f64::consts::PI * center_freq / sample_rate;
        let alpha = w0.sin() / (2.0 * q);
        let a = 10.0_f64.powf(db_gain / 40.0);

        let b0_raw = 1.0 + alpha * a;
        let b1_raw = -2.0 * w0.cos();
        let b2_raw = 1.0 - alpha * a;
        let a0_raw = 1.0 + alpha / a;
        let a1_raw = -2.0 * w0.cos();
        let a2_raw = 1.0 - alpha / a;

        self.a0 = b0_raw / a0_raw;
        self.a1 = b1_raw / a0_raw;
        self.a2 = b2_raw / a0_raw;
        self.b1 = a1_raw / a0_raw;
        self.b2 = a2_raw / a0_raw;
    }

    fn process(&mut self, x: f64) -> f64 {
        let y = self.a0 * x + self.a1 * self.x1 + self.a2 * self.x2
            - self.b1 * self.y1
            - self.b2 * self.y2;
        self.x2 = self.x1;
        self.x1 = x;
        self.y2 = self.y1;
        self.y1 = y;
        y
    }
}

struct EqualizerEffect {
    filters_ch1: Vec<BiquadFilter>,
    filters_ch2: Vec<BiquadFilter>,
    pre_amp_gain: f32,
    frequencies: [f64; 10],
}

impl EqualizerEffect {
    fn new() -> Self {
        let mut eq = Self {
            filters_ch1: (0..10).map(|_| BiquadFilter::new()).collect(),
            filters_ch2: (0..10).map(|_| BiquadFilter::new()).collect(),
            pre_amp_gain: 1.0,
            frequencies: [
                31.25, 62.5, 125.0, 250.0, 500.0, 1000.0, 2000.0, 4000.0, 8000.0, 16000.0,
            ],
        };
        eq.update_filters(&EqualizerConfig::default());
        eq
    }

    fn update_filters(&mut self, config: &EqualizerConfig) {
        self.pre_amp_gain = 10.0_f32.powf(config.pre_amp / 20.0);
        let sample_rate = 48000.0;
        for i in 0..10 {
            let gain = if i < config.gains.len() {
                config.gains[i] as f64
            } else {
                0.0
            };
            self.filters_ch1[i].set_peaking_eq(sample_rate, self.frequencies[i], 1.0, gain);
            self.filters_ch2[i].set_peaking_eq(sample_rate, self.frequencies[i], 1.0, gain);
        }
    }

    fn process(&mut self, data: &mut [f32], channels: usize) {
        if channels == 1 {
            for sample in data.iter_mut() {
                let mut s = (*sample * self.pre_amp_gain) as f64;
                for i in 0..10 {
                    s = self.filters_ch1[i].process(s);
                }
                *sample = s as f32;
            }
        } else if channels == 2 {
            for (i, sample) in data.iter_mut().enumerate() {
                let mut s = (*sample * self.pre_amp_gain) as f64;
                if i % 2 == 0 {
                    for j in 0..10 {
                        s = self.filters_ch1[j].process(s);
                    }
                } else {
                    for j in 0..10 {
                        s = self.filters_ch2[j].process(s);
                    }
                }
                *sample = s as f32;
            }
        }
    }
}

// ─── Buffer Level Manager (replaces adaptive resampler) ─────────────────────

/// Manages playback timing by monitoring the output buffer level.
/// Instead of continuous resampling (which introduces interpolation artifacts),
/// this uses simple sample duplication/dropping only when the buffer drifts
/// significantly from the target level.
struct BufferLevelManager {
    underrun_count: u32,
    overrun_count: u32,
}

impl BufferLevelManager {
    fn new() -> Self {
        Self {
            underrun_count: 0,
            overrun_count: 0,
        }
    }

    fn process(&mut self, input: &[f32], channels: usize, queued_ms: f64, output: &mut Vec<f32>) {
        output.clear();
        if channels == 0 || input.is_empty() {
            output.extend_from_slice(input);
            return;
        }

        let frames = input.len() / channels;

        // Buffer critically low (< 15ms): duplicate last frame to prevent underrun
        if queued_ms < 15.0 {
            self.underrun_count += 1;
            self.overrun_count = 0;
            // Duplicate the last frame
            if frames >= 1 && self.underrun_count <= 3 {
                output.reserve(input.len() + channels);
                output.extend_from_slice(input);
                let last_frame_start = (frames - 1) * channels;
                for c in 0..channels {
                    output.push(input[last_frame_start + c]);
                }
                return;
            }
            output.extend_from_slice(input);
            return;
        }

        // Buffer critically high (> 300ms): drop first frame to prevent overflow
        if queued_ms > 300.0 {
            self.overrun_count += 1;
            self.underrun_count = 0;
            if frames > 2 && self.overrun_count <= 3 {
                output.extend_from_slice(&input[channels..]);
                return;
            }
            output.extend_from_slice(input);
            return;
        }

        // Normal range: pass through unchanged
        self.underrun_count = 0;
        self.overrun_count = 0;
        output.extend_from_slice(input);
    }
}

// ─── Main DSP Processor ─────────────────────────────────────────────────────

/// The main DSP processor. Operates on f32 PCM samples at 48kHz.
pub struct DspProcessor {
    settings: Arc<RwLock<AudioDspSettings>>,
    // RNNoise states - separate per channel to avoid RNN state cross-contamination
    #[cfg(feature = "dsp")]
    denoiser_left: Box<DenoiseState<'static>>,
    #[cfg(feature = "dsp")]
    denoiser_right: Box<DenoiseState<'static>>,
    #[cfg(feature = "dsp")]
    ns_buffer_left: Vec<f32>,
    #[cfg(feature = "dsp")]
    ns_buffer_right: Vec<f32>,
    // Ulunas ONNX processor - separate per channel
    #[cfg(feature = "noise-suppression")]
    ulunas_left: Option<UlunasProcessor>,
    #[cfg(feature = "noise-suppression")]
    ulunas_right: Option<UlunasProcessor>,
    // PureVox ONNX processor - separate per channel
    #[cfg(feature = "noise-suppression")]
    purevox_left: Option<PureVoxProcessor>,
    #[cfg(feature = "noise-suppression")]
    purevox_right: Option<PureVoxProcessor>,

    #[cfg(feature = "noise-suppression")]
    ulunas_model_path: Option<PathBuf>,

    #[cfg(feature = "noise-suppression")]
    purevox_model_path: Option<PathBuf>,

    // Speexdsp-style NS
    #[cfg(feature = "dsp")]
    speex_ns: SpeexStyleNS,
    // Equalizer
    equalizer: EqualizerEffect,
    // Adaptive Resampler
    // Buffer level manager (replaces adaptive resampler)
    buffer_manager: BufferLevelManager,
    // Dereverb state
    dereverb_buffer_left: Vec<f32>,
    dereverb_buffer_right: Vec<f32>,
    dereverb_index: usize,
    // AGC envelope follower
    agc_envelope: f32,
    // AGC smoothed gain (avoids sudden gain jumps causing pops)
    agc_smoothed_gain: f32,
    // VAD fade state (0.0 = muted, 1.0 = full)
    vad_fade: f32,
    // Spectrum snapshots
    raw_spectrum: Vec<f32>,
    processed_spectrum: Vec<f32>,
    // Frame accumulation buffer (align to 480-sample frames for noise reduction)
    accum_buffer: Vec<f32>,
    output_buffer: Vec<f32>,
    to_process_buf: Vec<f32>,
}

const RNNOISE_FRAME_SIZE: usize = 480;

impl DspProcessor {
    pub fn new(settings: Arc<RwLock<AudioDspSettings>>, _model_dir: Option<PathBuf>) -> Self {
        Self {
            settings: settings.clone(),
            #[cfg(feature = "noise-suppression")]
            ulunas_model_path: _model_dir.as_ref().map(|d| d.join("ulunas.onnx")),

            #[cfg(feature = "noise-suppression")]
            purevox_model_path: _model_dir.as_ref().map(|d| d.join("purevox6.onnx")),
            #[cfg(feature = "dsp")]
            denoiser_left: DenoiseState::new(),
            #[cfg(feature = "dsp")]
            denoiser_right: DenoiseState::new(),
            #[cfg(feature = "dsp")]
            ns_buffer_left: Vec::with_capacity(RNNOISE_FRAME_SIZE * 2),
            #[cfg(feature = "dsp")]
            ns_buffer_right: Vec::with_capacity(RNNOISE_FRAME_SIZE * 2),
            #[cfg(feature = "noise-suppression")]
            ulunas_left: None,
            #[cfg(feature = "noise-suppression")]
            ulunas_right: None,
            #[cfg(feature = "noise-suppression")]
            purevox_left: None,
            #[cfg(feature = "noise-suppression")]
            purevox_right: None,

            #[cfg(feature = "dsp")]
            speex_ns: SpeexStyleNS::new(),
            equalizer: EqualizerEffect::new(),
            buffer_manager: BufferLevelManager::new(),
            dereverb_buffer_left: vec![0.0; 480],
            dereverb_buffer_right: vec![0.0; 480],
            dereverb_index: 0,
            agc_envelope: 0.0,
            agc_smoothed_gain: 1.0,
            vad_fade: 1.0,
            raw_spectrum: vec![0.0; 64],
            processed_spectrum: vec![0.0; 64],
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
        self.compute_spectrum(data, true);

        // Frame accumulation: align to 480*channels samples before processing,
        // matching KMP's AudioProcessorPipeline behavior.
        // Noise reduction (RNNoise/Ulunas) requires exactly 480-sample frames.
        // Processing variable-size chunks through AGC/EQ causes artifacts.
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
                        self.apply_noise_reduction(&mut to_process, channels.max(1), &settings);
                    }
                }
                "Dereverb" => {
                    if settings.dereverb_enabled {
                        self.apply_dereverb(
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
                        self.apply_agc(
                            &mut to_process,
                            settings.agc_target,
                            attack_rate,
                            decay_rate,
                        );
                    }
                }
                "VAD" => {
                    if settings.vad_enabled {
                        self.apply_vad(&mut to_process, settings.vad_threshold);
                    }
                }
                _ => {}
            }
        }

        self.buffer_manager
            .process(&to_process, channels, queued_ms, &mut self.output_buffer);

        // Soft clip — avoids harsh hard-clipping artifacts (crackling/pops)
        for sample in self.output_buffer.iter_mut() {
            *sample = soft_clip(*sample);
        }

        self.to_process_buf = to_process;

        data.clear();
        data.extend_from_slice(&self.output_buffer);

        let processed_rms = compute_rms(data);
        self.compute_spectrum(data, false);

        (raw_rms, processed_rms)
    }

    pub fn get_spectrums(&self) -> (Vec<f32>, Vec<f32>) {
        (self.raw_spectrum.clone(), self.processed_spectrum.clone())
    }

    // ── Noise Reduction Dispatcher ──────────────────────────────────────────

    fn apply_noise_reduction(
        &mut self,
        data: &mut Vec<f32>,
        channels: usize,
        settings: &AudioDspSettings,
    ) {
        match settings.ns_type.as_str() {
            #[cfg(feature = "dsp")]
            "RNNoise" => self.apply_rnnoise(data, channels, settings.ns_intensity),
            #[cfg(feature = "noise-suppression")]
            "Ulunas" => self.apply_ulunas(data, channels, settings.ns_intensity),
            #[cfg(feature = "noise-suppression")]
            "PureVox" => self.apply_purevox(data, channels, settings.ns_intensity),
            #[cfg(feature = "dsp")]
            "Speexdsp" => self.apply_speex(data, channels, settings.ns_intensity),
            _ => {}
        }
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

            // Process each channel with its own denoiser (no RNN state cross-contamination)
            Self::process_rnnoise_single_channel(
                &mut left,
                &mut self.ns_buffer_left,
                &mut self.denoiser_left,
                intensity,
            );
            Self::process_rnnoise_single_channel(
                &mut right,
                &mut self.ns_buffer_right,
                &mut self.denoiser_right,
                intensity,
            );

            data.clear();
            for i in 0..frames {
                data.push(left[i]);
                data.push(right[i]);
            }
        } else {
            Self::process_rnnoise_single_channel(
                data,
                &mut self.ns_buffer_left,
                &mut self.denoiser_left,
                intensity,
            );
        }
    }

    #[cfg(feature = "dsp")]
    fn process_rnnoise_single_channel(
        data: &mut Vec<f32>,
        ns_buffer: &mut Vec<f32>,
        denoiser: &mut DenoiseState<'static>,
        intensity: f32,
    ) {
        let mix = (intensity / 100.0).clamp(0.0, 1.0);
        let input_len = data.len();
        ns_buffer.extend_from_slice(data);

        let mut output = Vec::with_capacity(input_len);

        while ns_buffer.len() >= RNNOISE_FRAME_SIZE {
            let frame: Vec<f32> = ns_buffer.drain(..RNNOISE_FRAME_SIZE).collect();

            let input_frame: Vec<f32> = frame.iter().map(|s| s * 32767.0).collect();
            let mut output_frame = vec![0.0f32; RNNOISE_FRAME_SIZE];

            let _vad_prob = denoiser.process_frame(&mut output_frame, &input_frame);

            for i in 0..RNNOISE_FRAME_SIZE {
                let clean = output_frame[i] / 32767.0;
                let original = frame[i];
                output.push(original * (1.0 - mix) + clean * mix);
            }
        }

        for sample in ns_buffer.drain(..) {
            output.push(sample);
        }

        output.truncate(input_len);
        while output.len() < input_len {
            output.push(0.0);
        }

        *data = output;
    }

    // ── Ulunas (ONNX) ──────────────────────────────────────────────────────

    #[cfg(feature = "noise-suppression")]
    fn apply_ulunas(&mut self, data: &mut Vec<f32>, channels: usize, intensity: f32) {
        // Lazy init for both channels
        if self.ulunas_left.is_none() {
            if let Some(path) = &self.ulunas_model_path {
                if path.exists() {
                    match UlunasProcessor::new(path.to_str().unwrap_or("")) {
                        Ok(proc) => {
                            log::info!("[DSP] Ulunas ONNX model loaded (L): {:?}", path);
                            self.ulunas_left = Some(proc);
                        }
                        Err(e) => {
                            log::error!("[DSP] Failed to load Ulunas model: {}", e);
                            self.apply_rnnoise(data, channels, intensity);
                            return;
                        }
                    }
                } else {
                    log::warn!(
                        "[DSP] Ulunas model not found at {:?}, falling back to RNNoise",
                        path
                    );
                    self.apply_rnnoise(data, channels, intensity);
                    return;
                }
            } else {
                self.apply_rnnoise(data, channels, intensity);
                return;
            }
        }
        if channels >= 2 && self.ulunas_right.is_none() {
            if let Some(path) = &self.ulunas_model_path {
                if path.exists() {
                    match UlunasProcessor::new(path.to_str().unwrap_or("")) {
                        Ok(proc) => {
                            log::info!("[DSP] Ulunas ONNX model loaded (R): {:?}", path);
                            self.ulunas_right = Some(proc);
                        }
                        Err(e) => {
                            log::error!("[DSP] Failed to load Ulunas model for R channel: {}", e);
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

            Self::process_ulunas_single_channel(&mut left, &mut self.ulunas_left, intensity);
            Self::process_ulunas_single_channel(&mut right, &mut self.ulunas_right, intensity);

            data.clear();
            for i in 0..frames {
                data.push(left[i]);
                data.push(right[i]);
            }
        } else {
            Self::process_ulunas_single_channel(data, &mut self.ulunas_left, intensity);
        }
    }

    #[cfg(feature = "noise-suppression")]
    fn process_ulunas_single_channel(
        data: &mut Vec<f32>,
        ulunas: &mut Option<UlunasProcessor>,
        intensity: f32,
    ) {
        let mix = (intensity / 100.0).clamp(0.0, 1.0);

        if let Some(proc) = ulunas {
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
                            self.apply_rnnoise(data, channels, intensity);
                            return;
                        }
                    }
                } else {
                    log::warn!(
                        "[DSP] PureVox model not found at {:?}, falling back to RNNoise",
                        path
                    );
                    self.apply_rnnoise(data, channels, intensity);
                    return;
                }
            } else {
                self.apply_rnnoise(data, channels, intensity);
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

    // ── Dereverb (delay-line comb filter, matching KMP DereverbEffect) ─────

    fn apply_dereverb(&mut self, data: &mut Vec<f32>, channels: usize, level: f32) {
        let mix = (level / 100.0).clamp(0.0, 1.0);
        if mix <= 0.0 || channels == 0 {
            return;
        }

        let delay = 480usize;

        // Ensure buffers are sized
        if self.dereverb_buffer_left.len() != delay {
            self.dereverb_buffer_left = vec![0.0; delay];
        }
        if self.dereverb_buffer_right.len() != delay {
            self.dereverb_buffer_right = vec![0.0; delay];
        }

        if channels == 1 {
            let buf = &mut self.dereverb_buffer_left;
            for sample in data.iter_mut() {
                let delayed = buf[self.dereverb_index];
                buf[self.dereverb_index] = *sample;
                *sample = (*sample - delayed * mix).clamp(-1.0, 1.0);
                self.dereverb_index += 1;
                if self.dereverb_index >= delay {
                    self.dereverb_index = 0;
                }
            }
        } else {
            let buf_l = &mut self.dereverb_buffer_left;
            let buf_r = &mut self.dereverb_buffer_right;
            let mut i = 0;
            while i + 1 < data.len() {
                let delayed_l = buf_l[self.dereverb_index];
                let delayed_r = buf_r[self.dereverb_index];
                buf_l[self.dereverb_index] = data[i];
                buf_r[self.dereverb_index] = data[i + 1];
                data[i] = (data[i] - delayed_l * mix).clamp(-1.0, 1.0);
                data[i + 1] = (data[i + 1] - delayed_r * mix).clamp(-1.0, 1.0);
                self.dereverb_index += 1;
                if self.dereverb_index >= delay {
                    self.dereverb_index = 0;
                }
                i += 2;
            }
        }
    }

    // ── AGC ─────────────────────────────────────────────────────────────────

    fn apply_agc(&mut self, data: &mut Vec<f32>, target: f32, attack: f32, decay: f32) {
        let target_linear = target / 32767.0;
        // Noise gate threshold: below this level, don't apply AGC gain
        // (prevents amplifying hiss/noise floor during speech pauses)
        let gate_threshold = 0.005_f32; // ~ -46dB

        for sample in data.iter_mut() {
            let abs_sample = sample.abs();
            if abs_sample > self.agc_envelope {
                self.agc_envelope += attack * (abs_sample - self.agc_envelope);
            } else {
                self.agc_envelope += decay * (abs_sample - self.agc_envelope);
            }
            if self.agc_envelope > gate_threshold {
                let desired_gain = target_linear / self.agc_envelope;
                let clamped_gain = desired_gain.clamp(0.1, 5.0);
                // Smooth gain transition to avoid pops (exponential moving average)
                let smooth_factor = 0.005_f32;
                self.agc_smoothed_gain += smooth_factor * (clamped_gain - self.agc_smoothed_gain);
                *sample *= self.agc_smoothed_gain;
            } else {
                // Below noise gate: smoothly reduce gain toward unity
                let smooth_factor = 0.002_f32;
                self.agc_smoothed_gain += smooth_factor * (1.0 - self.agc_smoothed_gain);
                *sample *= self.agc_smoothed_gain;
            }
        }
    }

    // ── VAD ─────────────────────────────────────────────────────────────────

    fn apply_vad(&mut self, data: &mut Vec<f32>, threshold_db: f32) {
        let rms = compute_rms(data);
        let rms_db = if rms > 1e-10 {
            20.0 * rms.log10()
        } else {
            -100.0
        };
        let target_fade = if rms_db >= threshold_db { 1.0 } else { 0.0 };
        let fade_speed = if target_fade > self.vad_fade {
            0.1
        } else {
            0.02
        };
        self.vad_fade += fade_speed * (target_fade - self.vad_fade);
        self.vad_fade = self.vad_fade.clamp(0.0, 1.0);
        for sample in data.iter_mut() {
            *sample *= self.vad_fade;
        }
    }

    // ── Spectrum ─────────────────────────────────────────────────────────────

    fn compute_spectrum(&mut self, data: &[f32], is_raw: bool) {
        let bands = 64;
        let target = if is_raw {
            &mut self.raw_spectrum
        } else {
            &mut self.processed_spectrum
        };
        if target.len() != bands {
            target.resize(bands, 0.0);
        }
        if data.is_empty() {
            for v in target.iter_mut() {
                *v = 0.0;
            }
            return;
        }

        const BANDS: usize = 64;
        static BAND_LIMITS: std::sync::OnceLock<[f32; BANDS + 1]> = std::sync::OnceLock::new();
        let limits = BAND_LIMITS.get_or_init(|| {
            let mut array = [0.0; BANDS + 1];
            for i in 0..=BANDS {
                array[i] = (i as f32 / BANDS as f32).powf(1.5);
            }
            array
        });

        for (band_idx, band_val) in target.iter_mut().enumerate() {
            let start = limits[band_idx] * data.len() as f32;
            let end = limits[band_idx + 1] * data.len() as f32;
            let start = start as usize;
            let end = (end as usize).min(data.len());
            if start >= end {
                *band_val *= 0.85;
                continue;
            }
            let mut sum = 0.0_f32;
            for i in start..end {
                sum += data[i] * data[i];
            }
            let rms = (sum / (end - start) as f32).sqrt();
            let db = if rms > 1e-10 {
                20.0 * rms.log10()
            } else {
                -100.0
            };
            let normalized = ((db + 60.0) / 60.0).clamp(0.0, 1.0);
            if normalized > *band_val {
                *band_val = normalized;
            } else {
                *band_val = *band_val * 0.85 + normalized * 0.15;
            }
        }
    }
}

fn compute_rms(data: &[f32]) -> f32 {
    if data.is_empty() {
        return 0.0;
    }
    let sum: f32 = data.iter().map(|s| s * s).sum();
    (sum / data.len() as f32).sqrt()
}

/// Soft clip — smooth polynomial knee to avoid harsh hard-clipping artifacts.
/// Identity below 0.95, smooth Hermite compression to ±1.0 at ±2.0.
fn soft_clip(sample: f32) -> f32 {
    let a = sample.abs();
    if a <= 0.95 {
        sample
    } else if a <= 2.0 {
        let sign = sample.signum();
        let t = (a - 0.95) / 1.05; // 0..1 over [0.95, 2.0]
                                   // Hermite smoothstep: C1 continuous, f(0)=0, f(1)=1, f'(0)=f'(1)=0
        let s = t * t * (3.0 - 2.0 * t);
        sign * (0.95 + 0.05 * s)
    } else {
        sample.signum()
    }
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
        for _ in 0..20 {
            processor.process(&mut data, 1, 80.0);
        }
        assert!(
            data[data.len() - 1].abs() < 0.01,
            "Expected muted, got {}",
            data[data.len() - 1]
        );
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
        for _ in 0..10 {
            processor.process(&mut data, 1, 80.0);
        }
        assert!(
            data[data.len() - 1].abs() > 0.01,
            "AGC should have amplified the signal"
        );
    }
}
