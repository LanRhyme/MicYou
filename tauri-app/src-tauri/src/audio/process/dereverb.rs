/// Simple delay-line comb filter dereverberator.
pub struct DereverbEffect {
    buffer_left: Vec<f32>,
    buffer_right: Vec<f32>,
    index: usize,
}

impl DereverbEffect {
    const DELAY: usize = 480;

    pub fn new() -> Self {
        Self {
            buffer_left: vec![0.0; Self::DELAY],
            buffer_right: vec![0.0; Self::DELAY],
            index: 0,
        }
    }

    pub fn process(&mut self, data: &mut [f32], channels: usize, level: f32) {
        let mix = (level / 100.0).clamp(0.0, 1.0);
        if mix <= 0.0 || channels == 0 {
            return;
        }

        if channels == 1 {
            for sample in data.iter_mut() {
                let delayed = self.buffer_left[self.index];
                self.buffer_left[self.index] = *sample;
                *sample = (*sample - delayed * mix).clamp(-1.0, 1.0);
                self.index += 1;
                if self.index >= Self::DELAY {
                    self.index = 0;
                }
            }
        } else {
            let mut i = 0;
            while i + 1 < data.len() {
                let delayed_l = self.buffer_left[self.index];
                let delayed_r = self.buffer_right[self.index];
                self.buffer_left[self.index] = data[i];
                self.buffer_right[self.index] = data[i + 1];
                data[i] = (data[i] - delayed_l * mix).clamp(-1.0, 1.0);
                data[i + 1] = (data[i + 1] - delayed_r * mix).clamp(-1.0, 1.0);
                self.index += 1;
                if self.index >= Self::DELAY {
                    self.index = 0;
                }
                i += 2;
            }
        }
    }
}
