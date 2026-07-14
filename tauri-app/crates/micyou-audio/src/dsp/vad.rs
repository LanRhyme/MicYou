use super::util::compute_rms;

/// Voice Activity Detection with smooth fade in/out.
pub struct VadState {
    fade: f32,
}

impl VadState {
    pub fn new() -> Self {
        Self { fade: 1.0 }
    }

    /// Apply VAD gating to the audio buffer.
    ///
    /// `threshold_db` is the RMS threshold in dB. Signal below this level is faded out.
    pub fn process(&mut self, data: &mut [f32], threshold_db: f32) {
        let rms = compute_rms(data);
        let rms_db = if rms > 1e-10 {
            20.0 * rms.log10()
        } else {
            -100.0
        };
        let target_fade = if rms_db >= threshold_db { 1.0 } else { 0.0 };
        let fade_speed = if target_fade > self.fade { 0.1 } else { 0.02 };
        self.fade += fade_speed * (target_fade - self.fade);
        self.fade = self.fade.clamp(0.0, 1.0);
        for sample in data.iter_mut() {
            *sample *= self.fade;
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_vad_mutes_quiet() {
        let mut vad = VadState::new();
        // Very quiet signal
        let mut data = vec![0.00001_f32; 480];
        for _ in 0..50 {
            vad.process(&mut data, -40.0);
        }
        // After many iterations, fade should be near 0
        let rms = compute_rms(&data);
        assert!(
            rms < 0.0001,
            "VAD should mute quiet signal, got rms={}",
            rms
        );
    }
}
