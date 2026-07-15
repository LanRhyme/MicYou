/// Automatic Gain Control with envelope follower and noise gate.
pub struct AgcState {
    envelope: f32,
    smoothed_gain: f32,
}

impl AgcState {
    pub fn new() -> Self {
        Self {
            envelope: 0.0,
            smoothed_gain: 1.0,
        }
    }

    /// Apply AGC to the audio buffer.
    ///
    /// `target` is in linear units relative to i16 scale (e.g., 8000.0 / 32767.0 ≈ 0.244 RMS target).
    /// `attack` and `decay` are rate coefficients.
    pub fn process(&mut self, data: &mut [f32], target: f32, attack: f32, decay: f32) {
        let target_linear = target / 32767.0;
        // Noise gate threshold: below this level, don't apply AGC gain
        // (prevents amplifying hiss/noise floor during speech pauses)
        let gate_threshold = 0.005_f32; // ~ -46dB

        for sample in data.iter_mut() {
            let abs_sample = sample.abs();
            if abs_sample > self.envelope {
                self.envelope += attack * (abs_sample - self.envelope);
            } else {
                self.envelope += decay * (abs_sample - self.envelope);
            }
            if self.envelope > gate_threshold {
                let desired_gain = target_linear / self.envelope;
                let clamped_gain = desired_gain.clamp(0.1, 5.0);
                // Smooth gain transition to avoid pops (exponential moving average)
                let smooth_factor = 0.005_f32;
                self.smoothed_gain += smooth_factor * (clamped_gain - self.smoothed_gain);
                *sample *= self.smoothed_gain;
            } else {
                // Below noise gate: smoothly reduce gain toward unity
                let smooth_factor = 0.002_f32;
                self.smoothed_gain += smooth_factor * (1.0 - self.smoothed_gain);
                *sample *= self.smoothed_gain;
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::dsp::util::compute_rms;

    #[test]
    fn test_agc_boosts_quiet() {
        let mut agc = AgcState::new();
        let mut data = vec![0.005_f32; 480];
        for _ in 0..100 {
            agc.process(&mut data, 8000.0, 0.02, 0.01);
        }
        let rms = compute_rms(&data);
        // After many iterations, gain should have boosted the signal
        assert!(
            rms > 0.005,
            "AGC should boost quiet signal, got rms={}",
            rms
        );
    }
}
