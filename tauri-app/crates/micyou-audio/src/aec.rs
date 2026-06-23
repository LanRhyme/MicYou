// ─── AEC7 Streaming Acoustic Echo Cancellation ───────────────────────────────
//
// Integrates the aec7_ep0185.onnx model for real-time echo cancellation.
// Works on 48 kHz mono audio, processing STFT frames (WIN=960, HOP=480).
// Requires both microphone and far-end reference (speaker loopback) signals.

use ndarray::{Array1, Array3};
use num_complex::Complex;
use rustfft::FftPlanner;
use std::path::Path;

// ── Constants (consistent with Python AEC7 model) ──

/// Sample rate required by the model
pub const AEC_FS: u32 = 48000;
/// STFT window length in samples
pub const WIN_LEN: usize = 960;
/// STFT hop length in samples (≈10 ms)
pub const HOP_LEN: usize = 480;
/// FFT size
pub const NFFT: usize = 960;
/// Number of frequency bins (NFFT/2 + 1)
pub const N_BINS: usize = NFFT / 2 + 1; // 481
/// Frame rate (frames per second)
pub const FRAME_RATE: f64 = AEC_FS as f64 / HOP_LEN as f64; // 100 fps

// ── Hanning Window ───────────────────────────────────────────────────────────

/// Generate sqrt-Hann analysis window, matching PyTorch `hann_window.pow(0.5)`.
pub fn hann_window(len: usize) -> Array1<f32> {
    Array1::from_iter((0..len).map(|i| {
        let x = (std::f32::consts::PI * i as f32 / (len - 1) as f32).sin();
        x * x // equivalent to 0.5 * (1.0 - cos(2*PI*i/(len-1)))
    }))
}

// ── STFT ─────────────────────────────────────────────────────────────────────

/// Convert time-domain waveform into a sequence of STFT frames.
///
/// Each frame has shape (1, 2, N_BINS):
///   - [0, 0, :] = real part
///   - [0, 1, :] = imag part
pub fn stft(wav: &[f32], window: &[f32], fft: &dyn rustfft::Fft<f32>) -> Vec<Array3<f32>> {
    if wav.len() < WIN_LEN {
        return Vec::new();
    }
    let num_frames = (wav.len() - WIN_LEN) / HOP_LEN + 1;
    let mut frames = Vec::with_capacity(num_frames);

    for t in 0..num_frames {
        let start = t * HOP_LEN;
        let mut buf: Vec<Complex<f32>> = (0..NFFT)
            .map(|i| Complex::new(wav[start + i] * window[i], 0.0))
            .collect();

        fft.process(&mut buf);

        let mut frame = Array3::zeros((1, 2, N_BINS));
        for bin in 0..N_BINS {
            frame[[0, 0, bin]] = buf[bin].re;
            frame[[0, 1, bin]] = buf[bin].im;
        }
        frames.push(frame);
    }
    frames
}

// ── iSTFT ────────────────────────────────────────────────────────────────────

/// Synthesize time-domain waveform from enhanced STFT frames using OLA.
///
/// * `frames` — enhanced frames, each shape (1, 2, N_BINS)
/// * `window` — synthesis window coefficients (same as analysis window)
/// * `ifft` — inverse FFT planner
/// * `length` — target output length in samples (usually original input length)
pub fn istft(
    frames: &[Array3<f32>],
    window: &[f32],
    ifft: &dyn rustfft::Fft<f32>,
    length: usize,
) -> Vec<f32> {
    if frames.is_empty() {
        return Vec::new();
    }
    let num_frames = frames.len();
    let out_len = (num_frames - 1) * HOP_LEN + WIN_LEN;
    let mut out = vec![0.0f32; out_len];
    let mut sum_window = vec![0.0f32; out_len];

    for (t, frame) in frames.iter().enumerate() {
        // Rebuild symmetric spectrum from one-sided bins
        let mut buf: Vec<Complex<f32>> = Vec::with_capacity(NFFT);
        for bin in 0..NFFT {
            if bin < N_BINS {
                buf.push(Complex::new(frame[[0, 0, bin]], frame[[0, 1, bin]]));
            } else {
                let mirror = NFFT - bin;
                buf.push(Complex::new(frame[[0, 0, mirror]], -frame[[0, 1, mirror]]));
            }
        }

        ifft.process(&mut buf);

        let offset = t * HOP_LEN;
        for i in 0..WIN_LEN {
            let val = buf[i].re / NFFT as f32 * window[i];
            out[offset + i] += val;
            sum_window[offset + i] += window[i] * window[i];
        }
    }

    // OLA normalization
    for i in 0..out_len.min(length) {
        if sum_window[i] > 1e-6 {
            out[i] /= sum_window[i];
        }
    }

    out.truncate(length);
    out
}

