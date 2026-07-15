/// Delay-line comb filter dereverberation effect.
///
/// Uses a simple delayed subtraction approach to reduce room echo.
pub struct DereverbState {
    buffer_left: Vec<f32>,
    buffer_right: Vec<f32>,
    index: usize,
    delay: usize,
}

impl DereverbState {
    pub fn new(delay: usize) -> Self {
        Self {
            buffer_left: vec![0.0; delay],
            buffer_right: vec![0.0; delay],
            index: 0,
            delay,
        }
    }

    /// Apply dereverberation.
    ///
    /// `level` 0..100 controls the mix of dereverb effect.
    pub fn process(&mut self, data: &mut [f32], channels: usize, level: f32) {
        let mix = (level / 100.0).clamp(0.0, 1.0);
        if mix <= 0.0 || channels == 0 {
            return;
        }

        if channels == 1 {
            let buf = &mut self.buffer_left;
            for sample in data.iter_mut() {
                let delayed = buf[self.index];
                buf[self.index] = *sample;
                *sample = (*sample - delayed * mix).clamp(-1.0, 1.0);
                self.index += 1;
                if self.index >= self.delay {
                    self.index = 0;
                }
            }
        } else {
            let buf_l = &mut self.buffer_left;
            let buf_r = &mut self.buffer_right;
            let mut i = 0;
            while i + 1 < data.len() {
                let delayed_l = buf_l[self.index];
                let delayed_r = buf_r[self.index];
                buf_l[self.index] = data[i];
                buf_r[self.index] = data[i + 1];
                data[i] = (data[i] - delayed_l * mix).clamp(-1.0, 1.0);
                data[i + 1] = (data[i + 1] - delayed_r * mix).clamp(-1.0, 1.0);
                self.index += 1;
                if self.index >= self.delay {
                    self.index = 0;
                }
                i += 2;
            }
        }
    }
}
