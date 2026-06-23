/// Simple RMS-threshold VAD with smooth fade-in/out.
pub struct VadEffect {
    fade: f32,
}

impl VadEffect {
    pub fn new() -> Self {
        Self { fade: 1.0 }
    }

    pub fn process(&mut self, data: &mut [f32], threshold_db: f32) {
        let rms = super::dsp::compute_rms(data);
        let rms_db = if rms > 1e-10 { 20.0 * rms.log10() } else { -100.0 };
        let target_fade = if rms_db >= threshold_db { 1.0 } else { 0.0 };
        let fade_speed = if target_fade > self.fade { 0.1 } else { 0.02 };
        self.fade += fade_speed * (target_fade - self.fade);
        self.fade = self.fade.clamp(0.0, 1.0);
        for sample in data.iter_mut() {
            *sample *= self.fade;
        }
    }
}
