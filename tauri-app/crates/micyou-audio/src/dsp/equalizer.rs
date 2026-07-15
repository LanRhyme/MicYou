/// Second-order biquad filter for parametric equalization.
pub struct BiquadFilter {
    a0: f32,
    a1: f32,
    a2: f32,
    b1: f32,
    b2: f32,
    x1: f32,
    x2: f32,
    y1: f32,
    y2: f32,
}

impl BiquadFilter {
    pub fn new() -> Self {
        Self {
            a0: 1.0, a1: 0.0, a2: 0.0,
            b1: 0.0, b2: 0.0,
            x1: 0.0, x2: 0.0,
            y1: 0.0, y2: 0.0,
        }
    }

    /// Configure as a peaking EQ filter.
    /// `freq` in Hz, `q` quality factor, `gain_db` in dB, `sample_rate` in Hz.
    pub fn set_peaking_eq(&mut self, freq: f32, q: f32, gain_db: f32, sample_rate: f32) {
        let a = 10.0_f32.powf(gain_db / 40.0);
        let w0 = 2.0 * std::f32::consts::PI * freq / sample_rate;
        let alpha = w0.sin() / (2.0 * q);

        let b0 = 1.0 + alpha * a;
        let b1 = -2.0 * w0.cos();
        let b2 = 1.0 - alpha * a;
        let a0 = 1.0 + alpha / a;
        let a1 = -2.0 * w0.cos();
        let a2 = 1.0 - alpha / a;

        let norm = 1.0 / a0;
        self.a0 = b0 * norm;
        self.a1 = b1 * norm;
        self.a2 = b2 * norm;
        self.b1 = a1 * norm;
        self.b2 = a2 * norm;
        self.x1 = 0.0;
        self.x2 = 0.0;
        self.y1 = 0.0;
        self.y2 = 0.0;
    }

    pub fn process(&mut self, sample: f32) -> f32 {
        let output = self.a0 * sample + self.a1 * self.x1 + self.a2 * self.x2
                   - self.b1 * self.y1 - self.b2 * self.y2;
        self.x2 = self.x1;
        self.x1 = sample;
        self.y2 = self.y1;
        self.y1 = output;
        output
    }

    pub fn reset(&mut self) {
        self.x1 = 0.0; self.x2 = 0.0;
        self.y1 = 0.0; self.y2 = 0.0;
    }
}

use super::equalizer_config::EqualizerConfig;

/// 11-band peaking equalizer effect.
pub struct EqualizerEffect {
    filters_ch1: Vec<BiquadFilter>,
    filters_ch2: Vec<BiquadFilter>,
    pre_amp_gain: f32,
    frequencies: [f32; 11],
}

impl EqualizerEffect {
    pub fn new() -> Self {
        let frequencies = [32.0, 64.0, 125.0, 250.0, 500.0, 1000.0, 2000.0, 4000.0, 8000.0, 16000.0, 20000.0];
        Self {
            filters_ch1: (0..11).map(|_| BiquadFilter::new()).collect(),
            filters_ch2: (0..11).map(|_| BiquadFilter::new()).collect(),
            pre_amp_gain: 0.0,
            frequencies,
        }
    }

    pub fn update_filters(&mut self, config: &EqualizerConfig) {
        self.pre_amp_gain = 10.0_f32.powf(config.pre_amp / 20.0);
        for i in 0..11 {
            let gain = config.gains[i];
            let freq = self.frequencies[i];
            self.filters_ch1[i].set_peaking_eq(freq, 1.4, gain, 48000.0);
            self.filters_ch2[i].set_peaking_eq(freq, 1.4, gain, 48000.0);
        }
    }

    /// Process stereo interleaved audio.
    pub fn process(&mut self, data: &mut [f32], channels: usize) {
        if channels >= 2 {
            let mut i = 0;
            while i + 1 < data.len() {
                let mut left = data[i] * self.pre_amp_gain;
                let mut right = data[i + 1] * self.pre_amp_gain;
                for f in 0..11 {
                    left = self.filters_ch1[f].process(left);
                    right = self.filters_ch2[f].process(right);
                }
                data[i] = left;
                data[i + 1] = right;
                i += 2;
            }
        } else {
            for sample in data.iter_mut() {
                let mut s = *sample * self.pre_amp_gain;
                for f in 0..11 {
                    s = self.filters_ch1[f].process(s);
                }
                *sample = s;
            }
        }
    }
}
