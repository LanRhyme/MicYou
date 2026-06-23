/// Simple AGC envelope follower.
pub struct AgcEffect {
    envelope: f32,
}

impl AgcEffect {
    pub fn new() -> Self {
        Self { envelope: 0.0 }
    }

    pub fn process(&mut self, data: &mut [f32], target: f32, attack: f32, decay: f32) {
        let target_linear = target / 32767.0;
        for sample in data.iter_mut() {
            let abs_sample = sample.abs();
            if abs_sample > self.envelope {
                self.envelope += attack * (abs_sample - self.envelope);
            } else {
                self.envelope += decay * (abs_sample - self.envelope);
            }
            if self.envelope > 1e-6 {
                let desired_gain = target_linear / self.envelope;
                let clamped_gain = desired_gain.clamp(0.1, 10.0);
                *sample *= clamped_gain;
            }
        }
    }
}
