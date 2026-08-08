#[cfg(feature = "noise-suppression")]
use std::collections::{HashMap, VecDeque};
#[cfg(feature = "noise-suppression")]
use std::path::Path;
use std::path::PathBuf;
use std::sync::{Arc, RwLock};

#[cfg(feature = "noise-suppression")]
use ndarray::{Array3, ArrayD, IxDyn};
#[cfg(feature = "dsp")]
use nnnoiseless::DenoiseState;
#[cfg(feature = "noise-suppression")]
use rustfft::num_complex::Complex;

#[cfg(feature = "noise-suppression")]
use crate::AecFailure;

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
    pub ns_type: String,   // "PureVox", "RNNoise", "Speexdsp"
    pub ns_intensity: f32, // 0..100
    pub dereverb_enabled: bool,
    pub dereverb_level: f32, // 0..100
    pub agc_enabled: bool,
    pub agc_target: f32, // 0..32767
    pub agc_attack: f32, // raw slider value 1..100, maps to 0.001..0.1
    pub agc_decay: f32,  // raw slider value 1..100, maps to 0.0001..0.01
    pub vad_enabled: bool,
    pub vad_threshold: f32, // dB, -100..0
    pub aec_enabled: bool,

    /// Output playback ring buffer headroom in milliseconds (100..1200).
    #[serde(default = "default_output_buffer_ms")]
    pub output_buffer_ms: u32,

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
            aec_enabled: false,
            output_buffer_ms: 300,
            processing_chain: vec![
                "AEC".to_string(),
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

impl AudioDspSettings {
    pub fn normalize(&mut self) {
        if !matches!(self.ns_type.as_str(), "PureVox" | "RNNoise" | "Speexdsp") {
            self.ns_type = Self::default().ns_type;
        }
    }
}

/// Default output playback buffer headroom in milliseconds.
fn default_output_buffer_ms() -> u32 {
    300
}

// ─── Shared ONNX window helpers ────────────────────────────────────────────

#[cfg(feature = "noise-suppression")]
fn sqrt_hann_window(size: usize) -> Vec<f32> {
    (0..size)
        .map(|i| {
            let value =
                0.5 - 0.5 * (2.0 * std::f64::consts::PI * i as f64 / (size - 1) as f64).cos();
            value.sqrt() as f32
        })
        .collect()
}

#[cfg(feature = "noise-suppression")]
fn overlap_add_gain(window: &[f32], hop_length: usize) -> f32 {
    let average = (0..hop_length)
        .map(|i| window[i] * window[i] + window[i + hop_length] * window[i + hop_length])
        .sum::<f32>()
        / hop_length as f32;
    if average > 0.001 {
        1.0 / average.sqrt()
    } else {
        1.0
    }
}

// ─── PureVox6 ONNX noise suppression ────────────────────────────────────

#[cfg(feature = "noise-suppression")]
fn onnx_warning_logger() -> ort::logging::LoggerFunction {
    Arc::new(|level, _category, _id, _location, message| match level {
        ort::logging::LogLevel::Warning => log::warn!(target: "onnxruntime", "{message}"),
        ort::logging::LogLevel::Error => log::error!(target: "onnxruntime", "{message}"),
        ort::logging::LogLevel::Fatal => {
            log::error!(target: "onnxruntime", "[FATAL] {message}")
        }
        ort::logging::LogLevel::Verbose | ort::logging::LogLevel::Info => {}
    })
}

#[cfg(feature = "noise-suppression")]
fn configure_onnx_logging() -> ort::Result<()> {
    // `ort`'s tracing-enabled default environment registers a verbose global
    // logger. Configure it before the first Session is built; session-level
    // severity alone does not suppress environment initialization messages.
    ort::init().with_logger(onnx_warning_logger()).commit();
    ort::environment::Environment::current()?.set_log_level(ort::logging::LogLevel::Warning);
    Ok(())
}

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
    fn new(model_path: &Path) -> Result<Self, Box<dyn std::error::Error>> {
        let frame_size = 960;
        let hop_length = 480;

        configure_onnx_logging()?;
        let session = ort::session::Session::builder()?
            .with_log_level(ort::logging::LogLevel::Warning)?
            .with_logger(onnx_warning_logger())?
            .with_intra_threads(1)?
            .with_inter_threads(1)?
            .commit_from_file(model_path)?;

        let window = sqrt_hann_window(frame_size);
        let ola_gain = overlap_add_gain(&window, hop_length);

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
        for (sample, window) in fft_buffer.iter_mut().zip(&self.window) {
            *sample *= window;
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
        for ((sample, complex), window) in fft_buffer.iter_mut().zip(&complex_buf).zip(&self.window)
        {
            *sample = complex.re * scale * window;
        }

        // OLA
        for (accumulated, sample) in self.ola_accumulator.iter_mut().zip(&fft_buffer) {
            *accumulated += sample;
        }

        let mut output = vec![0.0_f32; hop_length];
        for (sample, accumulated) in output.iter_mut().zip(&self.ola_accumulator) {
            *sample = accumulated * self.ola_gain;
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

// ─── AEC7 ONNX acoustic echo cancellation ────────────────────────────────

#[cfg(feature = "noise-suppression")]
const AEC_WIN_LEN: usize = 960;
#[cfg(feature = "noise-suppression")]
const AEC_HOP_LEN: usize = 480;
#[cfg(feature = "noise-suppression")]
const AEC_NFFT: usize = 960;
#[cfg(feature = "noise-suppression")]
const AEC_N_BINS: usize = AEC_NFFT / 2 + 1; // 481

/// Hardcoded aec7 cache state configuration.
/// Shapes and output indices are written explicitly rather than read from
/// the ONNX model, because some ONNX runtimes may not report static shapes
/// reliably.  This matches the C++ reference implementation.
///
/// `deep_enc_conv` (shape [1,0]) is excluded — the ONNX optimizer folds it
/// away, so it is neither an input nor a meaningful output.
/// Its output `deep_enc_conv_o` still appears at output index 5 and is skipped.
#[cfg(feature = "noise-suppression")]
const AEC_CACHE_CONFIGS: &[(&str, &[usize], usize)] = &[
    ("res_enc_conv", &[1, 135680], 1),
    ("res_enc_tfa", &[1, 248], 2),
    ("mic_enc_conv", &[1, 135680], 3),
    ("mic_enc_tfa", &[1, 248], 4),
    // output[5] = deep_enc_conv_o → skipped
    ("deep_enc_tfa", &[1, 336], 6),
    ("dec_conv", &[1, 13440], 7),
    ("dec_tfa", &[1, 496], 8),
    ("inter", &[1, 7680], 9),
    ("res_prev1", &[1, 1, 1, 320], 10),
    ("res_prev2", &[1, 1, 1, 320], 11),
    ("mic_prev1", &[1, 1, 1, 320], 12),
    ("mic_prev2", &[1, 1, 1, 320], 13),
];

/// ONNX-based AEC7 acoustic echo cancellation.
/// Takes both mic (near-end) and far-end (speaker) audio and cancels echo.
/// Based on the aec7_ep0185.onnx model.
#[cfg(feature = "noise-suppression")]
struct AecProcessor {
    session: ort::session::Session,
    /// Cache state tensors with hardcoded shapes (see AEC_CACHE_CONFIGS).
    cache_tensors: Vec<ArrayD<f32>>,
    /// Input names matching the ONNX model — used for HashMap feed lookups.
    cache_names: Vec<String>,
    /// Hardcoded output index for each cache tensor (see AEC_CACHE_CONFIGS).
    cache_output_indices: Vec<usize>,
    // STFT / iSTFT / OLA
    window: Vec<f32>,
    /// Running per-sample accumulation of window² for correct COLA normalization.
    window_sum: Vec<f32>,
    mic_previous: Vec<f32>,    // last 480 mic samples for overlapping frames
    far_previous: Vec<f32>,    // last 480 far-end samples for overlapping frames
    ola_accumulator: Vec<f32>, // 960 samples
    fft_forward: std::sync::Arc<dyn rustfft::Fft<f32>>,
    fft_inverse: std::sync::Arc<dyn rustfft::Fft<f32>>,

    // Pre-allocated scratch buffers — reused every process() call to avoid
    // heap allocations on the real-time audio thread.
    scratch_mic_frame: Vec<f32>,
    scratch_far_frame: Vec<f32>,
    scratch_time_frame: Vec<f32>,
    scratch_complex: Vec<Complex<f32>>,
    scratch_mic_stft: Array3<f32>,
    scratch_far_stft: Array3<f32>,
}

#[cfg(feature = "noise-suppression")]
impl AecProcessor {
    fn new(model_path: &Path) -> Result<Self, Box<dyn std::error::Error>> {
        use rustfft::FftPlanner;

        configure_onnx_logging()?;
        let session = ort::session::Session::builder()?
            .with_log_level(ort::logging::LogLevel::Warning)?
            .with_logger(onnx_warning_logger())?
            .with_intra_threads(1)?
            .with_inter_threads(1)?
            .commit_from_file(model_path)?;

        // Sine window (sqrt of Hann). Applied once during analysis
        // (forward_stft) and once during synthesis (iSTFT in process),
        // giving total window effect = Hann (sin^2).
        let window = sqrt_hann_window(AEC_WIN_LEN);

        let mut planner = FftPlanner::new();
        let fft_forward = planner.plan_fft_forward(AEC_NFFT);
        let fft_inverse = planner.plan_fft_inverse(AEC_NFFT);

        // Cache state tensors use hardcoded shapes and output indices
        // (matching the C++ reference).  This avoids relying on the ONNX
        // runtime to report static shapes reliably.
        let mut cache_names = Vec::with_capacity(AEC_CACHE_CONFIGS.len());
        let mut cache_tensors = Vec::with_capacity(AEC_CACHE_CONFIGS.len());
        let mut cache_output_indices = Vec::with_capacity(AEC_CACHE_CONFIGS.len());

        for &(name, shape, output_idx) in AEC_CACHE_CONFIGS {
            cache_names.push(name.to_string());
            cache_tensors.push(ArrayD::zeros(IxDyn(shape)));
            cache_output_indices.push(output_idx);
        }

        Ok(Self {
            session,
            cache_tensors,
            cache_names,
            cache_output_indices,
            window,
            window_sum: vec![0.0; AEC_WIN_LEN],
            mic_previous: vec![0.0; AEC_HOP_LEN],
            far_previous: vec![0.0; AEC_HOP_LEN],
            ola_accumulator: vec![0.0; AEC_WIN_LEN],
            fft_forward,
            fft_inverse,
            // Pre-allocated scratch buffers — allocated once at construction
            scratch_mic_frame: vec![0.0f32; AEC_WIN_LEN],
            scratch_far_frame: vec![0.0f32; AEC_WIN_LEN],
            scratch_time_frame: vec![0.0f32; AEC_WIN_LEN],
            scratch_complex: vec![Complex::default(); AEC_NFFT],
            scratch_mic_stft: Array3::zeros((1, 2, AEC_N_BINS)),
            scratch_far_stft: Array3::zeros((1, 2, AEC_N_BINS)),
        })
    }

    fn reset(&mut self) {
        for tensor in &mut self.cache_tensors {
            tensor.fill(0.0);
        }
        self.window_sum.fill(0.0);
        self.mic_previous.fill(0.0);
        self.far_previous.fill(0.0);
        self.ola_accumulator.fill(0.0);
    }

    /// Process one frame (480 samples each of mic and far-end).
    /// Returns 480 samples of echo-cancelled audio.
    fn process(&mut self, mic_chunk: &[f32], far_chunk: &[f32]) -> Result<Vec<f32>, String> {
        if mic_chunk.len() != AEC_HOP_LEN || far_chunk.len() != AEC_HOP_LEN {
            return Err(format!(
                "invalid AEC frame lengths: mic={}, far={}, expected={AEC_HOP_LEN}",
                mic_chunk.len(),
                far_chunk.len()
            ));
        }

        // ── Build overlapping frames into scratch buffers ──
        {
            let mic = &mut self.scratch_mic_frame;
            mic[..AEC_HOP_LEN].copy_from_slice(&self.mic_previous);
            mic[AEC_HOP_LEN..].copy_from_slice(mic_chunk);
        }
        self.mic_previous.copy_from_slice(mic_chunk);

        {
            let far = &mut self.scratch_far_frame;
            far[..AEC_HOP_LEN].copy_from_slice(&self.far_previous);
            far[AEC_HOP_LEN..].copy_from_slice(far_chunk);
        }
        self.far_previous.copy_from_slice(far_chunk);

        // ── Apply window + FFT → STFT frames (fill pre-allocated arrays) ──
        forward_stft_free(
            &self.scratch_mic_frame,
            &mut self.scratch_mic_stft,
            &mut self.scratch_complex,
            &self.window,
            &self.fft_forward,
        );
        forward_stft_free(
            &self.scratch_far_frame,
            &mut self.scratch_far_stft,
            &mut self.scratch_complex,
            &self.window,
            &self.fft_forward,
        );

        // ── ONNX inference ──
        // Extract flat slices first to release the immutable borrow on self
        // before infer_step needs &mut self.
        let mic_flat = self.scratch_mic_stft.as_slice().unwrap().to_vec();
        let far_flat = self.scratch_far_stft.as_slice().unwrap().to_vec();
        let enhanced_stft = self
            .infer_step(&mic_flat, &far_flat)
            .map_err(|error| error.to_string())?;

        // ── iSTFT + OLA → time domain (reuse scratch buffers) ──
        {
            let complex_buf = &mut self.scratch_complex;
            for bin in 0..AEC_NFFT {
                if bin < AEC_N_BINS {
                    complex_buf[bin] =
                        Complex::new(enhanced_stft[[0, 0, bin]], enhanced_stft[[0, 1, bin]]);
                } else {
                    let mirror = AEC_NFFT - bin;
                    complex_buf[bin] = Complex::new(
                        enhanced_stft[[0, 0, mirror]],
                        -enhanced_stft[[0, 1, mirror]],
                    );
                }
            }
            self.fft_inverse.process(complex_buf);
            let scale = 1.0 / AEC_NFFT as f32;
            for ((sample, complex), window) in self
                .scratch_time_frame
                .iter_mut()
                .zip(complex_buf.iter())
                .zip(&self.window)
            {
                *sample = complex.re * scale * window;
            }
        }

        // ── OLA with per-sample window-sum normalization ──
        for i in 0..AEC_WIN_LEN {
            self.ola_accumulator[i] += self.scratch_time_frame[i];
            self.window_sum[i] += self.window[i] * self.window[i];
        }

        let mut output = vec![0.0f32; AEC_HOP_LEN];
        for ((sample, norm), accumulated) in output
            .iter_mut()
            .zip(&self.window_sum)
            .zip(&self.ola_accumulator)
        {
            *sample = if *norm > 1e-6 {
                accumulated / norm
            } else {
                *accumulated
            };
        }

        // Shift OLA accumulators
        for i in 0..AEC_WIN_LEN - AEC_HOP_LEN {
            self.ola_accumulator[i] = self.ola_accumulator[i + AEC_HOP_LEN];
            self.window_sum[i] = self.window_sum[i + AEC_HOP_LEN];
        }
        for i in AEC_WIN_LEN - AEC_HOP_LEN..AEC_WIN_LEN {
            self.ola_accumulator[i] = 0.0;
            self.window_sum[i] = 0.0;
        }

        Ok(output)
    }

    /// Run one ONNX inference step with mic and far STFT frames (flat f32 slices).
    fn infer_step(
        &mut self,
        mf_slice: &[f32],
        ff_slice: &[f32],
    ) -> Result<Array3<f32>, Box<dyn std::error::Error>> {
        use ort::value::Tensor;

        let mut feed: HashMap<String, ort::value::DynValue> = HashMap::new();
        feed.insert(
            "mic_frame".into(),
            Tensor::from_array((vec![1_i64, 2, AEC_N_BINS as i64], mf_slice.to_vec()))?.into_dyn(),
        );
        feed.insert(
            "far_frame".into(),
            Tensor::from_array((vec![1_i64, 2, AEC_N_BINS as i64], ff_slice.to_vec()))?.into_dyn(),
        );

        for (name, tensor) in self.cache_names.iter().zip(self.cache_tensors.iter()) {
            let flat = tensor.as_slice().ok_or("cache not contiguous")?;
            let shape_i64: Vec<i64> = tensor.shape().iter().map(|&d| d as i64).collect();
            feed.insert(
                name.clone(),
                Tensor::from_array((shape_i64, flat.to_vec()))?.into_dyn(),
            );
        }

        let outputs = self.session.run(feed)?;

        let (_shape, enhanced_data) = outputs[0]
            .try_extract_tensor::<f32>()
            .map_err(|e| format!("failed to extract enhanced_frame: {}", e))?;
        let enhanced = Array3::from_shape_vec((1, 2, AEC_N_BINS), enhanced_data.to_vec())
            .map_err(|e| format!("enhanced_frame reshape failed: {}", e))?;

        // Update cache states from outputs using hardcoded output indices.
        // This avoids relying on session.inputs() order and the skip_offset_at
        // heuristic.  deep_enc_conv_o at index 5 is simply not mapped.
        for (tensor, &output_idx) in self
            .cache_tensors
            .iter_mut()
            .zip(self.cache_output_indices.iter())
        {
            if let Ok((_shape, data)) = outputs[output_idx].try_extract_tensor::<f32>() {
                let shape = tensor.shape().to_vec();
                *tensor = ArrayD::from_shape_vec(IxDyn(&shape), data.to_vec())?;
            }
        }

        Ok(enhanced)
    }
}

// ─── AEC Delay Estimation and Alignment ───────────────────────────────

/// FIFO for far-end audio fed to AEC. The producer feeds the same number of
/// mono reference samples as the near-end frames entering the DSP pipeline, so
/// each 480-sample AEC hop consumes a distinct reference hop.
#[cfg(feature = "noise-suppression")]
struct FarEndBuffer {
    buffer: VecDeque<f32>,
}

#[cfg(feature = "noise-suppression")]
impl FarEndBuffer {
    fn new() -> Self {
        Self {
            buffer: VecDeque::with_capacity(AEC_HOP_LEN * 4),
        }
    }

    fn feed(&mut self, data: &[f32]) {
        const MAX_REFERENCE_SAMPLES: usize = 48_000 * 2;

        if data.len() >= MAX_REFERENCE_SAMPLES {
            self.buffer.clear();
            self.buffer
                .extend(data[data.len() - MAX_REFERENCE_SAMPLES..].iter().copied());
            return;
        }

        let overflow = self
            .buffer
            .len()
            .saturating_add(data.len())
            .saturating_sub(MAX_REFERENCE_SAMPLES);
        if overflow > 0 {
            self.buffer.drain(..overflow);
        }
        self.buffer.extend(data.iter().copied());
    }

    fn take_hop(&mut self) -> Option<Vec<f32>> {
        if self.buffer.len() < AEC_HOP_LEN {
            return None;
        }
        Some(self.buffer.drain(..AEC_HOP_LEN).collect())
    }

    fn clear(&mut self) {
        self.buffer.clear();
    }
}

/// Free function: compute STFT of a windowed frame, filling `out` in place.
/// Avoids borrowing `AecProcessor` as a whole so that callers can pass in
/// individual field references from the struct.
#[cfg(feature = "noise-suppression")]
fn forward_stft_free(
    windowed_frame: &[f32],
    out: &mut Array3<f32>,
    complex_buf: &mut [Complex<f32>],
    window: &[f32],
    fft_forward: &std::sync::Arc<dyn rustfft::Fft<f32>>,
) {
    for i in 0..AEC_WIN_LEN {
        complex_buf[i] = Complex::new(windowed_frame[i] * window[i], 0.0);
    }
    fft_forward.process(complex_buf);

    for bin in 0..AEC_N_BINS {
        out[[0, 0, bin]] = complex_buf[bin].re;
        out[[0, 1, bin]] = complex_buf[bin].im;
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
            for (bin, noise_estimate) in complex
                .iter()
                .zip(self.noise_estimate.iter_mut())
                .take(spec_size)
            {
                let mag = bin.norm();
                *noise_estimate =
                    *noise_estimate * (1.0 - self.adaptation_rate) + mag * self.adaptation_rate;
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
    // PureVox ONNX processor - separate per channel
    #[cfg(feature = "noise-suppression")]
    purevox_left: Option<PureVoxProcessor>,
    #[cfg(feature = "noise-suppression")]
    purevox_right: Option<PureVoxProcessor>,

    #[cfg(feature = "noise-suppression")]
    purevox_model_path: Option<PathBuf>,
    /// Once a model load fails, stop retrying to avoid expensive reloads
    /// every frame on the real-time audio thread.
    #[cfg(feature = "noise-suppression")]
    purevox_load_failed: bool,

    // AEC7 ONNX acoustic echo cancellation.
    // Stereo is downmixed to mono before AEC, then upmixed back (reference pattern).
    #[cfg(feature = "noise-suppression")]
    aec: Option<AecProcessor>,
    #[cfg(feature = "noise-suppression")]
    aec_model_path: Option<PathBuf>,
    #[cfg(feature = "noise-suppression")]
    /// Latches model loading or inference failures until the next transport
    /// session, preventing expensive retries on the real-time audio thread.
    aec_session_failed: bool,
    #[cfg(feature = "noise-suppression")]
    aec_failure: Option<AecFailure>,
    /// Far-end buffer for AEC.
    #[cfg(feature = "noise-suppression")]
    far_end: FarEndBuffer,

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
    /// Optional external DSP stage injected by the host (plugin system).
    /// Invoked when the processing chain reaches the synthetic `"Plugins"`
    /// node. Kept as a closure so `micyou-audio` stays independent of the
    /// plugin crate (no dependency cycle).
    external_hook: Option<Box<dyn FnMut(&mut Vec<f32>, usize, f64) + Send>>,
}

/// Synthetic processing-chain node name that invokes the external plugin DSP
/// stage. The host inserts it into `AudioDspSettings.processing_chain` at the
/// desired position (default: right after AEC).
pub const PLUGIN_CHAIN_NODE: &str = "Plugins";

const RNNOISE_FRAME_SIZE: usize = 480;

impl DspProcessor {
    pub fn new(settings: Arc<RwLock<AudioDspSettings>>, _model_dir: Option<PathBuf>) -> Self {
        Self {
            settings: settings.clone(),
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
            purevox_left: None,
            #[cfg(feature = "noise-suppression")]
            purevox_right: None,
            #[cfg(feature = "noise-suppression")]
            purevox_load_failed: false,

            #[cfg(feature = "noise-suppression")]
            aec: None,
            #[cfg(feature = "noise-suppression")]
            aec_model_path: _model_dir.as_ref().map(|d| d.join("aec7_ep0185.onnx")),
            #[cfg(feature = "noise-suppression")]
            aec_session_failed: false,
            #[cfg(feature = "noise-suppression")]
            aec_failure: None,
            #[cfg(feature = "noise-suppression")]
            far_end: FarEndBuffer::new(),

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
            external_hook: None,
        }
    }

    /// Attach the external plugin DSP stage (see `PLUGIN_CHAIN_NODE`).
    pub fn set_external_hook(
        &mut self,
        hook: Option<Box<dyn FnMut(&mut Vec<f32>, usize, f64) + Send>>,
    ) {
        self.external_hook = hook;
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
        // Noise reduction requires exactly 480-sample frames.
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
                "AEC" =>
                {
                    #[cfg(feature = "noise-suppression")]
                    if settings.aec_enabled {
                        self.apply_aec(&mut to_process, channels.max(1));
                    }
                }
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
                "VAD" if settings.vad_enabled => {
                    self.apply_vad(&mut to_process, settings.vad_threshold);
                }
                PLUGIN_CHAIN_NODE => {
                    // External plugin DSP stage (may be absent). Runs in chain
                    // position; the host decides where the synthetic node sits.
                    if let Some(hook) = &mut self.external_hook {
                        hook(&mut to_process, channels.max(1), queued_ms);
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

    /// Feed far-end (speaker) audio for AEC.
    #[cfg(feature = "noise-suppression")]
    pub fn set_far_end_audio(&mut self, far_end: &[f32]) {
        self.far_end.feed(far_end);
    }

    /// No-op when the optional DSP feature is disabled. This keeps the public
    /// audio API usable by callers that build the crate without ONNX support.
    #[cfg(not(feature = "noise-suppression"))]
    pub fn set_far_end_audio(&mut self, _far_end: &[f32]) {}

    #[cfg(feature = "noise-suppression")]
    pub fn take_aec_failure(&mut self) -> Option<AecFailure> {
        self.aec_failure.take()
    }

    #[cfg(not(feature = "noise-suppression"))]
    pub fn take_aec_failure(&mut self) -> Option<crate::AecFailure> {
        None
    }

    /// Reset stream-local AEC state when a new transport session begins.
    #[cfg(feature = "noise-suppression")]
    pub fn reset_aec_session(&mut self) {
        self.far_end.clear();
        self.aec_failure = None;
        // Model loading and inference failures are scoped to one transport
        // session. A failed inference discards the ONNX session, so clearing the
        // latch here causes the next session to build a fresh one rather than
        // reusing potentially corrupted runtime state.
        self.aec_session_failed = false;
        if let Some(aec) = &mut self.aec {
            aec.reset();
        }
    }

    #[cfg(not(feature = "noise-suppression"))]
    pub fn reset_aec_session(&mut self) {}

    // ── AEC Acoustic Echo Cancellation ────────────────────────────────────

    #[cfg(feature = "noise-suppression")]
    fn ensure_aec_loaded(&mut self) -> bool {
        if self.aec.is_some() {
            return true;
        }
        if self.aec_session_failed {
            return false;
        }

        let Some(path) = self.aec_model_path.as_deref() else {
            self.fail_aec_load(AecFailure::ModelMissing);
            return false;
        };
        if !path.exists() {
            log::warn!("[DSP] AEC7 model not found at {:?}, disabling AEC", path);
            self.fail_aec_load(AecFailure::ModelMissing);
            return false;
        }

        match AecProcessor::new(path) {
            Ok(processor) => {
                log::info!("[DSP] AEC7 ONNX model loaded: {:?}", path);
                self.aec = Some(processor);
                true
            }
            Err(error) => {
                log::error!("[DSP] Failed to load AEC7 model: {error}");
                self.fail_aec_load(AecFailure::ModelLoadFailed);
                false
            }
        }
    }

    #[cfg(feature = "noise-suppression")]
    fn fail_aec_load(&mut self, failure: AecFailure) {
        self.aec_session_failed = true;
        self.aec_failure = Some(failure);
    }

    #[cfg(feature = "noise-suppression")]
    fn fail_aec_inference(&mut self) {
        // Do not retry a failing ONNX session for every audio packet. Keep the
        // failure latched for this transport session and rebuild on the next
        // reset_aec_session().
        self.aec = None;
        self.aec_session_failed = true;
        self.aec_failure = Some(AecFailure::InferenceFailed);
    }

    #[cfg(feature = "noise-suppression")]
    fn process_aec_mono(&mut self, input: &[f32]) -> Result<Vec<f32>, String> {
        if !input.len().is_multiple_of(AEC_HOP_LEN) {
            return Err(format!(
                "invalid AEC input length: {}, expected a multiple of {AEC_HOP_LEN}",
                input.len()
            ));
        }

        let processor = self
            .aec
            .as_mut()
            .expect("AEC model must be loaded before processing");
        let mut output = Vec::with_capacity(input.len());

        for chunk in input.chunks_exact(AEC_HOP_LEN) {
            match self.far_end.take_hop() {
                Some(far_chunk) => output.extend(processor.process(chunk, &far_chunk)?),
                None => output.extend_from_slice(chunk),
            }
        }

        Ok(output)
    }

    #[cfg(feature = "noise-suppression")]
    fn apply_aec(&mut self, data: &mut Vec<f32>, channels: usize) {
        if !self.ensure_aec_loaded() {
            return;
        }

        let mono = (channels > 1).then(|| {
            data.chunks_exact(channels)
                .map(|frame| frame.iter().sum::<f32>() / channels as f32)
                .collect::<Vec<_>>()
        });
        let input = mono.as_deref().unwrap_or(data);
        let clean = match self.process_aec_mono(input) {
            Ok(clean) => clean,
            Err(error) => {
                log::error!("[DSP] AEC ONNX inference failed: {error}");
                self.fail_aec_inference();
                return;
            }
        };

        if channels == 1 {
            *data = clean;
        } else {
            data.clear();
            data.reserve(clean.len() * channels);
            for sample in clean {
                data.extend(std::iter::repeat_n(sample, channels));
            }
        }
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
        data: &mut [f32],
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

        data.copy_from_slice(&output);
    }

    #[cfg(feature = "noise-suppression")]
    fn apply_purevox(&mut self, data: &mut Vec<f32>, channels: usize, intensity: f32) {
        // Lazy init for both channels — only attempt once; if loading fails,
        // mark it so we don't retry on every audio frame.
        if self.purevox_left.is_none() && !self.purevox_load_failed {
            if let Some(path) = &self.purevox_model_path {
                if path.exists() {
                    match PureVoxProcessor::new(path) {
                        Ok(proc) => {
                            log::info!("[DSP] PureVox ONNX model loaded (L): {:?}", path);
                            self.purevox_left = Some(proc);
                        }
                        Err(e) => {
                            log::error!("[DSP] Failed to load PureVox model: {}", e);
                            self.purevox_load_failed = true;
                            self.apply_rnnoise(data, channels, intensity);
                            return;
                        }
                    }
                } else {
                    log::warn!(
                        "[DSP] PureVox model not found at {:?}, falling back to RNNoise",
                        path
                    );
                    self.purevox_load_failed = true;
                    self.apply_rnnoise(data, channels, intensity);
                    return;
                }
            } else {
                self.purevox_load_failed = true;
                self.apply_rnnoise(data, channels, intensity);
                return;
            }
        }
        if channels >= 2 && self.purevox_right.is_none() && !self.purevox_load_failed {
            if let Some(path) = &self.purevox_model_path {
                if path.exists() {
                    match PureVoxProcessor::new(path) {
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
        data: &mut [f32],
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

    fn apply_dereverb(&mut self, data: &mut [f32], channels: usize, level: f32) {
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

    fn apply_agc(&mut self, data: &mut [f32], target: f32, attack: f32, decay: f32) {
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

    fn apply_vad(&mut self, data: &mut [f32], threshold_db: f32) {
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
            for (i, limit) in array.iter_mut().enumerate() {
                *limit = (i as f32 / BANDS as f32).powf(1.5);
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
            for sample in &data[start..end] {
                sum += sample * sample;
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
    fn plugin_hook_runs_when_chain_contains_plugin_node() {
        let settings = Arc::new(RwLock::new(AudioDspSettings {
            processing_chain: vec![
                "AEC".to_string(),
                PLUGIN_CHAIN_NODE.to_string(),
                "Amplifier".to_string(),
            ],
            ..Default::default()
        }));
        let mut processor = DspProcessor::new(settings, None);
        let calls = std::sync::Arc::new(std::sync::Mutex::new(0usize));
        let calls_clone = calls.clone();
        processor.set_external_hook(Some(Box::new(
            move |data: &mut Vec<f32>, channels: usize, _queued_ms: f64| {
                assert_eq!(channels, 1);
                for sample in data.iter_mut() {
                    *sample += 0.5; // visible marker: plugin output
                }
                *calls_clone.lock().unwrap() += 1;
            },
        )));

        let mut data = vec![0.1f32; 960];
        processor.process(&mut data, 1, 10.0);
        assert_eq!(
            *calls.lock().unwrap(),
            1,
            "hook must run once per chain pass"
        );
        // Amplifier(0dB) + soft clip: plugin marker survives in output
        assert!(
            data.iter().any(|s| (*s - 0.6).abs() < 0.001),
            "plugin output must reach the final buffer"
        );
    }

    #[test]
    fn plugin_hook_skipped_when_chain_has_no_plugin_node() {
        let settings = Arc::new(RwLock::new(AudioDspSettings::default()));
        let mut processor = DspProcessor::new(settings, None);
        let calls = std::sync::Arc::new(std::sync::Mutex::new(0usize));
        let calls_clone = calls.clone();
        processor.set_external_hook(Some(Box::new(
            move |_data: &mut Vec<f32>, _ch: usize, _q: f64| {
                *calls_clone.lock().unwrap() += 1;
            },
        )));

        let mut data = vec![0.1f32; 960];
        processor.process(&mut data, 1, 10.0);
        assert_eq!(
            *calls.lock().unwrap(),
            0,
            "hook must not run without the Plugins node"
        );
    }

    #[test]
    fn unsupported_noise_reduction_type_uses_default() {
        let mut settings = AudioDspSettings {
            ns_type: "RemovedAlgorithm".to_string(),
            ..Default::default()
        };

        settings.normalize();

        assert_eq!(settings.ns_type, AudioDspSettings::default().ns_type);
    }

    #[cfg(feature = "noise-suppression")]
    #[test]
    fn far_end_buffer_consumes_distinct_hops_in_order() {
        let mut buffer = FarEndBuffer::new();
        let samples: Vec<f32> = (0..AEC_HOP_LEN * 2 + 60)
            .map(|sample| sample as f32)
            .collect();
        buffer.feed(&samples);

        let first = buffer.take_hop().unwrap();
        let second = buffer.take_hop().unwrap();
        assert_eq!(first[0], 0.0);
        assert_eq!(first[AEC_HOP_LEN - 1], 479.0);
        assert_eq!(second[0], 480.0);
        assert_eq!(second[AEC_HOP_LEN - 1], 959.0);
        assert!(buffer.take_hop().is_none());

        buffer.clear();
        assert!(buffer.take_hop().is_none());
    }

    #[cfg(feature = "noise-suppression")]
    #[test]
    fn new_session_retries_aec_model_after_load_failure() {
        let settings = Arc::new(RwLock::new(AudioDspSettings::default()));
        let mut processor = DspProcessor::new(settings, None);
        processor.aec_session_failed = true;
        processor.aec_failure = Some(AecFailure::ModelMissing);
        processor.far_end.feed(&vec![1.0; AEC_HOP_LEN]);

        processor.reset_aec_session();

        assert!(!processor.aec_session_failed);
        assert_eq!(processor.aec_failure, None);
        assert!(processor.far_end.take_hop().is_none());
    }

    #[cfg(feature = "noise-suppression")]
    #[test]
    fn inference_failure_is_latched_until_next_session() {
        let settings = Arc::new(RwLock::new(AudioDspSettings::default()));
        let mut processor = DspProcessor::new(settings, None);

        processor.fail_aec_inference();

        assert!(processor.aec.is_none());
        assert!(processor.aec_session_failed);
        assert_eq!(
            processor.take_aec_failure(),
            Some(AecFailure::InferenceFailed)
        );
        assert_eq!(processor.take_aec_failure(), None);
        assert!(!processor.ensure_aec_loaded());

        processor.reset_aec_session();

        assert!(!processor.aec_session_failed);
        assert_eq!(processor.aec_failure, None);
    }

    #[cfg(feature = "noise-suppression")]
    #[test]
    fn invalid_aec_input_length_is_rejected_before_processing() {
        let settings = Arc::new(RwLock::new(AudioDspSettings::default()));
        let mut processor = DspProcessor::new(settings, None);
        let input = vec![0.0; AEC_HOP_LEN - 1];

        let error = processor.process_aec_mono(&input).unwrap_err();

        assert!(error.contains("invalid AEC input length"));
        assert_eq!(input.len(), AEC_HOP_LEN - 1);
    }

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
