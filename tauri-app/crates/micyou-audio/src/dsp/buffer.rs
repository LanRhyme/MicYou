/// Manages the output ring-buffer level to prevent underruns and overruns.
pub struct BufferLevelManager {
    underrun_count: u32,
    overrun_count: u32,
}

impl BufferLevelManager {
    pub fn new() -> Self {
        Self {
            underrun_count: 0,
            overrun_count: 0,
        }
    }

    /// Process audio data with buffer-level-aware gain adjustment.
    /// - If queued_ms is very low, apply a small gain reduction to prevent underruns.
    /// - If queued_ms is very high, drop samples gracefully to prevent overruns.
    pub fn process(
        &mut self,
        data: &[f32],
        channels: usize,
        queued_ms: f64,
        output: &mut Vec<f32>,
    ) {
        output.clear();

        if queued_ms < 10.0 {
            self.underrun_count += 1;
            self.overrun_count = 0;
            // Slow down slightly: repeat some samples
            let repeat_every = if queued_ms < 5.0 { 200 } else { 400 };
            for (i, &sample) in data.iter().enumerate() {
                output.push(sample);
                if channels > 0 && (i % (channels * repeat_every) == 0) {
                    output.push(sample);
                }
            }
        } else if queued_ms > 200.0 {
            self.overrun_count += 1;
            self.underrun_count = 0;
            // Speed up: drop occasional samples
            let drop_every = if queued_ms > 400.0 { 100 } else { 200 };
            for (i, &sample) in data.iter().enumerate() {
                if channels > 0 && (i % (channels * drop_every) != 0) {
                    output.push(sample);
                }
            }
            if output.is_empty() {
                output.extend_from_slice(data);
            }
        } else {
            self.underrun_count = 0;
            self.overrun_count = 0;
            output.extend_from_slice(data);
        }
    }
}
