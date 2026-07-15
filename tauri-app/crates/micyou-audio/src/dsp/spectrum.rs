/// Spectrum analyzer that computes log-spaced band energies.
pub struct SpectrumState {
    raw: Vec<f32>,
    processed: Vec<f32>,
}

impl SpectrumState {
    pub fn new(bands: usize) -> Self {
        Self {
            raw: vec![0.0; bands],
            processed: vec![0.0; bands],
        }
    }

    /// Compute a log-spaced spectrum from the audio buffer.
    ///
    /// `is_raw` selects whether we're writing to the raw or processed spectrum buffer.
    pub fn compute(&mut self, data: &[f32], is_raw: bool) {
        let bands = self.raw.len();
        let target = if is_raw {
            &mut self.raw
        } else {
            &mut self.processed
        };

        if data.is_empty() {
            for v in target.iter_mut() {
                *v = 0.0;
            }
            return;
        }

        // Build log-spaced band limits (warped toward low frequencies)
        let limits: Vec<f32> = (0..=bands)
            .map(|i| (i as f32 / bands as f32).powf(1.5))
            .collect();

        for band_idx in 0..bands {
            let start = limits[band_idx] * data.len() as f32;
            let end = limits[band_idx + 1] * data.len() as f32;
            let start = start as usize;
            let end = (end as usize).min(data.len());

            if start >= end {
                target[band_idx] *= 0.85;
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

            if normalized > target[band_idx] {
                target[band_idx] = normalized;
            } else {
                target[band_idx] = target[band_idx] * 0.85 + normalized * 0.15;
            }
        }
    }

    pub fn get_spectrums(&self) -> (Vec<f32>, Vec<f32>) {
        (self.raw.clone(), self.processed.clone())
    }
}
