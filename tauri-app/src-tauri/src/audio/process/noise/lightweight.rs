/// Simple soft noise gate (expander).
pub struct LightweightDenoiser;

impl LightweightDenoiser {
    pub fn new() -> Self {
        Self
    }

    pub fn process(&mut self, data: &mut [f32], intensity: f32) {
        // intensity 0..100 maps to a threshold of -60dB to -30dB
        let threshold_db = -60.0 + (intensity / 100.0) * 30.0;
        let threshold = 10.0_f32.powf(threshold_db / 20.0);
        let ratio = 2.0;

        for sample in data.iter_mut() {
            let abs_val = sample.abs();
            if abs_val < threshold && abs_val > 0.0 {
                let gain = (abs_val / threshold).powf(ratio - 1.0);
                *sample *= gain;
            }
        }
    }
}
