#[cfg(feature = "dsp")]
use rustfft::{num_complex::Complex, FftPlanner};

/// Speexdsp-style frequency-domain noise suppressor.
#[cfg(feature = "dsp")]
pub struct SpeexStyleNS {
    fft_size: usize,
    noise_estimate: Vec<f32>,
    adaptation_rate: f32,
    fft_forward: std::sync::Arc<dyn rustfft::Fft<f32>>,
    fft_inverse: std::sync::Arc<dyn rustfft::Fft<f32>>,
}

#[cfg(feature = "dsp")]
impl SpeexStyleNS {
    pub fn new() -> Self {
        let fft_size = 480;
        let mut planner = FftPlanner::new();
        let fft_forward = planner.plan_fft_forward(fft_size);
        let fft_inverse = planner.plan_fft_inverse(fft_size);

        Self {
            fft_size,
            noise_estimate: vec![0.0; fft_size / 2 + 1],
            adaptation_rate: 0.05,
            fft_forward,
            fft_inverse,
        }
    }

    pub fn process(&mut self, data: &mut [f32], intensity: f32) {
        if data.len() < self.fft_size {
            return;
        }

        let mix = (intensity / 100.0).clamp(0.0, 1.0);
        let input_len = data.len();

        for chunk_start in (0..input_len).step_by(self.fft_size) {
            let chunk_end = (chunk_start + self.fft_size).min(input_len);
            let chunk = &mut data[chunk_start..chunk_end];
            if chunk.len() != self.fft_size {
                break;
            }

            // Forward FFT
            let mut fft_input: Vec<Complex<f32>> =
                chunk.iter().map(|&s| Complex { re: s, im: 0.0 }).collect();
            self.fft_forward.process(&mut fft_input);

            let half = self.fft_size / 2 + 1;

            // Estimate noise and suppress
            for i in 0..half {
                let magnitude =
                    (fft_input[i].re * fft_input[i].re + fft_input[i].im * fft_input[i].im).sqrt();
                self.noise_estimate[i] = self.noise_estimate[i] * (1.0 - self.adaptation_rate)
                    + magnitude * self.adaptation_rate;

                let snr = magnitude / (self.noise_estimate[i] + 1e-10);
                let gain = (snr / (snr + 1.0)).powf(2.0);
                let smooth_gain = gain * mix + (1.0 - mix);
                fft_input[i].re *= smooth_gain;
                fft_input[i].im *= smooth_gain;
            }

            // Inverse FFT
            self.fft_inverse.process(&mut fft_input);
            let scale = 1.0 / self.fft_size as f32;
            for (i, sample) in chunk.iter_mut().enumerate() {
                *sample = fft_input[i].re * scale;
            }
        }
    }
}

#[cfg(not(feature = "dsp"))]
pub struct SpeexStyleNS;

#[cfg(not(feature = "dsp"))]
impl SpeexStyleNS {
    pub fn new() -> Self {
        Self
    }
    pub fn process(&mut self, _data: &mut [f32], _intensity: f32) {}
}
