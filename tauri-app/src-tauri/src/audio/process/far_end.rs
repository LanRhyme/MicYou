// ─── Far-End Reference Ring Buffer for AEC ───────────────────────────────────
//
// Stores speaker output audio for use as far-end reference in AEC.
// Samples are stored as mono 48 kHz f32. Read is delayed by configurable
// amount to match acoustic + network round-trip latency.

/// Sample rate used by the AEC pipeline
pub const AEC_FS: u32 = 48000;

/// Circular buffer for far-end reference audio with configurable read delay.
///
/// The pipeline writes speaker output here after DSP processing.
/// The AEC processor reads delayed historical samples as far-end reference,
/// mimicking the acoustic + network round-trip latency (~200 ms).
///
/// All samples are stored as mono 48 kHz f32.
pub struct FarEndBuffer {
    buf: Vec<f32>,
    write_pos: usize,
    capacity: usize,
    delay_samples: usize,
    /// Total samples ever written (used for delay calculation)
    total_written: usize,
}

impl FarEndBuffer {
    /// Create a new far-end reference buffer.
    ///
    /// * `capacity_secs` - total storage duration in seconds
    /// * `delay_ms` - read delay in milliseconds (typical: 200)
    pub fn new(capacity_secs: f32, delay_ms: f32) -> Self {
        let capacity = (AEC_FS as f32 * capacity_secs) as usize;
        let delay_samples = (AEC_FS as f32 * delay_ms / 1000.0) as usize;
        Self {
            buf: vec![0.0; capacity],
            write_pos: 0,
            capacity,
            delay_samples: delay_samples.max(1),
            total_written: 0,
        }
    }

    /// Push interleaved multi-channel audio (channel-averaged to mono for storage).
    pub fn push_interleaved(&mut self, data: &[f32], channels: usize) {
        let ch = channels.max(1);
        for frame in data.chunks(ch) {
            let mono: f32 = frame.iter().sum::<f32>() / ch as f32;
            self.buf[self.write_pos] = mono;
            self.write_pos = (self.write_pos + 1) % self.capacity;
            self.total_written += 1;
        }
    }

    /// Read `count` delayed mono samples for AEC far-end reference.
    /// Returns zeros if the buffer hasn't filled enough yet.
    pub fn read_delayed(&self, count: usize) -> Vec<f32> {
        let mut result = Vec::with_capacity(count);
        let available = self.total_written.min(self.capacity);
        if available < self.delay_samples + count {
            return vec![0.0; count];
        }
        let read_start = (self.write_pos + self.capacity - self.delay_samples - count) % self.capacity;
        for i in 0..count {
            let idx = (read_start + i) % self.capacity;
            result.push(self.buf[idx]);
        }
        result
    }

    /// Reset buffer state for a new audio stream.
    pub fn reset(&mut self) {
        self.buf.fill(0.0);
        self.write_pos = 0;
        self.total_written = 0;
    }
}
