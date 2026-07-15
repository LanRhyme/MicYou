/// Lightweight noise reduction — a simple downward expander (soft noise gate).
pub struct LightweightNS;

impl LightweightNS {
    pub fn new() -> Self {
        Self
    }

    /// Apply soft downward expansion to reduce low-level noise.
    ///
    /// `intensity` 0..100 maps to a threshold of -60dB to -30dB.
    pub fn process(&self, data: &mut [f32], intensity: f32) {
        let threshold_db = -60.0 + (intensity / 100.0) * 30.0;
        let threshold = 10.0_f32.powf(threshold_db / 20.0);
        let ratio = 2.0; // Expansion ratio

        for sample in data.iter_mut() {
            let abs_val = sample.abs();
            if abs_val < threshold && abs_val > 0.0 {
                // Apply soft downward expansion
                let gain = (abs_val / threshold).powf(ratio - 1.0);
                *sample *= gain;
            }
        }
    }
}
