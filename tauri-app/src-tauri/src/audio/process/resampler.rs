pub struct ResamplerEffect {
    playback_ratio: f64,
    playback_ratio_integral: f64,
    pos: f64,
    prev_frame: Vec<f32>,
}

impl ResamplerEffect {
    pub fn new() -> Self {
        Self {
            playback_ratio: 1.0,
            playback_ratio_integral: 0.0,
            pos: 0.0,
            prev_frame: Vec::new(),
        }
    }

    pub fn update_playback_ratio(&mut self, queued_ms: f64) {
        let target_ms = 80.0;
        let error_ms = queued_ms - target_ms;

        let kp = 0.0008;
        let ki = 0.000008;
        let max_adjust = 0.03;

        let mut integral = self.playback_ratio_integral + error_ms * 0.01;
        integral = integral.clamp(-5000.0, 5000.0);
        self.playback_ratio_integral = integral;

        let adjust = (error_ms * kp + integral * ki).clamp(-max_adjust, max_adjust);
        self.playback_ratio = (1.0 + adjust).clamp(1.0 - max_adjust, 1.0 + max_adjust);
    }

    pub fn process(&mut self, input: &[f32], channels: usize) -> Vec<f32> {
        if (self.playback_ratio - 1.0).abs() < 0.00005 {
            return input.to_vec();
        }

        if channels == 0 || input.is_empty() {
            return input.to_vec();
        }

        let input_frames = input.len() / channels;
        if input_frames <= 1 {
            return input.to_vec();
        }

        if self.prev_frame.len() != channels {
            self.prev_frame = input[..channels].to_vec();
            self.pos = 1.0;
        }

        let effective_frames = input_frames + 1;
        let mut pos = self.pos;
        let estimated_out_frames = ((input_frames as f64 / self.playback_ratio) + 4.0) as usize;
        let mut output = Vec::with_capacity(estimated_out_frames * channels);

        let get_sample = |frame: usize, ch: usize| -> f32 {
            if frame == 0 {
                self.prev_frame[ch]
            } else {
                input[(frame - 1) * channels + ch]
            }
        };

        while (pos as usize) + 1 < effective_frames {
            let base = pos as usize;
            let frac = (pos - base as f64) as f32;

            for c in 0..channels {
                let s0 = if base > 0 { get_sample(base - 1, c) } else { get_sample(0, c) };
                let s1 = get_sample(base, c);
                let s2 = get_sample(base + 1, c);
                let s3 = if base + 2 < effective_frames { get_sample(base + 2, c) } else { s2 };

                let a = -0.5 * s0 + 1.5 * s1 - 1.5 * s2 + 0.5 * s3;
                let b = s0 - 2.5 * s1 + 2.0 * s2 - 0.5 * s3;
                let c_coeff = -0.5 * s0 + 0.5 * s2;
                let d = s1;

                let v = a * frac * frac * frac + b * frac * frac + c_coeff * frac + d;
                output.push(v);
            }

            pos += self.playback_ratio;
        }

        let last_frame_offset = (input_frames - 1) * channels;
        for c in 0..channels {
            self.prev_frame[c] = input[last_frame_offset + c];
        }

        self.pos = pos - input_frames as f64;

        output
    }
}
