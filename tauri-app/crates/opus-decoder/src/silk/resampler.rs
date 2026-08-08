//! SILK decoder-side resampler for internal-rate PCM to 48 kHz.

use crate::Error;

use super::resampler_private;

const RESAMPLER_MAX_BATCH_SIZE_MS: usize = 10;
const SILK_RESAMPLER_MAX_IIR_ORDER: usize = 6;
const SILK_RESAMPLER_MAX_DELAY: usize = 96;

/// Decoder-side SILK resampler state.
#[derive(Debug, Clone)]
pub(super) struct SilkResampler {
    /// Input sampling rate in Hz.
    fs_in_hz: u32,
    /// Output sampling rate in Hz.
    fs_out_hz: u32,
    /// Input rate in kHz.
    fs_in_khz: usize,
    /// One-block input batch size.
    batch_size: usize,
    /// Ratio step used by the FIR interpolator.
    inv_ratio_q16: i32,
    /// Decoder-mode delay compensation in samples.
    input_delay: usize,
    /// IIR/allpass state.
    s_iir: [i32; SILK_RESAMPLER_MAX_IIR_ORDER],
    /// FIR/history state.
    s_fir: [i16; resampler_private::fir_history_len()],
    /// Delay buffer used by the top-level dispatcher.
    delay_buf: [i16; SILK_RESAMPLER_MAX_DELAY],
}

impl SilkResampler {
    /// Create a decoder-mode SILK resampler.
    ///
    /// Params: `fs_in_hz` internal SILK rate and `fs_out_hz` API output rate.
    /// Returns: initialized resampler state for supported decoder paths.
    pub(super) fn new(fs_in_hz: u32, fs_out_hz: u32) -> Result<Self, Error> {
        let input_delay = match (fs_in_hz, fs_out_hz) {
            (8_000, 48_000) => 0,
            (12_000, 48_000) => 4,
            (16_000, 48_000) => 7,
            _ => return Err(Error::NotImplemented),
        };
        let fs_in_khz = (fs_in_hz / 1000) as usize;
        let batch_size = fs_in_khz * RESAMPLER_MAX_BATCH_SIZE_MS;
        let mut inv_ratio_q16 = ((((fs_in_hz as i64) << 15) / fs_out_hz as i64) << 2) as i32;
        while smulww(inv_ratio_q16, fs_out_hz as i32) < ((fs_in_hz as i32) << 1) {
            inv_ratio_q16 += 1;
        }

        Ok(Self {
            fs_in_hz,
            fs_out_hz,
            fs_in_khz,
            batch_size,
            inv_ratio_q16,
            input_delay,
            s_iir: [0; SILK_RESAMPLER_MAX_IIR_ORDER],
            s_fir: [0; resampler_private::fir_history_len()],
            delay_buf: [0; SILK_RESAMPLER_MAX_DELAY],
        })
    }

    /// Check whether the current state matches a rate pair.
    ///
    /// Params: `fs_in_hz` and `fs_out_hz`.
    /// Returns: true when the resampler can be reused as-is.
    pub(super) fn matches(&self, fs_in_hz: u32, fs_out_hz: u32) -> bool {
        self.fs_in_hz == fs_in_hz && self.fs_out_hz == fs_out_hz
    }

    /// Resample internal-rate PCM to API-rate PCM.
    ///
    /// Params: immutable `input` PCM at internal rate and mutable `output` PCM at API rate.
    /// Returns: number of output samples written.
    pub(super) fn process(&mut self, input: &[i16], output: &mut [i16]) -> usize {
        debug_assert!(input.len() >= self.fs_in_khz);

        let n_samples = self.fs_in_khz - self.input_delay;
        self.delay_buf[self.input_delay..self.fs_in_khz].copy_from_slice(&input[..n_samples]);

        let mut written = 0usize;
        let first_block = self.delay_buf[..self.fs_in_khz].to_vec();
        written += self.process_iir_fir(&first_block, &mut output[written..]);
        let second_len = input.len().saturating_sub(self.fs_in_khz);
        written += self.process_iir_fir(
            &input[n_samples..n_samples + second_len],
            &mut output[written..],
        );

        if self.input_delay > 0 {
            let start = input.len() - self.input_delay;
            self.delay_buf[..self.input_delay].copy_from_slice(&input[start..]);
        }
        written
    }

    /// Run the IIR+FIR upsampling core on one contiguous input slice.
    ///
    /// Params: immutable `input` block and mutable `output` buffer.
    /// Returns: number of output samples written.
    fn process_iir_fir(&mut self, input: &[i16], output: &mut [i16]) -> usize {
        if input.is_empty() {
            return 0;
        }

        let history = resampler_private::fir_history_len();
        let mut total_written = 0usize;
        let mut in_offset = 0usize;

        while in_offset < input.len() {
            let n_samples_in = (input.len() - in_offset).min(self.batch_size);
            let mut buf = vec![0i16; history + (2 * n_samples_in) + history];
            buf[..history].copy_from_slice(&self.s_fir);
            resampler_private::up2_hq(
                &mut self.s_iir,
                &mut buf[history..history + 2 * n_samples_in],
                &input[in_offset..in_offset + n_samples_in],
                n_samples_in,
            );

            let max_index_q16 = (n_samples_in as i32) << 17;
            let written = resampler_private::iir_fir_interpol(
                &mut output[total_written..],
                &buf,
                max_index_q16,
                self.inv_ratio_q16,
            );
            total_written += written;

            let src_start = 2 * n_samples_in;
            self.s_fir
                .copy_from_slice(&buf[src_start..src_start + history]);
            in_offset += n_samples_in;
        }

        total_written
    }

    /// Return the number of output samples for a given input length.
    ///
    /// Params: input length in samples.
    /// Returns: exact output sample count for the configured ratio.
    pub(super) fn output_len(&self, input_len: usize) -> usize {
        ((input_len as u64) * self.fs_out_hz as u64 / self.fs_in_hz as u64) as usize
    }
}

/// Approximate signed `((a32 * b32) >> 16)`.
///
/// Params: signed `a32` and `b32`.
/// Returns: signed Q16-scaled product.
fn smulww(a32: i32, b32: i32) -> i32 {
    ((a32 as i64 * b32 as i64) >> 16) as i32
}