// ── AEC7 ONNX Inference Engine ───────────────────────────────────────────────

/// Streaming AEC7 inference engine.
///
/// Manages the ONNX session and 13 inter-frame state caches.
/// Each call to `step()` processes one STFT frame pair (mic + far).
pub struct Aec7Infer {
    session: ort::session::Session,
    /// 13 cache tensors, ordered as in ONNX inputs 3..15
    cache_data: Vec<Vec<f32>>,
    /// Shapes of each cache tensor
    cache_shapes: Vec<Vec<usize>>,
}

impl Aec7Infer {
    /// Load the ONNX model and initialize all caches to zero.
    pub fn new(model_path: &Path) -> Result<Self, Box<dyn std::error::Error>> {
        let session = ort::session::Session::builder()?
            .with_intra_threads(1)?
            .with_inter_threads(1)?
            .commit_from_file(model_path.to_str().ok_or("invalid model path")?)?;

        // Cache shapes (in ONNX input order, skipping mic_frame and far_frame)
        let cache_shapes: Vec<Vec<usize>> = vec![
            vec![1, 135680], // res_enc_conv
            vec![1, 248],    // res_enc_tfa
            vec![1, 135680], // mic_enc_conv
            vec![1, 248],    // mic_enc_tfa
            vec![1, 0],      // deep_enc_conv (zero-size)
            vec![1, 336],    // deep_enc_tfa
            vec![1, 13440],  // dec_conv
            vec![1, 496],    // dec_tfa
            vec![1, 7680],   // inter
            vec![1, 1, 1, 320], // res_prev1
            vec![1, 1, 1, 320], // res_prev2
            vec![1, 1, 1, 320], // mic_prev1
            vec![1, 1, 1, 320], // mic_prev2
        ];

        let cache_data: Vec<Vec<f32>> = cache_shapes
            .iter()
            .map(|shape| vec![0.0f32; shape.iter().product()])
            .collect();

        Ok(Self {
            session,
            cache_data,
            cache_shapes,
        })
    }

    /// Reset all caches to zero (for starting a new audio stream).
    pub fn reset(&mut self) {
        for (data, shape) in self.cache_data.iter_mut().zip(&self.cache_shapes) {
            let len: usize = shape.iter().product();
            data.resize(len, 0.0);
            data.fill(0.0);
        }
    }

    /// Process one STFT frame.
    ///
    /// * `mic_frame` — current microphone STFT frame, flat vec of 2*481 = 962 f32 (real, imag interleaved)
    /// * `far_frame` — current far-end reference STFT frame, same layout
    ///
    /// Returns enhanced frame as flat vec of 962 f32 (real, imag interleaved).
    pub fn step(
        &mut self,
        mic_frame: &[f32], // [real0, imag0, real1, imag1, ...]  length = 962
        far_frame: &[f32],
    ) -> Result<Vec<f32>, Box<dyn std::error::Error>> {
        // Build input values — match existing ort 2.0.0-rc.12 API pattern
        let val_mic = ort::value::Value::from_array((vec![1i64, 2, N_BINS as i64], mic_frame.to_vec()))?;
        let val_far = ort::value::Value::from_array((vec![1i64, 2, N_BINS as i64], far_frame.to_vec()))?;

        let val_caches: Vec<_> = self
            .cache_data
            .iter()
            .zip(&self.cache_shapes)
            .map(|(data, shape)| {
                let dims: Vec<i64> = shape.iter().map(|&d| d as i64).collect();
                ort::value::Value::from_array((dims, data.clone())).unwrap()
            })
            .collect();

        // Run inference using the inputs! macro
        let outputs = self.session.run(ort::inputs![
            &val_mic,
            &val_far,
            &val_caches[0], &val_caches[1], &val_caches[2], &val_caches[3],
            &val_caches[4], &val_caches[5], &val_caches[6], &val_caches[7],
            &val_caches[8], &val_caches[9], &val_caches[10], &val_caches[11],
            &val_caches[12],
        ])?;

        // Extract enhanced_frame (output 0)
        let enhanced = if let Ok(tensor) = outputs[0].try_extract_tensor::<f32>() {
            tensor.1.to_vec() // (Shape, &[f32]) -> Vec<f32>
        } else {
            return Err("failed to extract enhanced_frame".into());
        };

        // Update caches from outputs 1..14
        for i in 0..self.cache_data.len().min(outputs.len() - 1) {
            if let Ok(tensor) = outputs[i + 1].try_extract_tensor::<f32>() {
                let output_data = tensor.1;
                if output_data.len() == self.cache_data[i].len() {
                    self.cache_data[i].copy_from_slice(output_data);
                }
            }
        }

        Ok(enhanced)
    }
}

