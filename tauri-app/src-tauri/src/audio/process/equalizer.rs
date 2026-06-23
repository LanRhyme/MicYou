use super::settings::EqualizerConfig;

struct BiquadFilter {
    a0: f64, a1: f64, a2: f64,
    b1: f64, b2: f64,
    x1: f64, x2: f64,
    y1: f64, y2: f64,
}

impl BiquadFilter {
    fn new() -> Self {
        Self {
            a0: 1.0, a1: 0.0, a2: 0.0,
            b1: 0.0, b2: 0.0,
            x1: 0.0, x2: 0.0,
            y1: 0.0, y2: 0.0,
        }
    }

    fn set_peaking_eq(&mut self, sample_rate: f64, center_freq: f64, q: f64, db_gain: f64) {
        let w0 = 2.0 * std::f64::consts::PI * center_freq / sample_rate;
        let alpha = w0.sin() / (2.0 * q);
        let a = 10.0_f64.powf(db_gain / 40.0);

        let b0_raw = 1.0 + alpha * a;
        let b1_raw = -2.0 * w0.cos();
        let b2_raw = 1.0 - alpha * a;
        let a0_raw = 1.0 + alpha / a;
        let a1_raw = -2.0 * w0.cos();
        let a2_raw = 1.0 - alpha / a;

        self.a0 = b0_raw / a0_raw;
        self.a1 = b1_raw / a0_raw;
        self.a2 = b2_raw / a0_raw;
        self.b1 = a1_raw / a0_raw;
        self.b2 = a2_raw / a0_raw;
    }

    fn process(&mut self, x: f64) -> f64 {
        let y = self.a0 * x + self.a1 * self.x1 + self.a2 * self.x2 - self.b1 * self.y1 - self.b2 * self.y2;
        self.x2 = self.x1;
        self.x1 = x;
        self.y2 = self.y1;
        self.y1 = y;
        y
    }
}

pub struct EqualizerEffect {
    filters_ch1: Vec<BiquadFilter>,
    filters_ch2: Vec<BiquadFilter>,
    pre_amp_gain: f32,
    frequencies: [f64; 10],
}

impl EqualizerEffect {
    pub fn new() -> Self {
        let mut eq = Self {
            filters_ch1: (0..10).map(|_| BiquadFilter::new()).collect(),
            filters_ch2: (0..10).map(|_| BiquadFilter::new()).collect(),
            pre_amp_gain: 1.0,
            frequencies: [31.25, 62.5, 125.0, 250.0, 500.0, 1000.0, 2000.0, 4000.0, 8000.0, 16000.0],
        };
        eq.update_filters(&EqualizerConfig::default());
        eq
    }

    pub fn update_filters(&mut self, config: &EqualizerConfig) {
        self.pre_amp_gain = 10.0_f32.powf(config.pre_amp / 20.0);
        let sample_rate = 48000.0;
        for i in 0..10 {
            let gain = if i < config.gains.len() { config.gains[i] as f64 } else { 0.0 };
            self.filters_ch1[i].set_peaking_eq(sample_rate, self.frequencies[i], 1.0, gain);
            self.filters_ch2[i].set_peaking_eq(sample_rate, self.frequencies[i], 1.0, gain);
        }
    }

    pub fn process(&mut self, data: &mut [f32], channels: usize) {
        if channels == 1 {
            for sample in data.iter_mut() {
                let mut s = (*sample * self.pre_amp_gain) as f64;
                for i in 0..10 {
                    s = self.filters_ch1[i].process(s);
                }
                *sample = s as f32;
            }
        } else if channels == 2 {
            for (i, sample) in data.iter_mut().enumerate() {
                let mut s = (*sample * self.pre_amp_gain) as f64;
                if i % 2 == 0 {
                    for j in 0..10 { s = self.filters_ch1[j].process(s); }
                } else {
                    for j in 0..10 { s = self.filters_ch2[j].process(s); }
                }
                *sample = s as f32;
            }
        }
    }
}
