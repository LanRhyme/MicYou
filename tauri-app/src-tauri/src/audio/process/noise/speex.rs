/// Spectral subtraction noise suppressor inspired by Speex.
/// Uses FFT to estimate noise floor and subtract it from the signal.
#[cfg(feature = "dsp")]
pub struct SpeexDenoiser {
    frame_size: usize,
    noise_estimate: Vec<f32>,
    adaptation_rate: f32,
}

#[cfg(feature = "dsp")]
impl SpeexDenoiser {
    pub fn new() -> Self {
        let frame_size = 480;
        Self {
            frame_size,
            noise_estimate: vec![0.0; frame_size / 2 + 1],
            adaptation_rate: 0.02,
        }
    }

    pub fn process(&mut self, data: &mut [f32], intensity: f32) {
        use rustfft::FftPlanner;
        use rustfft::num_complex::Complex;

        let len = data.len();
        if len < self.frame_size {
            return;
        }

        let mut planner = FftPlanner::new();
        let fft_forward = planner.plan_fft_forward(self.frame_size);
        let fft_inverse = planner.plan_fft_inverse(self.frame_size);

        let num_frames = len / self.frame_size;
        let mix = (intensity / 100.0).clamp(0.0, 1.0);

        for frame_idx in 0..num_frames {
            let offset = frame_idx * self.frame_size;
            let frame = &data[offset..offset + self.frame_size];

            let mut complex: Vec<Complex<f32>> = frame
                .iter()
                .map(|&s| Complex::new(s, 0.0))
                .collect();

            fft_forward.process(&mut complex);

            let spec_size = self.frame_size / 2 + 1;

            for i in 0..spec_size {
                let mag = complex[i].norm();
                self.noise_estimate[i] = self.noise_estimate[i] * (1.0 - self.adaptation_rate)
                    + mag * self.adaptation_rate;
            }

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

            fft_inverse.process(&mut complex);

            let scale = 1.0 / self.frame_size as f32;
            for i in 0..self.frame_size {
                data[offset + i] = complex[i].re * scale;
            }
        }
    }
}