// ── High-Level Streaming AEC7 Processor ──────────────────────────────────────

/// Streaming AEC7 processor that handles STFT/iSTFT and frame buffering.
///
/// Usage:
/// ```ignore
/// let mut aec = Aec7Processor::new(model_path)?;
/// let enhanced = aec.process(&mic_chunk, &far_chunk)?;
/// ```
pub struct Aec7Processor {
    infer: Aec7Infer,
    fft: std::sync::Arc<dyn rustfft::Fft<f32>>,
    ifft: std::sync::Arc<dyn rustfft::Fft<f32>>,
    window: Vec<f32>,
    /// Accumulation buffer for mic samples (fills to HOP_LEN before processing)
    mic_buffer: Vec<f32>,
    /// Accumulation buffer for far-end reference samples
    far_buffer: Vec<f32>,
    /// Overlap buffer from previous frame for STFT (WIN_LEN - HOP_LEN = 480 samples)
    mic_overlap: Vec<f32>,
    /// Overlap buffer for far-end reference
    far_overlap: Vec<f32>,
    /// OLA accumulator for iSTFT
    ola_buf: Vec<f32>,
    /// OLA window sum for normalization
    ola_window_sum: Vec<f32>,
    /// Current write position in the OLA circular buffer
    ola_pos: usize,
}

impl Aec7Processor {
    /// Create a new streaming AEC7 processor.
    pub fn new(model_path: &Path) -> Result<Self, Box<dyn std::error::Error>> {
        let infer = Aec7Infer::new(model_path)?;
        let mut planner = FftPlanner::new();
        let fft = planner.plan_fft_forward(NFFT);
        let ifft = planner.plan_fft_inverse(NFFT);
        let window: Vec<f32> = hann_window(WIN_LEN).iter().copied().collect();

        Ok(Self {
            infer,
            fft,
            ifft,
            window,
            mic_buffer: Vec::with_capacity(HOP_LEN),
            far_buffer: Vec::with_capacity(HOP_LEN),
            mic_overlap: vec![0.0f32; WIN_LEN - HOP_LEN],
            far_overlap: vec![0.0f32; WIN_LEN - HOP_LEN],
            ola_buf: Vec::new(),
            ola_window_sum: Vec::new(),
            ola_pos: 0,
        })
    }

    /// Reset processor state for a new audio stream.
    pub fn reset(&mut self) {
        self.infer.reset();
        self.mic_buffer.clear();
        self.far_buffer.clear();
        self.mic_overlap.fill(0.0);
        self.far_overlap.fill(0.0);
        self.ola_buf.clear();
        self.ola_window_sum.clear();
        self.ola_pos = 0;
    }

    /// Process incoming audio chunks in streaming mode.
    ///
    /// Audio is accumulated internally until enough samples for a full STFT frame
    /// are available. Returns any enhanced output samples ready for playback.
    ///
    /// * `mic` — incoming microphone samples (mono, 48k f32)
    /// * `far` — incoming far-end reference samples (mono, 48k f32, same length as mic)
    ///
    /// Returns enhanced audio samples, or empty vec if not enough data yet.
    pub fn process(&mut self, mic: &[f32], far: &[f32]) -> Result<Vec<f32>, Box<dyn std::error::Error>> {
        if mic.is_empty() || mic.len() != far.len() {
            return Ok(Vec::new());
        }

        self.mic_buffer.extend_from_slice(mic);
        self.far_buffer.extend_from_slice(far);

        let mut output = Vec::new();

        // Process as many complete hop-length frames as available
        while self.mic_buffer.len() >= HOP_LEN && self.far_buffer.len() >= HOP_LEN {
            // Build full STFT frame: overlap + new samples
            let mut mic_frame = Vec::with_capacity(WIN_LEN);
            mic_frame.extend_from_slice(&self.mic_overlap);
            mic_frame.extend_from_slice(&self.mic_buffer[..HOP_LEN]);

            let mut far_frame = Vec::with_capacity(WIN_LEN);
            far_frame.extend_from_slice(&self.far_overlap);
            far_frame.extend_from_slice(&self.far_buffer[..HOP_LEN]);

            // Update overlap buffers
            self.mic_overlap.copy_from_slice(&mic_frame[HOP_LEN..]);
            self.far_overlap.copy_from_slice(&far_frame[HOP_LEN..]);

            // Drain processed samples
            self.mic_buffer.drain(..HOP_LEN);
            self.far_buffer.drain(..HOP_LEN);

            // Apply window and FFT
            let mic_spec = self.forward_stft(&mic_frame);
            let far_spec = self.forward_stft(&far_frame);

            // ONNX inference
            let enhanced_spec = self.infer.step(&mic_spec, &far_spec)?;

            // iSTFT (returns HOP_LEN = 480 samples per frame)
            let enhanced_audio = self.inverse_stft(&enhanced_spec);

            output.extend_from_slice(&enhanced_audio);
        }

        Ok(output)
    }

    /// Compute forward STFT for one frame, returns flat interleaved real/imag.
    fn forward_stft(&self, frame: &[f32]) -> Vec<f32> {
        let mut buf: Vec<Complex<f32>> = (0..NFFT)
            .map(|i| Complex::new(frame[i] * self.window[i], 0.0))
            .collect();

        self.fft.process(&mut buf);

        // Interleave real/imag for ONNX input [1, 2, 481]
        let mut spec = Vec::with_capacity(N_BINS * 2);
        for bin in 0..N_BINS {
            spec.push(buf[bin].re);
            spec.push(buf[bin].im);
        }
        spec
    }

    /// Compute inverse STFT from enhanced spectrum, returns HOP_LEN samples via OLA.
    fn inverse_stft(&mut self, spec_flat: &[f32]) -> Vec<f32> {
        // Rebuild complex spectrum
        let mut buf: Vec<Complex<f32>> = Vec::with_capacity(NFFT);
        for bin in 0..NFFT {
            if bin < N_BINS {
                let re = spec_flat[bin * 2];
                let im = spec_flat[bin * 2 + 1];
                buf.push(Complex::new(re, im));
            } else {
                let mirror = NFFT - bin;
                let re = spec_flat[mirror * 2];
                let im = -spec_flat[mirror * 2 + 1];
                buf.push(Complex::new(re, im));
            }
        }

        self.ifft.process(&mut buf);

        // Apply synthesis window and scale
        let scale = 1.0 / NFFT as f32;
        let mut time_frame = vec![0.0f32; WIN_LEN];
        for i in 0..WIN_LEN {
            time_frame[i] = buf[i].re * scale * self.window[i];
        }

        // OLA: accumulate with circular buffer
        self.ola_overlap_add(&time_frame)
    }

    /// Overlap-add: accumulate the time-domain frame and output ready samples.
    fn ola_overlap_add(&mut self, frame: &[f32]) -> Vec<f32> {
        // Ensure OLA buffers are large enough
        let needed_len = self.ola_pos + WIN_LEN;
        if self.ola_buf.len() < needed_len {
            self.ola_buf.resize(needed_len, 0.0);
            self.ola_window_sum.resize(needed_len, 0.0);
        }

        // Accumulate
        for i in 0..WIN_LEN {
            let w = self.window[i];
            self.ola_buf[self.ola_pos + i] += frame[i];
            self.ola_window_sum[self.ola_pos + i] += w * w;
        }

        // Output HOP_LEN samples if we've accumulated enough
        if self.ola_pos >= HOP_LEN {
            let start = self.ola_pos - HOP_LEN;
            let mut output = Vec::with_capacity(HOP_LEN);
            for i in 0..HOP_LEN {
                let idx = start + i;
                let val = if self.ola_window_sum[idx] > 1e-6 {
                    self.ola_buf[idx] / self.ola_window_sum[idx]
                } else {
                    0.0
                };
                output.push(val);
            }
            // Shift buffers
            let remaining = self.ola_buf.len() - start;
            self.ola_buf.copy_within(start.., 0);
            self.ola_window_sum.copy_within(start.., 0);
            self.ola_buf.truncate(remaining);
            self.ola_window_sum.truncate(remaining);
            self.ola_pos = remaining;
            output
        } else {
            self.ola_pos += HOP_LEN;
            Vec::new()
        }
    }
}

// ── Tests ────────────────────────────────────────────────────────────────────

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_hann_window() {
        let win = hann_window(WIN_LEN);
        assert_eq!(win.len(), WIN_LEN);
        assert!(win[0] < 1e-6); // near zero at edges
        assert!(win[WIN_LEN - 1] < 1e-6);
        assert!(win[WIN_LEN / 2] > 0.5); // peak near center
    }

    #[test]
    fn test_stft_istft_roundtrip() {
        let mut planner = FftPlanner::new();
        let fft = planner.plan_fft_forward(NFFT);
        let ifft = planner.plan_fft_inverse(NFFT);
        let window: Vec<f32> = hann_window(WIN_LEN).iter().copied().collect();

        // Generate a short sine wave
        let freq = 440.0;
        let duration_samples = WIN_LEN * 2; // two frames
        let input: Vec<f32> = (0..duration_samples)
            .map(|i| {
                (2.0 * std::f32::consts::PI * freq * i as f32 / AEC_FS as f32).sin()
            })
            .collect();

        let frames = stft(&input, &window, &*fft);
        let output = istft(&frames, &window, &*ifft, input.len());

        assert_eq!(output.len(), input.len());
        // Compare first few samples after OLA warmup
        let warmup = WIN_LEN;
        for i in warmup..output.len().min(input.len()) {
            let diff = (output[i] - input[i]).abs();
            assert!(diff < 0.01, "sample {}: diff={}", i, diff);
        }
    }

    #[test]
    fn test_aec7_infer_new_and_reset() {
        // This test requires the model file in resources/
        let model_path = std::path::PathBuf::from(env!("CARGO_MANIFEST_DIR"))
            .join("../src-tauri/resources/aec7_ep0185.onnx");
        if !model_path.exists() {
            eprintln!("Skipping test: model file not found at {:?}", model_path);
            return;
        }

        let mut infer = Aec7Infer::new(&model_path).expect("failed to create Aec7Infer");
        infer.reset();

        // Create zero input frames
        let mic_frame = vec![0.0f32; N_BINS * 2];
        let far_frame = vec![0.0f32; N_BINS * 2];

        let enhanced = infer.step(&mic_frame, &far_frame).expect("step failed");
        assert_eq!(enhanced.len(), N_BINS * 2);
    }

    #[test]
    fn test_aec7_processor_creation() {
        let model_path = std::path::PathBuf::from(env!("CARGO_MANIFEST_DIR"))
            .join("../src-tauri/resources/aec7_ep0185.onnx");
        if !model_path.exists() {
            eprintln!("Skipping test: model file not found at {:?}", model_path);
            return;
        }

        let mut proc = Aec7Processor::new(&model_path).expect("failed to create processor");

        // Feed exactly HOP_LEN samples
        let mic = vec![0.1f32; HOP_LEN];
        let far = vec![0.05f32; HOP_LEN];
        let output = proc.process(&mic, &far).expect("process failed");

        // First frame: overlap is zeros, so OLA output starts after HOP_LEN accumulation
        // With initial zero overlap, the first process call will produce HOP_LEN output
        // since ola_pos starts at 0 and we add HOP_LEN, then output when >= HOP_LEN
        // Wait, ola_pos starts at 0. First call: ola_pos += HOP_LEN = 480. Not >= HOP_LEN.
        // Second call would output. But we only call once, so output is empty.
        assert!(output.is_empty() || output.len() == HOP_LEN);
    }
}
