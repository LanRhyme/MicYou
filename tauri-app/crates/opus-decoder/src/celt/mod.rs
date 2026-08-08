//! CELT decoder for Opus CELT-only and hybrid packets.
//!
//! The implementation stays structurally close to libopus to preserve
//! conformance while keeping the decode path self-contained in Rust.
#![allow(
    unused_variables,
    unused_assignments,
    dead_code,
    clippy::too_many_arguments,
    clippy::needless_borrow,
    clippy::get_first,
    clippy::needless_range_loop,
    clippy::unnecessary_cast
)]

use crate::Error;
use crate::entropy::EcDec;
use core::sync::atomic::{AtomicUsize, Ordering};

mod bands;
mod cwrs;
mod kiss_fft;
mod laplace;
mod mdct;
mod modes;
mod quant_bands;
mod rate;
mod vq;

const OVERLAP_48K_20MS: usize = 120;
const NBANDS_48K_20MS: usize = 21;
const LOG_ENERGY_FLOOR_DB: f32 = -28.0;
const BITRES: i32 = 3;
const COMBFILTER_MINPERIOD: usize = 15;
const DECODE_BUFFER_SIZE: usize = 2048;
const COMB_FILTER_GAINS: [[f32; 3]; 3] = [
    [0.306_640_62, 0.217_041_02, 0.129_638_67],
    [0.463_867_2, 0.268_066_4, 0.0],
    [0.799_804_7, 0.100_097_66, 0.0],
];
const TRIM_ICDF: [u8; 11] = [126, 124, 119, 109, 87, 41, 19, 9, 4, 2, 0];
const SPREAD_ICDF: [u8; 4] = [25, 23, 2, 0];
const TAPSET_ICDF: [u8; 3] = [2, 1, 0];
const TF_SELECT_TABLE: [[i8; 8]; 4] = [
    [0, -1, 0, -1, 0, -1, 0, -1],
    [0, -1, 0, -2, 1, 0, 1, -1],
    [0, -2, 0, -3, 2, 0, 1, -1],
    [0, -2, 0, -3, 3, 0, 1, -1],
];
static TRACE_PACKET_CALL_IDX: AtomicUsize = AtomicUsize::new(0);

/// Result of decoding one CELT frame.
#[derive(Debug, Clone, Copy)]
pub(crate) struct CeltFrameDecode {
    /// Number of decoded samples per channel.
    pub samples_per_channel: usize,
    /// Indicates transient frame path usage in CELT.
    pub is_transient: bool,
}

#[derive(Debug, Clone)]
pub(crate) struct CeltDecoder {
    fs_hz: u32,
    channels: u8,
    start_band: usize,
    mode: &'static modes::CeltMode,
    mdct: mdct::MdctBackward,
    mdct_480: mdct::MdctBackward,
    mdct_240: mdct::MdctBackward,
    mdct_short: mdct::MdctBackward,
    decode_mem: Vec<f32>,
    decode_mem_right: Vec<f32>,
    prev_energy: Vec<f32>,
    old_log_energy: Vec<f32>,
    old_log_energy2: Vec<f32>,
    background_log_energy: Vec<f32>,
    deemph_mem: Vec<f32>,
    end_band_override: Option<usize>,
    postfilter_period: i32,
    postfilter_period_old: i32,
    postfilter_gain: f32,
    postfilter_gain_old: f32,
    postfilter_tapset: usize,
    postfilter_tapset_old: usize,
    final_range: u32,
    rng_seed: u32,
    last_split_count: usize,
    loss_count: u32,
}

impl CeltDecoder {
    /// Create CELT decoder state.
    ///
    /// Params: `fs_hz` is output sample rate and `channels` is output channels.
    /// Returns: initialized CELT decoder state.
    pub fn new(fs_hz: u32, channels: u8) -> Self {
        let mode = modes::mode48000_960_120();
        let state_ch = 2usize;
        Self {
            fs_hz,
            channels,
            start_band: 0,
            mode,
            mdct: mdct::MdctBackward::new(1920, mode.overlap),
            mdct_480: mdct::MdctBackward::new(960, mode.overlap),
            mdct_240: mdct::MdctBackward::new(480, mode.overlap),
            mdct_short: mdct::MdctBackward::new(240, mode.overlap),
            decode_mem: vec![0.0; DECODE_BUFFER_SIZE + mode.overlap],
            decode_mem_right: vec![0.0; DECODE_BUFFER_SIZE + mode.overlap],
            prev_energy: vec![0.0; NBANDS_48K_20MS * state_ch],
            old_log_energy: vec![LOG_ENERGY_FLOOR_DB; NBANDS_48K_20MS * state_ch],
            old_log_energy2: vec![LOG_ENERGY_FLOOR_DB; NBANDS_48K_20MS * state_ch],
            background_log_energy: vec![0.0; NBANDS_48K_20MS * state_ch],
            deemph_mem: vec![0.0; state_ch],
            end_band_override: None,
            postfilter_period: 0,
            postfilter_period_old: 0,
            postfilter_gain: 0.0,
            postfilter_gain_old: 0.0,
            postfilter_tapset: 0,
            postfilter_tapset_old: 0,
            final_range: 0,
            rng_seed: 0,
            last_split_count: 0,
            loss_count: 0,
        }
    }

    /// Reset decoder state between streams.
    ///
    /// Params: none.
    /// Returns: nothing.
    pub fn reset(&mut self) {
        self.decode_mem.fill(0.0);
        self.decode_mem_right.fill(0.0);
        self.prev_energy.fill(0.0);
        self.old_log_energy.fill(LOG_ENERGY_FLOOR_DB);
        self.old_log_energy2.fill(LOG_ENERGY_FLOOR_DB);
        self.background_log_energy.fill(0.0);
        self.deemph_mem.fill(0.0);
        self.end_band_override = None;
        self.postfilter_period = 0;
        self.postfilter_period_old = 0;
        self.postfilter_gain = 0.0;
        self.postfilter_gain_old = 0.0;
        self.postfilter_tapset = 0;
        self.postfilter_tapset_old = 0;
        self.final_range = 0;
        self.rng_seed = 0;
        self.last_split_count = 0;
        self.start_band = 0;
        self.loss_count = 0;
    }

    /// Clear CELT PLC loss history before a good decode.
    ///
    /// Params: none.
    /// Returns: nothing.
    pub fn reset_loss_count(&mut self) {
        self.loss_count = 0;
    }

    /// Return the CELT output decimation factor for the configured sample rate.
    ///
    /// Params: none.
    /// Returns: integer 48 kHz downsample factor for the public output rate.
    fn downsample_factor(&self) -> Result<usize, Error> {
        match self.fs_hz {
            48_000 => Ok(1),
            24_000 => Ok(2),
            16_000 => Ok(3),
            12_000 => Ok(4),
            8_000 => Ok(6),
            _ => Err(Error::NotImplemented),
        }
    }

    /// Convert a 48 kHz CELT frame size to output samples per channel.
    ///
    /// Params: internal `frame_size_48k`.
    /// Returns: per-channel sample count at the configured public output rate.
    fn output_frame_size(&self, frame_size_48k: usize) -> Result<usize, Error> {
        let downsample = self.downsample_factor()?;
        if frame_size_48k % downsample != 0 {
            return Err(Error::NotImplemented);
        }
        Ok(frame_size_48k / downsample)
    }

    /// Write one deemphasized CELT channel into the interleaved i16 output buffer.
    ///
    /// Params: mutable interleaved `out`, per-channel synthesized `ch_synth`,
    /// channel index `ch`, and additive flag `accum`.
    /// Returns: `Ok(())` on success or `Error::NotImplemented` for unsupported
    /// output rates.
    fn write_output_channel_i16(
        &self,
        out: &mut [i16],
        ch_synth: &[f32],
        ch: usize,
        accum: bool,
    ) -> Result<(), Error> {
        let downsample = self.downsample_factor()?;
        let output_samples = self.output_frame_size(ch_synth.len())?;
        for i in 0..output_samples {
            let sample = Self::float_to_i16(ch_synth[i * downsample]);
            let out_idx = i * self.channels as usize + ch;
            out[out_idx] = if accum {
                out[out_idx].saturating_add(sample)
            } else {
                sample
            };
        }
        Ok(())
    }

    /// Derive the highest active CELT band from saved energy history.
    ///
    /// Params: active output `channels`.
    /// Returns: exclusive upper band index for PLC shaping.
    fn plc_end_band(&self, channels: usize) -> usize {
        let active_channels = channels.clamp(1, 2);
        let nb_ebands = self.mode.nb_ebands;
        for band in (0..nb_ebands).rev() {
            for ch in 0..active_channels {
                if self.prev_energy[ch * nb_ebands + band] > LOG_ENERGY_FLOOR_DB {
                    return band + 1;
                }
            }
        }
        0
    }

    /// Called when a CELT packet is lost (packet=[] or fec path).
    ///
    /// Params: per-channel `frame_size` and output `channels`.
    /// Returns: interleaved concealed floating PCM.
    pub fn decode_lost(&mut self, frame_size: usize, channels: usize) -> Vec<f32> {
        let downsample = match self.downsample_factor() {
            Ok(value) => value,
            Err(_) => return Vec::new(),
        };
        let active_channels = channels.clamp(1, self.channels as usize).min(2);
        let mut pcm = vec![0.0f32; frame_size * active_channels];
        let Some(frame_size_48k) = frame_size.checked_mul(downsample) else {
            return pcm;
        };
        let lm = match frame_size_48k {
            120 => 0,
            240 => 1,
            480 => 2,
            960 => 3,
            _ => return pcm,
        };

        let nb_ebands = self.mode.nb_ebands;
        let end = self.plc_end_band(active_channels);
        if end == 0 {
            self.loss_count = self.loss_count.saturating_add(1);
            return pcm;
        }

        let mut left_energy = self.prev_energy[..nb_ebands].to_vec();
        let mut right_energy = if active_channels == 2 {
            self.prev_energy[nb_ebands..2 * nb_ebands].to_vec()
        } else {
            left_energy.clone()
        };
        if self.loss_count > 0 {
            for band in 0..end {
                left_energy[band] = (left_energy[band] - 6.0).max(self.background_log_energy[band]);
                if active_channels == 2 {
                    right_energy[band] = (right_energy[band] - 6.0)
                        .max(self.background_log_energy[nb_ebands + band]);
                }
            }
        }
        for band in end..nb_ebands {
            left_energy[band] = LOG_ENERGY_FLOOR_DB;
            right_energy[band] = LOG_ENERGY_FLOOR_DB;
        }

        let mut mdct_left = vec![0.0f32; frame_size_48k];
        for band in 0..end {
            let start = (self.mode.e_bands[band] as usize) << lm;
            let band_len =
                (self.mode.e_bands[band + 1] as usize - self.mode.e_bands[band] as usize) << lm;
            for value in &mut mdct_left[start..start + band_len] {
                self.rng_seed = bands::celt_lcg_rand(self.rng_seed);
                *value = ((self.rng_seed >> 16) as i16) as f32;
            }
            vq::renormalise_vector(&mut mdct_left[start..start + band_len], 1.0);
        }
        let mut denorm_left = vec![0.0f32; frame_size_48k];
        bands::denormalise_bands(
            self.mode,
            &mdct_left,
            &mut denorm_left,
            &left_energy,
            0,
            end,
            lm,
            false,
        );
        if Self::synthesise_channel_to_mem(
            &denorm_left,
            frame_size_48k,
            lm,
            false,
            self.mode.window,
            self.mode.overlap,
            &self.mdct,
            &self.mdct_480,
            &self.mdct_240,
            &self.mdct_short,
            &mut self.decode_mem,
        )
        .is_err()
        {
            return pcm;
        }

        if active_channels == 2 {
            let mut mdct_right = vec![0.0f32; frame_size_48k];
            for band in 0..end {
                let start = (self.mode.e_bands[band] as usize) << lm;
                let band_len =
                    (self.mode.e_bands[band + 1] as usize - self.mode.e_bands[band] as usize) << lm;
                for value in &mut mdct_right[start..start + band_len] {
                    self.rng_seed = bands::celt_lcg_rand(self.rng_seed);
                    *value = ((self.rng_seed >> 16) as i16) as f32;
                }
                vq::renormalise_vector(&mut mdct_right[start..start + band_len], 1.0);
            }
            let mut denorm_right = vec![0.0f32; frame_size_48k];
            bands::denormalise_bands(
                self.mode,
                &mdct_right,
                &mut denorm_right,
                &right_energy,
                0,
                end,
                lm,
                false,
            );
            if Self::synthesise_channel_to_mem(
                &denorm_right,
                frame_size_48k,
                lm,
                false,
                self.mode.window,
                self.mode.overlap,
                &self.mdct,
                &self.mdct_480,
                &self.mdct_240,
                &self.mdct_short,
                &mut self.decode_mem_right,
            )
            .is_err()
            {
                return pcm;
            }
        }

        self.postfilter_period = 0;
        self.postfilter_period_old = 0;
        self.postfilter_gain = 0.0;
        self.postfilter_gain_old = 0.0;
        self.postfilter_tapset = 0;
        self.postfilter_tapset_old = 0;
        self.old_log_energy2[..nb_ebands].copy_from_slice(&self.old_log_energy[..nb_ebands]);
        self.old_log_energy[..nb_ebands].copy_from_slice(&left_energy);
        self.prev_energy[..nb_ebands].copy_from_slice(&left_energy);
        self.old_log_energy2[nb_ebands..2 * nb_ebands]
            .copy_from_slice(&self.old_log_energy[nb_ebands..2 * nb_ebands]);
        if active_channels == 2 {
            self.old_log_energy[nb_ebands..2 * nb_ebands].copy_from_slice(&right_energy);
            self.prev_energy[nb_ebands..2 * nb_ebands].copy_from_slice(&right_energy);
        } else {
            self.old_log_energy[nb_ebands..2 * nb_ebands].copy_from_slice(&left_energy);
            self.prev_energy[nb_ebands..2 * nb_ebands].copy_from_slice(&left_energy);
        }

        let out_start = Self::out_start(frame_size_48k);
        let mut left = self.decode_mem[out_start..out_start + frame_size_48k].to_vec();
        self.apply_deemph(0, &mut left);
        for i in 0..frame_size {
            pcm[i * active_channels] = left[i * downsample];
        }
        if active_channels == 2 {
            let mut right = self.decode_mem_right[out_start..out_start + frame_size_48k].to_vec();
            self.apply_deemph(1, &mut right);
            for i in 0..frame_size {
                pcm[i * 2 + 1] = right[i * downsample];
            }
        }

        self.final_range = 0;
        self.last_split_count = 0;
        self.loss_count = self.loss_count.saturating_add(1);
        pcm
    }

    /// Return CELT range coder final state.
    ///
    /// Params: none.
    /// Returns: range coder final value for last decoded frame.
    pub fn final_range(&self) -> u32 {
        self.final_range
    }

    /// Return the MDCT overlap window used for transition fades.
    ///
    /// Params: none.
    /// Returns: immutable CELT synthesis window coefficients.
    pub(crate) fn window(&self) -> &[f32] {
        self.mode.window
    }

    /// Return deemphasis memory for the selected channel.
    ///
    /// Params: `ch` channel index.
    /// Returns: deemphasis memory value for that channel.
    pub fn deemph_mem(&self, ch: usize) -> f32 {
        self.deemph_mem[ch]
    }

    /// Return absolute sum of the left decode memory.
    ///
    /// Params: none.
    /// Returns: sum of absolute sample values in the left decode buffer.
    pub fn decode_mem_abs_sum(&self) -> f32 {
        self.decode_mem.iter().map(|x| x.abs()).sum()
    }

    /// Return absolute sum of the right decode memory.
    ///
    /// Params: none.
    /// Returns: sum of absolute sample values in the right decode buffer.
    pub fn decode_mem_right_abs_sum(&self) -> f32 {
        self.decode_mem_right.iter().map(|x| x.abs()).sum()
    }

    /// Return leading CELT band energies for debug comparisons.
    ///
    /// Params: `count` is the number of entries to copy.
    /// Returns: copied prefix of `prev_energy` with at most `count` values.
    pub fn prev_energy_head(&self, count: usize) -> Vec<f32> {
        self.prev_energy[..self.prev_energy.len().min(count)].to_vec()
    }

    /// Return split count captured for last decoded frame.
    ///
    /// Params: none.
    /// Returns: recursive split count.
    pub fn last_split_count(&self) -> usize {
        self.last_split_count
    }

    /// Override the lowest coded band for the next CELT decode.
    ///
    /// Params: `band` lower band index to decode from.
    /// Returns: nothing; the index is clamped to the valid mode range.
    pub fn set_start_band(&mut self, band: usize) {
        self.start_band = band.min(self.mode.nb_ebands.saturating_sub(1));
    }

    /// Override the highest coded band for the next CELT decode.
    ///
    /// Params: `band` exclusive upper band index.
    /// Returns: nothing; the index is clamped to the valid mode range.
    pub fn set_end_band(&mut self, band: usize) {
        self.end_band_override = Some(band.clamp(1, self.mode.nb_ebands));
    }

    /// Clear any explicit CELT end-band override.
    ///
    /// Params: none.
    /// Returns: nothing; future decodes use config-derived band end.
    pub fn clear_end_band(&mut self) {
        self.end_band_override = None;
    }

    /// Return the currently configured CELT start band.
    ///
    /// Params: none.
    /// Returns: active lower coded band index.
    pub(crate) fn debug_start_band(&self) -> usize {
        self.start_band
    }

    /// Return the CELT end band implied by a TOC config.
    ///
    /// Params: packet `config`.
    /// Returns: upper coded band index for the current mode.
    pub(crate) fn debug_end_band(&self, config: u8) -> usize {
        self.end_band_override
            .unwrap_or_else(|| bandwidth_end(config))
    }

    /// Check whether packet should emit targeted debug logs.
    ///
    /// Params: `packet_idx` absolute packet index in current test run.
    /// Returns: true when packet is in onset/hotspot probe set.
    fn should_trace_debug_packet(packet_idx: usize) -> bool {
        let _ = packet_idx;
        false
    }

    /// Append one NDJSON debug entry to the session log.
    ///
    /// Params: `run_id` run label, `hypothesis_id` tested hypothesis, `location`
    /// source marker, `message` event name, and `data_json` object string.
    /// Returns: nothing.
    fn append_debug_log(
        run_id: &str,
        hypothesis_id: &str,
        location: &str,
        message: &str,
        data_json: &str,
    ) {
        let _ = (run_id, hypothesis_id, location, message, data_json);
    }

    /// Append one NDJSON debug entry to the active runtime debug session.
    ///
    /// Params: run metadata and JSON payload string.
    /// Returns: nothing.
    fn append_runtime_debug_log(
        run_id: &str,
        hypothesis_id: &str,
        location: &str,
        message: &str,
        data_json: &str,
    ) {
        let _ = (run_id, hypothesis_id, location, message, data_json);
    }

    /// Apply first-order deemphasis to one channel buffer in-place.
    ///
    /// Params: `ch` is channel index, `samples` are per-channel samples.
    /// Returns: nothing.
    fn apply_deemph(&mut self, ch: usize, samples: &mut [f32]) {
        let coef = self.mode.preemph[0];
        let mut mem = self.deemph_mem[ch];
        for x in samples.iter_mut() {
            // libopus recurrence:
            // tmp = x + m; m = coef * tmp; output = tmp
            let tmp = *x + mem;
            mem = coef * tmp;
            *x = tmp;
        }
        self.deemph_mem[ch] = mem;
    }

    /// Apply CELT pitch comb postfilter in-place on decoder memory.
    ///
    /// Params: `buf` is decode memory, `start` is frame start in `buf`, `t0`/`t1`
    /// are old/new periods, `n` is frame segment length, `g0`/`g1` are old/new
    /// gains, `tapset0`/`tapset1` select tap weights, `window` is overlap window,
    /// `overlap` is overlap length.
    /// Returns: nothing.
    fn comb_filter_in_place(
        buf: &mut [f32],
        start: usize,
        t0: usize,
        t1: usize,
        n: usize,
        g0: f32,
        g1: f32,
        tapset0: usize,
        tapset1: usize,
        window: &[f32],
        overlap: usize,
    ) {
        if n == 0 || (g0 == 0.0 && g1 == 0.0) {
            return;
        }
        let period0 = t0.max(COMBFILTER_MINPERIOD);
        let period1 = t1.max(COMBFILTER_MINPERIOD);
        let ts0 = tapset0.min(2);
        let ts1 = tapset1.min(2);
        let g00 = g0 * COMB_FILTER_GAINS[ts0][0];
        let g01 = g0 * COMB_FILTER_GAINS[ts0][1];
        let g02 = g0 * COMB_FILTER_GAINS[ts0][2];
        let g10 = g1 * COMB_FILTER_GAINS[ts1][0];
        let g11 = g1 * COMB_FILTER_GAINS[ts1][1];
        let g12 = g1 * COMB_FILTER_GAINS[ts1][2];
        let mut overlap_len = overlap.min(n);
        if g0 == g1 && period0 == period1 && ts0 == ts1 {
            overlap_len = 0;
        }

        let mut x1 = buf[start - period1 + 1];
        let mut x2 = buf[start - period1];
        let mut x3 = buf[start - period1 - 1];
        let mut x4 = buf[start - period1 - 2];

        for i in 0..overlap_len {
            let x0 = buf[start + i - period1 + 2];
            let f = window[i] * window[i];
            let one_minus_f = 1.0 - f;
            let mut y = buf[start + i];
            y += (one_minus_f * g00) * buf[start + i - period0];
            y +=
                (one_minus_f * g01) * (buf[start + i - period0 + 1] + buf[start + i - period0 - 1]);
            y +=
                (one_minus_f * g02) * (buf[start + i - period0 + 2] + buf[start + i - period0 - 2]);
            y += (f * g10) * x2;
            y += (f * g11) * (x1 + x3);
            y += (f * g12) * (x0 + x4);
            buf[start + i] = y;
            x4 = x3;
            x3 = x2;
            x2 = x1;
            x1 = x0;
        }

        if g1 == 0.0 {
            return;
        }
        for i in overlap_len..n {
            let x0 = buf[start + i - period1 + 2];
            let y = buf[start + i] + g10 * x2 + g11 * (x1 + x3) + g12 * (x0 + x4);
            buf[start + i] = y;
            x4 = x3;
            x3 = x2;
            x2 = x1;
            x1 = x0;
        }
    }

    /// Convert one floating sample to i16 with saturation.
    ///
    /// Params: `x` is floating PCM sample.
    /// Returns: saturated i16 PCM.
    fn float_to_i16(x: f32) -> i16 {
        let v = x.round().clamp(i16::MIN as f32, i16::MAX as f32);
        v as i16
    }

    /// Return frame output start offset inside `decode_mem`.
    ///
    /// Params: `frame_size_48k` is frame size in 48 kHz samples.
    /// Returns: start index for current synthesis output in `decode_mem`.
    fn out_start(frame_size_48k: usize) -> usize {
        DECODE_BUFFER_SIZE - frame_size_48k
    }

    /// Return overlap tail slice stored in unified decode memory.
    ///
    /// Params: none.
    /// Returns: immutable overlap tail `[DECODE_BUFFER_SIZE..DECODE_BUFFER_SIZE+overlap)`.
    fn overlap_tail(&self) -> &[f32] {
        let start = DECODE_BUFFER_SIZE;
        let end = start + self.mode.overlap;
        &self.decode_mem[start..end]
    }

    /// Run CELT synthesis for one channel into the provided decode-state buffer.
    ///
    /// Params: denormalized spectrum, frame shape context and mutable channel state.
    /// Returns: `Ok(())` on success, `Error::NotImplemented` for unsupported sizes.
    fn synthesise_channel_to_mem(
        denorm: &[f32],
        frame_size_48k: usize,
        lm: usize,
        is_transient: bool,
        window: &[f32],
        overlap: usize,
        mdct: &mdct::MdctBackward,
        mdct_480: &mdct::MdctBackward,
        mdct_240: &mdct::MdctBackward,
        mdct_short: &mdct::MdctBackward,
        decode_mem: &mut [f32],
    ) -> Result<(), Error> {
        let decode_len = DECODE_BUFFER_SIZE + overlap;
        decode_mem.copy_within(frame_size_48k..decode_len, 0);
        let out_start = Self::out_start(frame_size_48k);
        if is_transient {
            let m_blocks = 1usize << lm;
            let short_len = frame_size_48k / m_blocks;
            let mut short_coeffs = vec![0.0f32; short_len];
            for b in 0..m_blocks {
                let out_offset = out_start + b * short_len;
                for j in 0..short_len {
                    short_coeffs[j] = denorm[j * m_blocks + b];
                }
                mdct_short
                    .backward(
                        &short_coeffs,
                        window,
                        &mut decode_mem[out_offset..out_offset + short_len + overlap],
                    )
                    .map_err(|_| Error::NotImplemented)?;
            }
        } else {
            let mdct_impl = match frame_size_48k {
                120 => mdct_short,
                240 => mdct_240,
                480 => mdct_480,
                960 => mdct,
                _ => return Err(Error::NotImplemented),
            };
            mdct_impl
                .backward(
                    denorm,
                    window,
                    &mut decode_mem[out_start..out_start + frame_size_48k + overlap],
                )
                .map_err(|_| Error::NotImplemented)?;
        }
        Ok(())
    }

    /// Decode a single CELT frame.
    ///
    /// `frame_size_48k` is samples-per-channel at 48 kHz (120/240/480/960).
    pub fn decode_frame(
        &mut self,
        frame: &[u8],
        frame_size_48k: usize,
        config: u8,
        packet_channels: u8,
        parent_packet_idx: usize,
        out: &mut [i16],
    ) -> Result<CeltFrameDecode, Error> {
        let mut ec = EcDec::new(frame);
        self.decode_frame_with_ec(
            frame,
            &mut ec,
            frame_size_48k,
            config,
            packet_channels,
            parent_packet_idx,
            out,
            false,
        )
    }

    /// Decode a single CELT frame using a shared entropy decoder.
    ///
    /// Params: source `frame`, shared mutable `ec`, `frame_size_48k`, TOC `config`,
    /// coded `packet_channels`, parent `packet_idx`, mutable `out`, and `accum`
    /// which adds decoded PCM on top of existing samples for hybrid mode.
    /// Returns: decoded CELT frame metadata.
    pub(crate) fn decode_frame_with_ec(
        &mut self,
        frame: &[u8],
        mut ec: &mut EcDec<'_>,
        frame_size_48k: usize,
        config: u8,
        packet_channels: u8,
        parent_packet_idx: usize,
        out: &mut [i16],
        accum: bool,
    ) -> Result<CeltFrameDecode, Error> {
        let frame_call_idx = TRACE_PACKET_CALL_IDX.fetch_add(1, Ordering::SeqCst);
        let _ = parent_packet_idx;
        let packet_idx = usize::MAX;
        let output_samples = self.output_frame_size(frame_size_48k)?;
        if !matches!(self.channels, 1 | 2) {
            return Err(Error::NotImplemented);
        }
        if !matches!(frame_size_48k, 120 | 240 | 480 | 960) {
            return Err(Error::NotImplemented);
        }
        let needed = output_samples * self.channels as usize;
        if out.len() < needed {
            return Err(Error::OutputTooSmall {
                needed,
                got: out.len(),
            });
        }

        let trace_this_packet = false;
        let debug_packet = false;
        let coded_channels = packet_channels.clamp(1, 2) as usize;
        if debug_packet {
            let overlap_abs: f32 = self.overlap_tail().iter().map(|x| x.abs()).sum();
            let data = format!(
                "{{\"packet_idx\":{},\"frame_size_48k\":{},\"config\":{},\"coded_channels\":{},\"overlap_abs_in\":{},\"deemph_mem0_in\":{},\"pf_old_period\":{},\"pf_old_gain\":{},\"pf_old_tapset\":{},\"pf_cur_period\":{},\"pf_cur_gain\":{},\"pf_cur_tapset\":{}}}",
                packet_idx,
                frame_size_48k,
                config,
                coded_channels,
                overlap_abs,
                self.deemph_mem[0],
                self.postfilter_period_old,
                self.postfilter_gain_old,
                self.postfilter_tapset_old,
                self.postfilter_period,
                self.postfilter_gain,
                self.postfilter_tapset
            );
            // #region agent log
            Self::append_debug_log(
                "run-onset-map-v1",
                "H1",
                "crates/opus-decoder/src/celt/mod.rs:decode_frame",
                "state_in",
                &data,
            );
            // #endregion
        }
        if packet_idx == 6 {
            let overlap_tail = self.overlap_tail();
            let ovl_abs_sum: f32 = overlap_tail.iter().map(|x| x.abs()).sum();
            let data = format!(
                "{{\"packet_idx\":{},\"stage\":\"entry\",\"overlap_abs_sum\":{},\"overlap_first4\":[{:.9},{:.9},{:.9},{:.9}],\"pf_old_period\":{},\"pf_old_gain\":{},\"pf_old_tapset\":{},\"pf_cur_period\":{},\"pf_cur_gain\":{},\"pf_cur_tapset\":{}}}",
                packet_idx,
                ovl_abs_sum,
                overlap_tail.first().copied().unwrap_or(0.0),
                overlap_tail.get(1).copied().unwrap_or(0.0),
                overlap_tail.get(2).copied().unwrap_or(0.0),
                overlap_tail.get(3).copied().unwrap_or(0.0),
                self.postfilter_period_old,
                self.postfilter_gain_old,
                self.postfilter_tapset_old,
                self.postfilter_period,
                self.postfilter_gain,
                self.postfilter_tapset
            );
            // #region agent log
            Self::append_runtime_debug_log(
                "run-pkt6-overlap-entry-v1",
                "H61",
                "crates/opus-decoder/src/celt/mod.rs:decode_frame",
                "rust_pkt6_overlap_entry",
                &data,
            );
            // #endregion
        }
        if coded_channels == 1 {
            let nb = self.mode.nb_ebands;
            for i in 0..nb {
                self.prev_energy[i] = self.prev_energy[i].max(self.prev_energy[nb + i]);
            }
        }
        let start = self.start_band.min(self.mode.nb_ebands.saturating_sub(1));
        let end = self
            .end_band_override
            .unwrap_or_else(|| bandwidth_end(config));
        let active_len = ec.storage();
        if trace_this_packet {
            debug_trace!(
                "R pkt{} config={} end={} coded_channels={} output_channels={} frame_bytes={} total_bits={}",
                packet_idx,
                config,
                end,
                coded_channels,
                self.channels,
                active_len,
                active_len as i32 * 8
            );
            debug_trace!("R pkt{} frame_call_idx={}", packet_idx, frame_call_idx);
            if self.channels == 2 {
                debug_trace!(
                    "R pkt{} deemph_mem_in: ch0={:.6} ch1={:.6}",
                    packet_idx,
                    self.deemph_mem[0],
                    self.deemph_mem[1]
                );
            }
            // #region agent log
            if let Ok(mut f) = std::fs::OpenOptions::new()
                .create(true)
                .append(true)
                .open("/Users/tadeusz/Opus/Rasopus/.cursor/debug-bea564.log")
            {
                let ts = std::time::SystemTime::now()
                    .duration_since(std::time::UNIX_EPOCH)
                    .map(|d| d.as_millis())
                    .unwrap_or(0);
                let line = format!(
                    "{{\"sessionId\":\"4200c5\",\"runId\":\"run-stereo-probe\",\"hypothesisId\":\"H4\",\"location\":\"crates/opus-decoder/src/celt/mod.rs:184\",\"message\":\"target_pkt_channels_and_config\",\"data\":{{\"packet_idx\":{},\"config\":{},\"end\":{},\"coded_channels\":{},\"output_channels\":{}}},\"timestamp\":{}}}\n",
                    packet_idx, config, end, coded_channels, self.channels, ts
                );
                let _ = std::io::Write::write_all(&mut f, line.as_bytes());
            }
            // #endregion
        }
        let total_bits = (active_len * 8) as i32;
        let trace_pkt38_stage = |_stage: &str, _ec: &EcDec<'_>| {};
        trace_pkt38_stage("entry", &ec);
        let mut tell = ec.tell();
        let silence_flag = if tell >= total_bits {
            true
        } else if tell == 1 {
            ec.dec_bit_logp(15)
        } else {
            false
        };
        if silence_flag {
            tell = total_bits;
        }
        if trace_this_packet {
            debug_trace!(
                "R pkt{} [after_silence_flag] tell={}",
                packet_idx,
                ec.tell_frac()
            );
        }
        trace_pkt38_stage("silence", &ec);

        let mut postfilter_pitch = 0i32;
        let mut postfilter_qg = -1i32;
        let mut postfilter_tapset = 0i32;
        let mut postfilter_gain = 0.0f32;
        if start == 0 && tell + 16 <= total_bits && ec.dec_bit_logp(1) {
            let octave = ec.dec_uint(6) as i32;
            postfilter_pitch = ((16 << octave) + ec.dec_bits((4 + octave) as u32) as i32) - 1;
            postfilter_qg = ec.dec_bits(3) as i32;
            if ec.tell() + 2 <= total_bits {
                postfilter_tapset = ec.dec_icdf(&TAPSET_ICDF, 2) as i32;
            }
            postfilter_gain = 0.09375 * (postfilter_qg + 1) as f32;
        }
        if trace_this_packet {
            debug_trace!(
                "R pkt{} postfilter_active={} pitch={} qg={} tapset={}",
                packet_idx,
                postfilter_pitch > 0,
                postfilter_pitch,
                postfilter_qg,
                postfilter_tapset
            );
        }
        // #region agent log
        if let Ok(mut f) = std::fs::OpenOptions::new()
            .create(true)
            .append(true)
            .open("/Users/tadeusz/Opus/Rasopus/.cursor/debug-bea564.log")
        {
            let ts = std::time::SystemTime::now()
                .duration_since(std::time::UNIX_EPOCH)
                .map(|d| d.as_millis())
                .unwrap_or(0);
            let line = format!(
                "{{\"sessionId\":\"4200c5\",\"runId\":\"run-postfilter-overlap-scan\",\"hypothesisId\":\"H10\",\"location\":\"crates/opus-decoder/src/celt/mod.rs:227\",\"message\":\"postfilter_decoded\",\"data\":{{\"packet_idx\":{},\"coded_channels\":{},\"postfilter_active\":{},\"postfilter_pitch\":{},\"postfilter_qg\":{},\"postfilter_tapset\":{}}},\"timestamp\":{}}}\n",
                packet_idx,
                coded_channels,
                postfilter_pitch > 0,
                postfilter_pitch,
                postfilter_qg,
                postfilter_tapset,
                ts
            );
            let _ = std::io::Write::write_all(&mut f, line.as_bytes());
        }
        // #endregion
        if postfilter_pitch > 0 {
            // #region agent log
            if let Ok(mut f) = std::fs::OpenOptions::new()
                .create(true)
                .append(true)
                .open("/Users/tadeusz/Opus/Rasopus/.cursor/debug-bea564.log")
            {
                let ts = std::time::SystemTime::now()
                    .duration_since(std::time::UNIX_EPOCH)
                    .map(|d| d.as_millis())
                    .unwrap_or(0);
                let line = format!(
                    "{{\"sessionId\":\"4200c5\",\"runId\":\"run-postfilter-overlap-scan\",\"hypothesisId\":\"H11\",\"location\":\"crates/opus-decoder/src/celt/mod.rs:249\",\"message\":\"postfilter_active_but_not_applied_in_rust\",\"data\":{{\"packet_idx\":{},\"postfilter_pitch\":{},\"postfilter_qg\":{},\"postfilter_tapset\":{},\"comb_filter_applied\":false}},\"timestamp\":{}}}\n",
                    packet_idx, postfilter_pitch, postfilter_qg, postfilter_tapset, ts
                );
                let _ = std::io::Write::write_all(&mut f, line.as_bytes());
            }
            // #endregion
        }
        tell = ec.tell();
        if trace_this_packet {
            debug_trace!(
                "R pkt{} [after_postfilter] tell={}",
                packet_idx,
                ec.tell_frac()
            );
        }
        trace_pkt38_stage("postfilt", &ec);
        let lm = match frame_size_48k {
            120 => 0,
            240 => 1,
            480 => 2,
            960 => 3,
            _ => unreachable!(),
        };
        let is_transient = if lm > 0 && tell + 3 <= total_bits {
            ec.dec_bit_logp(3)
        } else {
            false
        };
        if packet_idx == 905 {
            debug_trace!(
                "R pkt905 frame_size_48k={} lm={} is_transient={}",
                frame_size_48k,
                lm,
                is_transient
            );
        }
        if trace_this_packet {
            debug_trace!(
                "R pkt{} [after_transient] tell={}",
                packet_idx,
                ec.tell_frac()
            );
        }
        trace_pkt38_stage("transient", &ec);
        tell = ec.tell();
        let intra_ener = tell + 3 <= total_bits && ec.dec_bit_logp(3);
        if trace_this_packet {
            debug_trace!(
                "R pkt{} [after_intra_ener] tell={}",
                packet_idx,
                ec.tell_frac()
            );
        }
        trace_pkt38_stage("intra", &ec);
        if trace_this_packet {
            debug_trace!(
                "R pkt{} prev_energy_all: {:?}",
                packet_idx,
                &self.prev_energy[..self.mode.nb_ebands]
            );
            debug_trace!(
                "R pkt{} old_log_energy_all: {:?}",
                packet_idx,
                &self.old_log_energy[..self.mode.nb_ebands]
            );
            debug_trace!(
                "R pkt{} old_log_energy2_all: {:?}",
                packet_idx,
                &self.old_log_energy2[..self.mode.nb_ebands]
            );
        }

        quant_bands::unquant_coarse_energy(
            self.mode,
            start,
            end,
            &mut self.prev_energy,
            intra_ener,
            &mut ec,
            coded_channels,
            lm,
            total_bits,
        );
        if trace_this_packet {
            debug_trace!(
                "R pkt{} [after_coarse_energy] tell={}",
                packet_idx,
                ec.tell_frac()
            );
        }
        trace_pkt38_stage("coarse", &ec);
        let mut tf_res = vec![0i32; self.mode.nb_ebands];
        tf_decode(
            start,
            end,
            is_transient,
            &mut tf_res,
            lm,
            total_bits,
            &mut ec,
        );
        if trace_this_packet {
            debug_trace!("R pkt{} [after_tf] tell={}", packet_idx, ec.tell_frac());
            // #region agent log
            let tf_csv = tf_res[start..end]
                .iter()
                .map(std::string::ToString::to_string)
                .collect::<Vec<_>>()
                .join(",");
            debug_trace!(
                "R pkt{} frame_call_idx={} tf_res[{}..{}]={}",
                packet_idx,
                frame_call_idx,
                start,
                end,
                tf_csv
            );
            // #endregion
        }
        trace_pkt38_stage("tf", &ec);
        let spread_decision = if ec.tell() + 4 <= total_bits {
            ec.dec_icdf(&SPREAD_ICDF, 5)
        } else {
            0
        };
        if trace_this_packet {
            debug_trace!("R pkt{} [after_spread] tell={}", packet_idx, ec.tell_frac());
            // #region agent log
            debug_trace!(
                "R pkt{} frame_call_idx={} spread_decision={}",
                packet_idx,
                frame_call_idx,
                spread_decision
            );
            // #endregion
        }
        trace_pkt38_stage("spread", &ec);
        let cap = rate::init_caps(self.mode, lm, coded_channels);
        let mut offsets = vec![0i32; self.mode.nb_ebands];
        let mut dynalloc_logp = 6i32;
        let mut total_bits_q = total_bits << BITRES;
        tell = ec.tell_frac() as i32;
        if trace_this_packet {
            debug_trace!(
                "R pkt{} [after_dynalloc_start] tell={}",
                packet_idx,
                ec.tell_frac()
            );
        }
        for i in start..end {
            let width = (coded_channels as i32)
                * (((self.mode.e_bands[i + 1] - self.mode.e_bands[i]) as i32) << lm);
            let quanta = ((width << BITRES).min((6 << BITRES).max(width))).max(0);
            let mut dynalloc_loop_logp = dynalloc_logp;
            let mut boost = 0i32;
            let tell_before_dyn_band = tell;
            while tell + (dynalloc_loop_logp << BITRES) < total_bits_q && boost < cap[i] {
                let flag = ec.dec_bit_logp(dynalloc_loop_logp as u32);
                tell = ec.tell_frac() as i32;
                if !flag {
                    break;
                }
                boost += quanta;
                total_bits_q -= quanta;
                dynalloc_loop_logp = 1;
            }
            offsets[i] = boost;
            if boost > 0 {
                dynalloc_logp = (dynalloc_logp - 1).max(2);
            }
            if trace_this_packet {
                // #region agent log
                debug_trace!(
                    "R pkt{} dynalloc band={} width={} quanta={} cap={} boost={} tell:{}->{} total_bits_q={} dynalloc_logp={}",
                    packet_idx,
                    i,
                    width,
                    quanta,
                    cap[i],
                    boost,
                    tell_before_dyn_band,
                    tell,
                    total_bits_q,
                    dynalloc_logp
                );
                if let Ok(mut f) = std::fs::OpenOptions::new()
                    .create(true)
                    .append(true)
                    .open("/Users/tadeusz/Opus/Rasopus/.cursor/debug-bea564.log")
                {
                    let ts = std::time::SystemTime::now()
                        .duration_since(std::time::UNIX_EPOCH)
                        .map(|d| d.as_millis())
                        .unwrap_or(0);
                    let line = format!(
                        "{{\"sessionId\":\"4200c5\",\"runId\":\"run-stereo-probe\",\"hypothesisId\":\"H8\",\"location\":\"crates/opus-decoder/src/celt/mod.rs:318\",\"message\":\"dynalloc_band_state\",\"data\":{{\"packet_idx\":{},\"band\":{},\"width\":{},\"quanta\":{},\"cap\":{},\"boost\":{},\"tell_before\":{},\"tell_after\":{},\"total_bits_q\":{},\"dynalloc_logp\":{}}},\"timestamp\":{}}}\n",
                        packet_idx,
                        i,
                        width,
                        quanta,
                        cap[i],
                        boost,
                        tell_before_dyn_band,
                        tell,
                        total_bits_q,
                        dynalloc_logp,
                        ts
                    );
                    let _ = std::io::Write::write_all(&mut f, line.as_bytes());
                }
                // #endregion
            }
        }
        if trace_this_packet {
            debug_trace!(
                "R pkt{} [after_dynalloc_end] tell={}",
                packet_idx,
                ec.tell_frac()
            );
        }
        trace_pkt38_stage("dynalloc", &ec);
        let alloc_trim = if tell + (6 << BITRES) <= total_bits_q {
            ec.dec_icdf(&TRIM_ICDF, 7)
        } else {
            5
        };
        if trace_this_packet {
            debug_trace!("R pkt{} [after_trim] tell={}", packet_idx, ec.tell_frac());
            // #region agent log
            debug_trace!("R pkt{} alloc_trim={}", packet_idx, alloc_trim);
            // #endregion
        }
        trace_pkt38_stage("trim", &ec);
        let mut bits = ((active_len as i32 * 8) << BITRES) - ec.tell_frac() as i32 - 1;
        let anti_collapse_rsv = if is_transient && lm >= 2 && bits >= ((lm as i32 + 2) << BITRES) {
            1 << BITRES
        } else {
            0
        };
        if packet_idx < 5 || trace_this_packet {
            debug_trace!(
                "pkt{} is_transient={} anti_collapse_rsv={}",
                packet_idx,
                is_transient,
                anti_collapse_rsv
            );
        }
        if (4..=8).contains(&packet_idx) {
            let overlap_abs: f32 = self.overlap_tail().iter().map(|x| x.abs()).sum();
            let deemph = self.deemph_mem[0];
            debug_trace!(
                "R pkt{} STATE_IN overlap_abs={:.4} deemph={:.6} frame_size={} config={} end={} is_transient={} lm={} postfilter_pitch={} anti_collapse_rsv={}",
                packet_idx,
                overlap_abs,
                deemph,
                frame_size_48k,
                config,
                end,
                is_transient,
                lm,
                postfilter_pitch,
                anti_collapse_rsv
            );
        }
        if debug_packet || intra_ener {
            let data = format!(
                "{{\"packet_idx\":{},\"config\":{},\"end\":{},\"lm\":{},\"is_transient\":{},\"intra_ener\":{},\"postfilter_active\":{},\"postfilter_pitch\":{},\"postfilter_qg\":{},\"postfilter_tapset\":{},\"anti_collapse_rsv\":{}}}",
                packet_idx,
                config,
                end,
                lm,
                is_transient,
                intra_ener,
                postfilter_pitch > 0,
                postfilter_pitch,
                postfilter_qg,
                postfilter_tapset,
                anti_collapse_rsv
            );
            // #region agent log
            Self::append_debug_log(
                "run-onset-map-v1",
                "H4",
                "crates/opus-decoder/src/celt/mod.rs:decode_frame",
                "packet_flags",
                &data,
            );
            // #endregion
        }
        bits -= anti_collapse_rsv;
        let alloc = rate::clt_compute_allocation(
            self.mode,
            start,
            end,
            &offsets,
            &cap,
            alloc_trim,
            bits,
            coded_channels,
            lm,
            &mut ec,
            packet_idx,
        );
        if trace_this_packet {
            debug_trace!("R pkt{} [after_alloc] tell={}", packet_idx, ec.tell_frac());
            debug_trace!(
                "R pkt{} alloc_meta: bits_in={} balance_out={} coded_bands={} intensity={} dual_stereo={}",
                packet_idx,
                bits,
                alloc.balance,
                alloc.coded_bands,
                alloc.intensity,
                alloc.dual_stereo
            );
            for i in start..end {
                debug_trace!(
                    "R pkt{} alloc band={} pulse={} fine={} prio={}",
                    packet_idx,
                    i,
                    alloc.pulses[i],
                    alloc.fine_quant[i],
                    alloc.fine_priority[i]
                );
            }
        }
        trace_pkt38_stage("alloc", &ec);
        if trace_this_packet {
            debug_trace!(
                "pkt{}: is_transient={} anti_collapse_rsv={} coded_bands={}",
                packet_idx,
                is_transient,
                anti_collapse_rsv,
                alloc.coded_bands
            );
            debug_trace!(
                "pkt{}: tell_before_bands={} total_bits_q={}",
                packet_idx,
                ec.tell_frac(),
                (active_len as i32 * 8) << 3
            );
            // #region agent log
            if let Ok(mut f) = std::fs::OpenOptions::new()
                .create(true)
                .append(true)
                .open("/Users/tadeusz/Opus/Rasopus/.cursor/debug-bea564.log")
            {
                let ts = std::time::SystemTime::now()
                    .duration_since(std::time::UNIX_EPOCH)
                    .map(|d| d.as_millis())
                    .unwrap_or(0);
                let line = format!(
                    "{{\"sessionId\":\"4200c5\",\"runId\":\"run-stereo-probe\",\"hypothesisId\":\"H2\",\"location\":\"crates/opus-decoder/src/celt/mod.rs:368\",\"message\":\"tell_before_fine_with_alloc\",\"data\":{{\"packet_idx\":{},\"tell\":{},\"total_bits_q\":{},\"channels\":{},\"intensity\":{},\"dual_stereo\":{},\"coded_bands\":{}}},\"timestamp\":{}}}\n",
                    packet_idx,
                    ec.tell_frac(),
                    (active_len as i32 * 8) << 3,
                    coded_channels,
                    alloc.intensity,
                    alloc.dual_stereo,
                    alloc.coded_bands,
                    ts
                );
                let _ = std::io::Write::write_all(&mut f, line.as_bytes());
            }
            // #endregion
        }
        quant_bands::unquant_fine_energy(
            self.mode,
            start,
            end,
            &mut self.prev_energy,
            &alloc.fine_quant,
            &mut ec,
            coded_channels,
            packet_idx,
        );
        trace_pkt38_stage("fine", &ec);
        if trace_this_packet {
            debug_trace!(
                "R pkt{} [after_fine_energy] tell={}",
                packet_idx,
                ec.tell_frac()
            );
            debug_trace!(
                "R pkt{} tell_before_bands_actual={}",
                packet_idx,
                ec.tell_frac()
            );
            // #region agent log
            if let Ok(mut f) = std::fs::OpenOptions::new()
                .create(true)
                .append(true)
                .open("/Users/tadeusz/Opus/Rasopus/.cursor/debug-bea564.log")
            {
                let ts = std::time::SystemTime::now()
                    .duration_since(std::time::UNIX_EPOCH)
                    .map(|d| d.as_millis())
                    .unwrap_or(0);
                let line = format!(
                    "{{\"sessionId\":\"4200c5\",\"runId\":\"run-stereo-probe\",\"hypothesisId\":\"H3\",\"location\":\"crates/opus-decoder/src/celt/mod.rs:383\",\"message\":\"tell_before_bands_actual\",\"data\":{{\"packet_idx\":{},\"tell\":{},\"channels\":{}}},\"timestamp\":{}}}\n",
                    packet_idx,
                    ec.tell_frac(),
                    coded_channels,
                    ts
                );
                let _ = std::io::Write::write_all(&mut f, line.as_bytes());
            }
            // #endregion
        }
        let mut mdct_in = vec![0.0f32; frame_size_48k];
        let tell_before_bands_decode = ec.tell_frac();
        let mut mdct_side = if coded_channels == 2 {
            Some(vec![0.0f32; frame_size_48k])
        } else {
            None
        };
        let band_decode_path = if coded_channels == 2 {
            "quant_all_bands_stereo"
        } else {
            "quant_all_bands_mono"
        };
        if trace_this_packet {
            // #region agent log
            if let Ok(mut f) = std::fs::OpenOptions::new()
                .create(true)
                .append(true)
                .open("/Users/tadeusz/Opus/Rasopus/.cursor/debug-bea564.log")
            {
                let ts = std::time::SystemTime::now()
                    .duration_since(std::time::UNIX_EPOCH)
                    .map(|d| d.as_millis())
                    .unwrap_or(0);
                let line = format!(
                    "{{\"sessionId\":\"4200c5\",\"runId\":\"run-stereo-probe\",\"hypothesisId\":\"H5\",\"location\":\"crates/opus-decoder/src/celt/mod.rs:434\",\"message\":\"band_decode_path_selected\",\"data\":{{\"packet_idx\":{},\"coded_channels\":{},\"path\":\"{}\",\"intensity\":{},\"dual_stereo\":{},\"tell_before\":{}}},\"timestamp\":{}}}\n",
                    packet_idx,
                    coded_channels,
                    band_decode_path,
                    alloc.intensity,
                    alloc.dual_stereo,
                    tell_before_bands_decode,
                    ts
                );
                let _ = std::io::Write::write_all(&mut f, line.as_bytes());
            }
            // #endregion
        }
        let collapse_masks = if let Some(ref mut side) = mdct_side {
            bands::quant_all_bands_stereo(
                self.mode,
                start,
                end,
                &mut mdct_in,
                side,
                &alloc.pulses,
                is_transient,
                spread_decision,
                &tf_res,
                (active_len as i32 * 8 << BITRES) - anti_collapse_rsv,
                alloc.balance,
                alloc.coded_bands,
                lm,
                &mut ec,
                &mut self.rng_seed,
                alloc.dual_stereo,
                alloc.intensity,
                coded_channels == 2 && self.channels == 1,
                packet_idx,
                frame_call_idx,
            )
        } else {
            bands::quant_all_bands_mono(
                self.mode,
                start,
                end,
                &mut mdct_in,
                &alloc.pulses,
                is_transient,
                spread_decision,
                &tf_res,
                (active_len as i32 * 8 << BITRES) - anti_collapse_rsv,
                alloc.balance,
                alloc.coded_bands,
                lm,
                &mut ec,
                &mut self.rng_seed,
                packet_idx,
                frame_call_idx,
            )
        };
        let anti_probe_packet = packet_idx <= 8 || trace_this_packet;
        let collapse_blocks = 1usize << lm;
        let collapse_mask_limit = if collapse_blocks >= 8 {
            0xFFu8
        } else {
            ((1u16 << collapse_blocks) - 1) as u8
        };
        let collapse_nonfull_count = collapse_masks
            .iter()
            .filter(|&&m| (m & collapse_mask_limit) != collapse_mask_limit)
            .count();
        let collapse_empty_count = collapse_masks
            .iter()
            .filter(|&&m| (m & collapse_mask_limit) == 0)
            .count();
        if anti_probe_packet {
            let data = format!(
                "{{\"packet_idx\":{},\"lm\":{},\"coded_channels\":{},\"collapse_masks_len\":{},\"collapse_mask_limit\":{},\"collapse_nonfull_count\":{},\"collapse_empty_count\":{},\"seed_after_quant\":{},\"ec_rng_after_quant\":{},\"anti_collapse_rsv\":{},\"is_transient\":{}}}",
                packet_idx,
                lm,
                coded_channels,
                collapse_masks.len(),
                collapse_mask_limit,
                collapse_nonfull_count,
                collapse_empty_count,
                self.rng_seed,
                ec.rng(),
                anti_collapse_rsv,
                is_transient
            );
            // #region agent log
            Self::append_runtime_debug_log(
                "run-anti-collapse-v1",
                "H2",
                "crates/opus-decoder/src/celt/mod.rs:decode_frame",
                "anti_collapse_post_quant_state",
                &data,
            );
            // #endregion
        }
        if packet_idx <= 10 || coded_channels == 2 {
            let data = format!(
                "{{\"packet_idx\":{},\"coded_channels\":{},\"output_channels\":{},\"is_transient\":{},\"lm\":{},\"anti_collapse_rsv\":{},\"collapse_nonfull_count\":{}}}",
                packet_idx,
                coded_channels,
                self.channels,
                is_transient,
                lm,
                anti_collapse_rsv,
                collapse_nonfull_count
            );
            // #region agent log
            Self::append_runtime_debug_log(
                "run-coded-channels-scan-v1",
                "H9",
                "crates/opus-decoder/src/celt/mod.rs:decode_frame",
                "coded_channels_state",
                &data,
            );
            // #endregion
        }
        if trace_this_packet {
            // #region agent log
            if let Ok(mut f) = std::fs::OpenOptions::new()
                .create(true)
                .append(true)
                .open("/Users/tadeusz/Opus/Rasopus/.cursor/debug-bea564.log")
            {
                let ts = std::time::SystemTime::now()
                    .duration_since(std::time::UNIX_EPOCH)
                    .map(|d| d.as_millis())
                    .unwrap_or(0);
                let tell_after = ec.tell_frac();
                let line = format!(
                    "{{\"sessionId\":\"4200c5\",\"runId\":\"run-stereo-probe\",\"hypothesisId\":\"H6\",\"location\":\"crates/opus-decoder/src/celt/mod.rs:450\",\"message\":\"band_decode_consumed_bits\",\"data\":{{\"packet_idx\":{},\"coded_channels\":{},\"tell_before\":{},\"tell_after\":{},\"consumed\":{}}},\"timestamp\":{}}}\n",
                    packet_idx,
                    coded_channels,
                    tell_before_bands_decode,
                    tell_after,
                    tell_after as i32 - tell_before_bands_decode as i32,
                    ts
                );
                let _ = std::io::Write::write_all(&mut f, line.as_bytes());
            }
            // #endregion
        }
        if trace_this_packet && coded_channels == 2 {
            let mid_abs = mdct_in.iter().map(|v| v.abs()).sum::<f32>();
            let side_abs = mdct_side
                .as_ref()
                .map(|v| v.iter().map(|x| x.abs()).sum::<f32>())
                .unwrap_or(0.0);
            let output_strategy = if self.channels == 1 {
                "freq_downmix_after_denorm"
            } else {
                "multi_channel_output"
            };
            // #region agent log
            if let Ok(mut f) = std::fs::OpenOptions::new()
                .create(true)
                .append(true)
                .open("/Users/tadeusz/Opus/Rasopus/.cursor/debug-bea564.log")
            {
                let ts = std::time::SystemTime::now()
                    .duration_since(std::time::UNIX_EPOCH)
                    .map(|d| d.as_millis())
                    .unwrap_or(0);
                let line = format!(
                    "{{\"sessionId\":\"4200c5\",\"runId\":\"run-stereo-probe\",\"hypothesisId\":\"H9\",\"location\":\"crates/opus-decoder/src/celt/mod.rs:486\",\"message\":\"stereo_vectors_before_denorm\",\"data\":{{\"packet_idx\":{},\"coded_channels\":{},\"mid_abs\":{},\"side_abs\":{},\"output_strategy\":\"{}\"}},\"timestamp\":{}}}\n",
                    packet_idx, coded_channels, mid_abs, side_abs, output_strategy, ts
                );
                let _ = std::io::Write::write_all(&mut f, line.as_bytes());
            }
            // #endregion
        }
        self.last_split_count = 0;
        if packet_idx == 0 {
            let m = 1usize << lm;
            let mut band_abs = String::new();
            for bi in start..end {
                let bs = m * self.mode.e_bands[bi] as usize;
                let be = m * self.mode.e_bands[bi + 1] as usize;
                let acc: f32 = mdct_in[bs..be].iter().map(|v| v.abs()).sum();
                if !band_abs.is_empty() {
                    band_abs.push(',');
                }
                band_abs.push_str(&format!("{:.6}", acc));
            }
            // #region agent log
            if let Ok(mut f) = std::fs::OpenOptions::new()
                .create(true)
                .append(true)
                .open("/Users/tadeusz/Opus/Rasopus/.cursor/debug-bea564.log")
            {
                let ts = std::time::SystemTime::now()
                    .duration_since(std::time::UNIX_EPOCH)
                    .map(|d| d.as_millis())
                    .unwrap_or(0);
                let line = format!(
                    "{{\"sessionId\":\"4200c5\",\"runId\":\"run-norm-band-scan\",\"hypothesisId\":\"H21\",\"location\":\"crates/opus-decoder/src/celt/mod.rs:676\",\"message\":\"norm_band_abs_snapshot\",\"data\":{{\"packet_idx\":{},\"coded_channels\":{},\"start\":{},\"end\":{},\"lm\":{},\"band_abs_csv\":\"{}\"}},\"timestamp\":{}}}\n",
                    packet_idx, coded_channels, start, end, lm, band_abs, ts
                );
                let _ = std::io::Write::write_all(&mut f, line.as_bytes());
            }
            // #endregion
        }
        if (packet_idx == 0 || (2128..=2129).contains(&packet_idx)) && is_transient {
            let m_blocks = 1usize << lm;
            let short_len = frame_size_48k / m_blocks.max(1);
            // #region agent log
            if m_blocks == 8 && short_len > 0 {
                let mut block_abs = [0.0f32; 8];
                for b in 0..8 {
                    let mut acc = 0.0f32;
                    for j in 0..short_len {
                        acc += mdct_in[j * m_blocks + b].abs();
                    }
                    block_abs[b] = acc;
                }
                if let Ok(mut f) = std::fs::OpenOptions::new()
                    .create(true)
                    .append(true)
                    .open("/Users/tadeusz/Opus/Rasopus/.cursor/debug-bea564.log")
                {
                    let ts = std::time::SystemTime::now()
                        .duration_since(std::time::UNIX_EPOCH)
                        .map(|d| d.as_millis())
                        .unwrap_or(0);
                    let line = format!(
                        "{{\"sessionId\":\"4200c5\",\"runId\":\"run-norm-block-scan\",\"hypothesisId\":\"H20\",\"location\":\"crates/opus-decoder/src/celt/mod.rs:684\",\"message\":\"norm_block_abs_snapshot\",\"data\":{{\"packet_idx\":{},\"coded_channels\":{},\"blocks\":{},\"short_len\":{},\"block_abs\":[{:.6},{:.6},{:.6},{:.6},{:.6},{:.6},{:.6},{:.6}]}},\"timestamp\":{}}}\n",
                        packet_idx,
                        coded_channels,
                        m_blocks,
                        short_len,
                        block_abs[0],
                        block_abs[1],
                        block_abs[2],
                        block_abs[3],
                        block_abs[4],
                        block_abs[5],
                        block_abs[6],
                        block_abs[7],
                        ts
                    );
                    let _ = std::io::Write::write_all(&mut f, line.as_bytes());
                }
            }
            // #endregion
        }
        // Anti-collapse bit (consumed from range coder for transient frames).
        let mut anti_collapse_on = 0u32;
        if anti_collapse_rsv > 0 {
            anti_collapse_on = ec.dec_bits(1);
            if trace_this_packet {
                debug_trace!(
                    "pkt{} anti_collapse_rsv={} anti_collapse_on={} is_transient={} lm={}",
                    packet_idx,
                    anti_collapse_rsv,
                    anti_collapse_on,
                    is_transient,
                    lm
                );
            }
            if debug_packet {
                let data = format!(
                    "{{\"packet_idx\":{},\"anti_collapse_rsv\":{},\"anti_collapse_on\":{},\"is_transient\":{},\"lm\":{}}}",
                    packet_idx, anti_collapse_rsv, anti_collapse_on, is_transient, lm
                );
                // #region agent log
                Self::append_debug_log(
                    "run-onset-map-v1",
                    "H5",
                    "crates/opus-decoder/src/celt/mod.rs:decode_frame",
                    "anti_collapse_flag",
                    &data,
                );
                // #endregion
            }
        } else if trace_this_packet {
            debug_trace!(
                "pkt{} anti_collapse_rsv=0 anti_collapse_on=0 is_transient={} lm={}",
                packet_idx,
                is_transient,
                lm
            );
            if debug_packet {
                let data = format!(
                    "{{\"packet_idx\":{},\"anti_collapse_rsv\":0,\"anti_collapse_on\":0,\"is_transient\":{},\"lm\":{}}}",
                    packet_idx, is_transient, lm
                );
                // #region agent log
                Self::append_debug_log(
                    "run-onset-map-v1",
                    "H5",
                    "crates/opus-decoder/src/celt/mod.rs:decode_frame",
                    "anti_collapse_flag",
                    &data,
                );
                // #endregion
            }
        } else if debug_packet {
            let data = format!(
                "{{\"packet_idx\":{},\"anti_collapse_rsv\":0,\"anti_collapse_on\":0,\"is_transient\":{},\"lm\":{}}}",
                packet_idx, is_transient, lm
            );
            // #region agent log
            Self::append_debug_log(
                "run-onset-map-v1",
                "H5",
                "crates/opus-decoder/src/celt/mod.rs:decode_frame",
                "anti_collapse_flag",
                &data,
            );
            // #endregion
        }
        if anti_probe_packet {
            let data = format!(
                "{{\"packet_idx\":{},\"anti_collapse_rsv\":{},\"anti_collapse_on\":{},\"seed_before_apply\":{},\"ec_rng_before_apply\":{},\"is_transient\":{},\"lm\":{},\"collapse_nonfull_count\":{}}}",
                packet_idx,
                anti_collapse_rsv,
                anti_collapse_on,
                self.rng_seed,
                ec.rng(),
                is_transient,
                lm,
                collapse_nonfull_count
            );
            // #region agent log
            Self::append_runtime_debug_log(
                "run-anti-collapse-v1",
                "H1",
                "crates/opus-decoder/src/celt/mod.rs:decode_frame",
                "anti_collapse_gate_state",
                &data,
            );
            // #endregion
        }
        let anti_pre_main_abs = if anti_probe_packet {
            mdct_in.iter().map(|v| v.abs()).sum::<f32>()
        } else {
            0.0
        };
        let anti_pre_side_abs = if anti_probe_packet {
            mdct_side
                .as_ref()
                .map(|side| side.iter().map(|v| v.abs()).sum::<f32>())
                .unwrap_or(0.0)
        } else {
            0.0
        };
        quant_bands::unquant_energy_finalise(
            self.mode,
            start,
            end,
            Some(&mut self.prev_energy),
            &alloc.fine_quant,
            &alloc.fine_priority,
            active_len as i32 * 8 - ec.tell(),
            &mut ec,
            coded_channels,
        );
        if anti_collapse_on != 0 {
            if trace_this_packet {
                debug_trace!(
                    "R pkt{} applying anti_collapse lm={} coded_channels={} seed={}",
                    packet_idx,
                    lm,
                    coded_channels,
                    self.rng_seed
                );
            }
            if let Some(side) = mdct_side.as_mut() {
                bands::anti_collapse(
                    self.mode,
                    &mut mdct_in,
                    Some(side),
                    &collapse_masks,
                    lm,
                    coded_channels,
                    start,
                    end,
                    &self.prev_energy,
                    &self.old_log_energy,
                    &self.old_log_energy2,
                    &alloc.pulses,
                    self.rng_seed,
                    trace_this_packet,
                    packet_idx,
                );
            } else {
                bands::anti_collapse(
                    self.mode,
                    &mut mdct_in,
                    None,
                    &collapse_masks,
                    lm,
                    coded_channels,
                    start,
                    end,
                    &self.prev_energy,
                    &self.old_log_energy,
                    &self.old_log_energy2,
                    &alloc.pulses,
                    self.rng_seed,
                    trace_this_packet,
                    packet_idx,
                );
            }
        }
        if anti_probe_packet {
            let anti_post_main_abs = mdct_in.iter().map(|v| v.abs()).sum::<f32>();
            let anti_post_side_abs = mdct_side
                .as_ref()
                .map(|side| side.iter().map(|v| v.abs()).sum::<f32>())
                .unwrap_or(0.0);
            let data = format!(
                "{{\"packet_idx\":{},\"anti_collapse_on\":{},\"main_abs_before\":{},\"main_abs_after\":{},\"side_abs_before\":{},\"side_abs_after\":{},\"main_abs_delta\":{},\"side_abs_delta\":{},\"seed_after_apply\":{},\"ec_rng_after_apply\":{}}}",
                packet_idx,
                anti_collapse_on,
                anti_pre_main_abs,
                anti_post_main_abs,
                anti_pre_side_abs,
                anti_post_side_abs,
                anti_post_main_abs - anti_pre_main_abs,
                anti_post_side_abs - anti_pre_side_abs,
                self.rng_seed,
                ec.rng()
            );
            // #region agent log
            Self::append_runtime_debug_log(
                "run-anti-collapse-v1",
                "H5",
                "crates/opus-decoder/src/celt/mod.rs:decode_frame",
                "anti_collapse_apply_effect",
                &data,
            );
            // #endregion
        }
        if packet_idx == 6 {
            let m = 1usize << lm;
            let bound = m * self.mode.e_bands[end] as usize;
            let x_abs_sum_active: f32 = mdct_in[..bound].iter().map(|x| x.abs()).sum();
            let x_abs_sum_tail: f32 = mdct_in[bound..].iter().map(|x| x.abs()).sum();
            let len = mdct_in.len();
            let data = format!(
                "{{\"packet_idx\":{},\"stage\":\"before_denormalise\",\"x_abs_sum_active\":{},\"x_abs_sum_tail\":{},\"bound\":{},\"x_first8\":[{:.9},{:.9},{:.9},{:.9},{:.9},{:.9},{:.9},{:.9}],\"x_active_last8\":[{:.9},{:.9},{:.9},{:.9},{:.9},{:.9},{:.9},{:.9}],\"x_tail_first4\":[{:.9},{:.9},{:.9},{:.9}]}}",
                packet_idx,
                x_abs_sum_active,
                x_abs_sum_tail,
                bound,
                mdct_in.get(0).copied().unwrap_or(0.0),
                mdct_in.get(1).copied().unwrap_or(0.0),
                mdct_in.get(2).copied().unwrap_or(0.0),
                mdct_in.get(3).copied().unwrap_or(0.0),
                mdct_in.get(4).copied().unwrap_or(0.0),
                mdct_in.get(5).copied().unwrap_or(0.0),
                mdct_in.get(6).copied().unwrap_or(0.0),
                mdct_in.get(7).copied().unwrap_or(0.0),
                mdct_in.get(bound.saturating_sub(8)).copied().unwrap_or(0.0),
                mdct_in.get(bound.saturating_sub(7)).copied().unwrap_or(0.0),
                mdct_in.get(bound.saturating_sub(6)).copied().unwrap_or(0.0),
                mdct_in.get(bound.saturating_sub(5)).copied().unwrap_or(0.0),
                mdct_in.get(bound.saturating_sub(4)).copied().unwrap_or(0.0),
                mdct_in.get(bound.saturating_sub(3)).copied().unwrap_or(0.0),
                mdct_in.get(bound.saturating_sub(2)).copied().unwrap_or(0.0),
                mdct_in.get(bound.saturating_sub(1)).copied().unwrap_or(0.0),
                mdct_in.get(bound).copied().unwrap_or(0.0),
                mdct_in
                    .get((bound + 1).min(len.saturating_sub(1)))
                    .copied()
                    .unwrap_or(0.0),
                mdct_in
                    .get((bound + 2).min(len.saturating_sub(1)))
                    .copied()
                    .unwrap_or(0.0),
                mdct_in
                    .get((bound + 3).min(len.saturating_sub(1)))
                    .copied()
                    .unwrap_or(0.0)
            );
            // #region agent log
            Self::append_runtime_debug_log(
                "run-pkt6-mdctin-v1",
                "H84",
                "crates/opus-decoder/src/celt/mod.rs:decode_frame",
                "rust_pkt6_mdct_in_before_denorm",
                &data,
            );
            // #endregion
            let e = &self.prev_energy[..self.mode.nb_ebands];
            let e_abs_sum: f32 = e.iter().map(|x| x.abs()).sum();
            let data = format!(
                "{{\"packet_idx\":{},\"stage\":\"before_denormalise\",\"energy_abs_sum\":{},\"energy_first12\":[{:.9},{:.9},{:.9},{:.9},{:.9},{:.9},{:.9},{:.9},{:.9},{:.9},{:.9},{:.9}]}}",
                packet_idx,
                e_abs_sum,
                e.get(0).copied().unwrap_or(0.0),
                e.get(1).copied().unwrap_or(0.0),
                e.get(2).copied().unwrap_or(0.0),
                e.get(3).copied().unwrap_or(0.0),
                e.get(4).copied().unwrap_or(0.0),
                e.get(5).copied().unwrap_or(0.0),
                e.get(6).copied().unwrap_or(0.0),
                e.get(7).copied().unwrap_or(0.0),
                e.get(8).copied().unwrap_or(0.0),
                e.get(9).copied().unwrap_or(0.0),
                e.get(10).copied().unwrap_or(0.0),
                e.get(11).copied().unwrap_or(0.0)
            );
            // #region agent log
            Self::append_runtime_debug_log(
                "run-pkt6-energy-v1",
                "H83",
                "crates/opus-decoder/src/celt/mod.rs:decode_frame",
                "rust_pkt6_oldbande_before_denorm",
                &data,
            );
            // #endregion
        }
        if silence_flag {
            let state_channels = coded_channels.min(2);
            for c in 0..state_channels {
                let base = c * self.mode.nb_ebands;
                for i in 0..self.mode.nb_ebands {
                    self.prev_energy[base + i] = LOG_ENERGY_FLOOR_DB;
                }
            }
        }
        let mut denorm = vec![0.0f32; frame_size_48k];
        bands::denormalise_bands(
            self.mode,
            &mdct_in,
            &mut denorm,
            &self.prev_energy[..self.mode.nb_ebands],
            start,
            end,
            lm,
            silence_flag,
        );
        if packet_idx == 905 {
            let denorm_abs_sum: f32 = denorm.iter().map(|x| x.abs()).sum();
            let first_n = denorm.len().min(16);
            debug_trace!(
                "R pkt905 denorm abs_sum={:.6} first{}={:?}",
                denorm_abs_sum,
                first_n,
                &denorm[..first_n]
            );
            let m = 1usize << lm;
            for i in 0..end {
                let bs = m * self.mode.e_bands[i] as usize;
                let be = m * self.mode.e_bands[i + 1] as usize;
                let band_abs: f32 = denorm[bs..be].iter().map(|x| x.abs()).sum();
                debug_trace!("R pkt905 denorm band {} abs_sum={:.6}", i, band_abs);
            }
            let active_end = m * self.mode.e_bands[end] as usize;
            let beyond_end_abs: f32 = denorm[active_end..].iter().map(|x| x.abs()).sum();
            debug_trace!(
                "R pkt905 denorm beyond_end abs_sum={:.6} active_end={}",
                beyond_end_abs,
                active_end
            );
        }
        let mut denorm_right_for_stereo: Option<Vec<f32>> = None;
        if coded_channels == 2 {
            if let Some(side) = mdct_side.as_ref() {
                let mut denorm_side = vec![0.0f32; frame_size_48k];
                bands::denormalise_bands(
                    self.mode,
                    side,
                    &mut denorm_side,
                    &self.prev_energy[self.mode.nb_ebands..self.mode.nb_ebands * 2],
                    start,
                    end,
                    lm,
                    silence_flag,
                );
                if self.channels == 1 {
                    let sum_l_before: f32 = denorm.iter().map(|x| x.abs()).sum();
                    let sum_r_before: f32 = denorm_side.iter().map(|x| x.abs()).sum();
                    if trace_this_packet {
                        // #region agent log
                        debug_trace!(
                            "R pkt{} denorm abs_sum: L={:.6} R={:.6}",
                            packet_idx,
                            sum_l_before,
                            sum_r_before
                        );
                        // #endregion
                    }
                    if packet_idx == 2135 {
                        debug_trace!(
                            "R pkt2135 denorm abs_sum: L={:.6} R={:.6}",
                            sum_l_before,
                            sum_r_before
                        );
                    }
                    if packet_idx == 2128 {
                        let m = 1usize << lm;
                        for i in 0..end {
                            let s = self.mode.e_bands[i] as usize * m;
                            let e = self.mode.e_bands[i + 1] as usize * m;
                            let l_sum: f32 = denorm[s..e].iter().sum();
                            let r_sum: f32 = denorm_side[s..e].iter().sum();
                            let mono_sum: f32 = (l_sum + r_sum) * 0.5;
                            debug_trace!(
                                "R pkt2128 band{} L={:.6} R={:.6} mono={:.6}",
                                i,
                                l_sum,
                                r_sum,
                                mono_sum
                            );
                        }
                    }
                    if debug_packet {
                        let data = format!(
                            "{{\"packet_idx\":{},\"sum_l_before\":{},\"sum_r_before\":{},\"frame_size_48k\":{},\"lm\":{}}}",
                            packet_idx, sum_l_before, sum_r_before, frame_size_48k, lm
                        );
                        // #region agent log
                        Self::append_debug_log(
                            "run-onset-map-v1",
                            "H2",
                            "crates/opus-decoder/src/celt/mod.rs:decode_frame",
                            "stereo_denorm_before_downmix",
                            &data,
                        );
                        // #endregion
                    }
                    for i in 0..frame_size_48k {
                        denorm[i] = 0.5 * (denorm[i] + denorm_side[i]);
                    }
                    let sum_mono_after: f32 = denorm.iter().map(|x| x.abs()).sum();
                    if trace_this_packet {
                        // #region agent log
                        debug_trace!(
                            "R pkt{} mono abs_sum after downmix: {:.6}",
                            packet_idx,
                            sum_mono_after
                        );
                        // #endregion
                    }
                    if packet_idx == 2135 {
                        debug_trace!(
                            "R pkt2135 mono abs_sum after downmix: {:.6}",
                            sum_mono_after
                        );
                    }
                    if debug_packet {
                        let data = format!(
                            "{{\"packet_idx\":{},\"sum_mono_after\":{},\"frame_size_48k\":{},\"lm\":{}}}",
                            packet_idx, sum_mono_after, frame_size_48k, lm
                        );
                        // #region agent log
                        Self::append_debug_log(
                            "run-onset-map-v1",
                            "H2",
                            "crates/opus-decoder/src/celt/mod.rs:decode_frame",
                            "stereo_denorm_after_downmix",
                            &data,
                        );
                        // #endregion
                    }
                } else if self.channels == 2 {
                    denorm_right_for_stereo = Some(denorm_side);
                }
            }
        } else if self.channels == 2 {
            // Mono-coded packet routed to stereo output: identical spectrum on both channels.
            denorm_right_for_stereo = Some(denorm.clone());
        }
        if packet_idx == 0 && is_transient {
            let m_blocks = 1usize << lm;
            let mut b0_csv = String::new();
            for bi in start..end {
                let bs = m_blocks * self.mode.e_bands[bi] as usize;
                let be = m_blocks * self.mode.e_bands[bi + 1] as usize;
                let mut acc = 0.0f32;
                let mut j = bs;
                while j < be {
                    acc += denorm[j].abs();
                    j += m_blocks;
                }
                if !b0_csv.is_empty() {
                    b0_csv.push(',');
                }
                b0_csv.push_str(&format!("{:.6}", acc));
            }
            // #region agent log
            if let Ok(mut f) = std::fs::OpenOptions::new()
                .create(true)
                .append(true)
                .open("/Users/tadeusz/Opus/Rasopus/.cursor/debug-bea564.log")
            {
                let ts = std::time::SystemTime::now()
                    .duration_since(std::time::UNIX_EPOCH)
                    .map(|d| d.as_millis())
                    .unwrap_or(0);
                let line = format!(
                    "{{\"sessionId\":\"4200c5\",\"runId\":\"run-band-block0-scan\",\"hypothesisId\":\"H25\",\"location\":\"crates/opus-decoder/src/celt/mod.rs:734\",\"message\":\"denorm_band_block0_abs\",\"data\":{{\"packet_idx\":{},\"start\":{},\"end\":{},\"lm\":{},\"band_block0_abs_csv\":\"{}\"}},\"timestamp\":{}}}\n",
                    packet_idx, start, end, lm, b0_csv, ts
                );
                let _ = std::io::Write::write_all(&mut f, line.as_bytes());
            }
            // #endregion
        }

        if packet_idx == 0 || (2127..=2129).contains(&packet_idx) {
            let overlap_tail = self.overlap_tail();
            let ovl_sum: f32 = overlap_tail.iter().map(|x| x.abs()).sum();
            let ovl_n = overlap_tail.len().min(8);
            debug_trace!(
                "R pkt{} overlap_in len={} abs_sum={:.6} first8: {:?}",
                packet_idx,
                overlap_tail.len(),
                ovl_sum,
                &overlap_tail[..ovl_n]
            );
            // #region agent log
            if let Ok(mut f) = std::fs::OpenOptions::new()
                .create(true)
                .append(true)
                .open("/Users/tadeusz/Opus/Rasopus/.cursor/debug-bea564.log")
            {
                let ts = std::time::SystemTime::now()
                    .duration_since(std::time::UNIX_EPOCH)
                    .map(|d| d.as_millis())
                    .unwrap_or(0);
                let line = format!(
                    "{{\"sessionId\":\"4200c5\",\"runId\":\"run-postfilter-overlap-scan\",\"hypothesisId\":\"H12\",\"location\":\"crates/opus-decoder/src/celt/mod.rs:734\",\"message\":\"overlap_in_snapshot\",\"data\":{{\"packet_idx\":{},\"coded_channels\":{},\"overlap_len\":{},\"overlap_abs_sum\":{},\"overlap_first8\":[{:.6},{:.6},{:.6},{:.6},{:.6},{:.6},{:.6},{:.6}]}} ,\"timestamp\":{}}}\n",
                    packet_idx,
                    coded_channels,
                    overlap_tail.len(),
                    ovl_sum,
                    overlap_tail.first().copied().unwrap_or(0.0),
                    overlap_tail.get(1).copied().unwrap_or(0.0),
                    overlap_tail.get(2).copied().unwrap_or(0.0),
                    overlap_tail.get(3).copied().unwrap_or(0.0),
                    overlap_tail.get(4).copied().unwrap_or(0.0),
                    overlap_tail.get(5).copied().unwrap_or(0.0),
                    overlap_tail.get(6).copied().unwrap_or(0.0),
                    overlap_tail.get(7).copied().unwrap_or(0.0),
                    ts
                );
                let _ = std::io::Write::write_all(&mut f, line.as_bytes());
            }
            // #endregion
        }

        // Match libopus buffer flow: shift history before new synthesis.
        let decode_len = DECODE_BUFFER_SIZE + self.mode.overlap;
        self.decode_mem.copy_within(frame_size_48k..decode_len, 0);
        let out_start = Self::out_start(frame_size_48k);
        if packet_idx == 6 {
            let overlap_src_end = out_start + self.mode.overlap;
            let ovl = &self.decode_mem[out_start..overlap_src_end];
            let ovl_abs_sum: f32 = ovl.iter().map(|x| x.abs()).sum();
            let data = format!(
                "{{\"packet_idx\":{},\"stage\":\"post_shift_overlap_source\",\"overlap_abs_sum\":{},\"overlap_first4\":[{:.9},{:.9},{:.9},{:.9}]}}",
                packet_idx,
                ovl_abs_sum,
                ovl.first().copied().unwrap_or(0.0),
                ovl.get(1).copied().unwrap_or(0.0),
                ovl.get(2).copied().unwrap_or(0.0),
                ovl.get(3).copied().unwrap_or(0.0)
            );
            // #region agent log
            Self::append_runtime_debug_log(
                "run-pkt6-overlap-entry-v1",
                "H62",
                "crates/opus-decoder/src/celt/mod.rs:decode_frame",
                "rust_pkt6_post_shift_overlap_source",
                &data,
            );
            // #endregion
        }
        if packet_idx == 5 {
            let overlap_tail = self.overlap_tail();
            let ovl_abs_sum: f32 = overlap_tail.iter().map(|x| x.abs()).sum();
            let data = format!(
                "{{\"packet_idx\":{},\"stage\":\"post_shift\",\"overlap_tail_abs_sum\":{},\"overlap_tail_first4\":[{:.9},{:.9},{:.9},{:.9}],\"shift_applied\":true}}",
                packet_idx,
                ovl_abs_sum,
                overlap_tail.first().copied().unwrap_or(0.0),
                overlap_tail.get(1).copied().unwrap_or(0.0),
                overlap_tail.get(2).copied().unwrap_or(0.0),
                overlap_tail.get(3).copied().unwrap_or(0.0)
            );
            // #region agent log
            Self::append_runtime_debug_log(
                "run-post-imdct-state-pkt5-v1",
                "H34",
                "crates/opus-decoder/src/celt/mod.rs:decode_frame",
                "rust_post_shift_overlap_tail",
                &data,
            );
            // #endregion
        }

        let mut base_synth = vec![0.0f32; frame_size_48k];
        let trace_transient_pkt5 =
            packet_idx == 5 && is_transient && lm == 3 && frame_size_48k == 960;
        let trace_long_pkt6 = packet_idx == 6 && !is_transient && lm == 3 && frame_size_48k == 960;
        if packet_idx == 0 || (2127..=2129).contains(&packet_idx) {
            // #region agent log
            if let Ok(mut f) = std::fs::OpenOptions::new()
                .create(true)
                .append(true)
                .open("/Users/tadeusz/Opus/Rasopus/.cursor/debug-bea564.log")
            {
                let ts = std::time::SystemTime::now()
                    .duration_since(std::time::UNIX_EPOCH)
                    .map(|d| d.as_millis())
                    .unwrap_or(0);
                let denorm_abs: f32 = denorm.iter().map(|x| x.abs()).sum();
                let line = format!(
                    "{{\"sessionId\":\"4200c5\",\"runId\":\"run-synthesis-path-scan\",\"hypothesisId\":\"H15\",\"location\":\"crates/opus-decoder/src/celt/mod.rs:769\",\"message\":\"synthesis_path_decision\",\"data\":{{\"packet_idx\":{},\"coded_channels\":{},\"is_transient\":{},\"lm\":{},\"frame_size_48k\":{},\"postfilter_active\":{},\"postfilter_pitch\":{},\"denorm_abs_sum\":{}}},\"timestamp\":{}}}\n",
                    packet_idx,
                    coded_channels,
                    is_transient,
                    lm,
                    frame_size_48k,
                    postfilter_pitch > 0,
                    postfilter_pitch,
                    denorm_abs,
                    ts
                );
                let _ = std::io::Write::write_all(&mut f, line.as_bytes());
            }
            // #endregion
        }
        if trace_transient_pkt5 {
            let denorm_abs: f32 = denorm.iter().map(|x| x.abs()).sum();
            let data = format!(
                "{{\"packet_idx\":{},\"stage\":\"after_denormalise_bands\",\"lm\":{},\"is_transient\":{},\"frame_size\":{},\"denorm_abs_sum\":{},\"denorm_first8\":[{:.9},{:.9},{:.9},{:.9},{:.9},{:.9},{:.9},{:.9}]}}",
                packet_idx,
                lm,
                is_transient,
                frame_size_48k,
                denorm_abs,
                denorm.get(0).copied().unwrap_or(0.0),
                denorm.get(1).copied().unwrap_or(0.0),
                denorm.get(2).copied().unwrap_or(0.0),
                denorm.get(3).copied().unwrap_or(0.0),
                denorm.get(4).copied().unwrap_or(0.0),
                denorm.get(5).copied().unwrap_or(0.0),
                denorm.get(6).copied().unwrap_or(0.0),
                denorm.get(7).copied().unwrap_or(0.0)
            );
            // #region agent log
            Self::append_runtime_debug_log(
                "run-transient-imdct-pkt5-v1",
                "H30",
                "crates/opus-decoder/src/celt/mod.rs:decode_frame",
                "rust_denorm_after_denormalise",
                &data,
            );
            // #endregion
        }
        if trace_long_pkt6 {
            let denorm_abs: f32 = denorm.iter().map(|x| x.abs()).sum();
            let data = format!(
                "{{\"packet_idx\":{},\"stage\":\"after_denormalise_bands\",\"lm\":{},\"is_transient\":{},\"frame_size\":{},\"denorm_abs_sum\":{},\"denorm_first8\":[{:.9},{:.9},{:.9},{:.9},{:.9},{:.9},{:.9},{:.9}]}}",
                packet_idx,
                lm,
                is_transient,
                frame_size_48k,
                denorm_abs,
                denorm.get(0).copied().unwrap_or(0.0),
                denorm.get(1).copied().unwrap_or(0.0),
                denorm.get(2).copied().unwrap_or(0.0),
                denorm.get(3).copied().unwrap_or(0.0),
                denorm.get(4).copied().unwrap_or(0.0),
                denorm.get(5).copied().unwrap_or(0.0),
                denorm.get(6).copied().unwrap_or(0.0),
                denorm.get(7).copied().unwrap_or(0.0)
            );
            // #region agent log
            Self::append_runtime_debug_log(
                "run-pkt6-long-imdct-v1",
                "H81",
                "crates/opus-decoder/src/celt/mod.rs:decode_frame",
                "rust_pkt6_denorm_after_denormalise",
                &data,
            );
            // #endregion
        }
        if is_transient {
            // Transient CELT frames are composed of 120-sample short blocks.
            let m_blocks = 1usize << lm;
            let short_len = frame_size_48k / m_blocks;
            let mut short_coeffs = vec![0.0f32; short_len];

            for b in 0..m_blocks {
                let out_offset = out_start + b * short_len;
                let overlap_in_abs: f32 = self.decode_mem
                    [out_offset..out_offset + self.mode.overlap]
                    .iter()
                    .map(|x| x.abs())
                    .sum();
                for j in 0..short_len {
                    short_coeffs[j] = denorm[j * m_blocks + b];
                }
                if trace_transient_pkt5 {
                    let coeff_abs_sum: f32 = short_coeffs.iter().map(|x| x.abs()).sum();
                    let data = format!(
                        "{{\"packet_idx\":{},\"stage\":\"after_deinterleave_before_imdct\",\"block\":{},\"blocks_total\":{},\"short_len\":{},\"coeff_abs_sum\":{},\"coeff_first5\":[{:.9},{:.9},{:.9},{:.9},{:.9}]}}",
                        packet_idx,
                        b,
                        m_blocks,
                        short_len,
                        coeff_abs_sum,
                        short_coeffs.get(0).copied().unwrap_or(0.0),
                        short_coeffs.get(1).copied().unwrap_or(0.0),
                        short_coeffs.get(2).copied().unwrap_or(0.0),
                        short_coeffs.get(3).copied().unwrap_or(0.0),
                        short_coeffs.get(4).copied().unwrap_or(0.0)
                    );
                    // #region agent log
                    Self::append_runtime_debug_log(
                        "run-transient-imdct-pkt5-v1",
                        "H31",
                        "crates/opus-decoder/src/celt/mod.rs:decode_frame",
                        "rust_short_block_deinterleaved",
                        &data,
                    );
                    // #endregion
                }
                let short_coeff_abs: f32 = short_coeffs.iter().map(|x| x.abs()).sum();
                let short_coeff_abs_contig: f32 = (0..short_len)
                    .map(|j| denorm[b * short_len + j].abs())
                    .sum();
                self.mdct_short
                    .backward(
                        &short_coeffs,
                        self.mode.window,
                        &mut self.decode_mem
                            [out_offset..out_offset + short_len + self.mode.overlap],
                    )
                    .map_err(|_| Error::NotImplemented)?;
                if trace_transient_pkt5 {
                    let block_out =
                        &self.decode_mem[out_offset..out_offset + short_len + self.mode.overlap];
                    let block_abs_sum: f32 = block_out.iter().map(|x| x.abs()).sum();
                    let data = format!(
                        "{{\"packet_idx\":{},\"stage\":\"after_short_imdct_before_overlap_chain\",\"block\":{},\"blocks_total\":{},\"block_out_len\":{},\"block_out_abs_sum\":{},\"block_out_first5\":[{:.9},{:.9},{:.9},{:.9},{:.9}]}}",
                        packet_idx,
                        b,
                        m_blocks,
                        block_out.len(),
                        block_abs_sum,
                        block_out.get(0).copied().unwrap_or(0.0),
                        block_out.get(1).copied().unwrap_or(0.0),
                        block_out.get(2).copied().unwrap_or(0.0),
                        block_out.get(3).copied().unwrap_or(0.0),
                        block_out.get(4).copied().unwrap_or(0.0)
                    );
                    // #region agent log
                    Self::append_runtime_debug_log(
                        "run-transient-imdct-pkt5-v1",
                        "H32",
                        "crates/opus-decoder/src/celt/mod.rs:decode_frame",
                        "rust_short_block_imdct_out",
                        &data,
                    );
                    // #endregion
                }
                if (packet_idx == 0 || (2127..=2129).contains(&packet_idx))
                    && (packet_idx == 0 || b == 0 || b + 1 == m_blocks)
                {
                    // #region agent log
                    if let Ok(mut f) = std::fs::OpenOptions::new()
                        .create(true)
                        .append(true)
                        .open("/Users/tadeusz/Opus/Rasopus/.cursor/debug-bea564.log")
                    {
                        let ts = std::time::SystemTime::now()
                            .duration_since(std::time::UNIX_EPOCH)
                            .map(|d| d.as_millis())
                            .unwrap_or(0);
                        let head_abs: f32 = self.decode_mem
                            [out_offset..out_offset + self.mode.overlap]
                            .iter()
                            .map(|x| x.abs())
                            .sum();
                        let tail_abs: f32 = self.decode_mem
                            [out_offset + short_len..out_offset + short_len + self.mode.overlap]
                            .iter()
                            .map(|x| x.abs())
                            .sum();
                        let line = format!(
                            "{{\"sessionId\":\"4200c5\",\"runId\":\"run-synthesis-path-scan\",\"hypothesisId\":\"H16\",\"location\":\"crates/opus-decoder/src/celt/mod.rs:804\",\"message\":\"short_mdct_block_snapshot\",\"data\":{{\"packet_idx\":{},\"block\":{},\"blocks_total\":{},\"short_len\":{},\"overlap\":{},\"overlap_in_abs_sum\":{},\"short_coeff_abs_sum\":{},\"short_coeff_abs_contig\":{},\"short_tmp_head_abs_sum\":{},\"short_tmp_tail_abs_sum\":{}}},\"timestamp\":{}}}\n",
                            packet_idx,
                            b,
                            m_blocks,
                            short_len,
                            self.mode.overlap,
                            overlap_in_abs,
                            short_coeff_abs,
                            short_coeff_abs_contig,
                            head_abs,
                            tail_abs,
                            ts
                        );
                        let _ = std::io::Write::write_all(&mut f, line.as_bytes());
                    }
                    // #endregion
                }
            }
            base_synth.copy_from_slice(&self.decode_mem[out_start..out_start + frame_size_48k]);
            if trace_transient_pkt5 {
                let overlap_tail = self.overlap_tail();
                let base_abs_sum: f32 = base_synth.iter().map(|x| x.abs()).sum();
                let overlap_abs_sum: f32 = overlap_tail.iter().map(|x| x.abs()).sum();
                let data = format!(
                    "{{\"packet_idx\":{},\"stage\":\"after_overlap_add_all_short_blocks\",\"result_abs_sum\":{},\"result_first8\":[{:.9},{:.9},{:.9},{:.9},{:.9},{:.9},{:.9},{:.9}],\"new_overlap_abs_sum\":{},\"new_overlap_first8\":[{:.9},{:.9},{:.9},{:.9},{:.9},{:.9},{:.9},{:.9}]}}",
                    packet_idx,
                    base_abs_sum,
                    base_synth.get(0).copied().unwrap_or(0.0),
                    base_synth.get(1).copied().unwrap_or(0.0),
                    base_synth.get(2).copied().unwrap_or(0.0),
                    base_synth.get(3).copied().unwrap_or(0.0),
                    base_synth.get(4).copied().unwrap_or(0.0),
                    base_synth.get(5).copied().unwrap_or(0.0),
                    base_synth.get(6).copied().unwrap_or(0.0),
                    base_synth.get(7).copied().unwrap_or(0.0),
                    overlap_abs_sum,
                    overlap_tail.first().copied().unwrap_or(0.0),
                    overlap_tail.get(1).copied().unwrap_or(0.0),
                    overlap_tail.get(2).copied().unwrap_or(0.0),
                    overlap_tail.get(3).copied().unwrap_or(0.0),
                    overlap_tail.get(4).copied().unwrap_or(0.0),
                    overlap_tail.get(5).copied().unwrap_or(0.0),
                    overlap_tail.get(6).copied().unwrap_or(0.0),
                    overlap_tail.get(7).copied().unwrap_or(0.0)
                );
                // #region agent log
                Self::append_runtime_debug_log(
                    "run-transient-imdct-pkt5-v1",
                    "H33",
                    "crates/opus-decoder/src/celt/mod.rs:decode_frame",
                    "rust_transient_overlap_result",
                    &data,
                );
                // #endregion
            }
        } else {
            // Long block path for all supported frame sizes.
            let mdct_impl = match frame_size_48k {
                120 => &self.mdct_short,
                240 => &self.mdct_240,
                480 => &self.mdct_480,
                960 => &self.mdct,
                _ => return Err(Error::NotImplemented),
            };
            mdct_impl
                .backward(
                    &denorm,
                    self.mode.window,
                    &mut self.decode_mem[out_start..out_start + frame_size_48k + self.mode.overlap],
                )
                .map_err(|_| Error::NotImplemented)?;
            if trace_long_pkt6 {
                let out_abs_sum: f32 = self.decode_mem[out_start..out_start + frame_size_48k]
                    .iter()
                    .map(|x| x.abs())
                    .sum();
                let ovl_abs_sum: f32 = self.decode_mem
                    [out_start + frame_size_48k..out_start + frame_size_48k + self.mode.overlap]
                    .iter()
                    .map(|x| x.abs())
                    .sum();
                let data = format!(
                    "{{\"packet_idx\":{},\"stage\":\"after_long_imdct\",\"out_abs_sum\":{},\"out_first8\":[{:.9},{:.9},{:.9},{:.9},{:.9},{:.9},{:.9},{:.9}],\"new_overlap_abs_sum\":{},\"new_overlap_first8\":[{:.9},{:.9},{:.9},{:.9},{:.9},{:.9},{:.9},{:.9}]}}",
                    packet_idx,
                    out_abs_sum,
                    self.decode_mem.get(out_start).copied().unwrap_or(0.0),
                    self.decode_mem.get(out_start + 1).copied().unwrap_or(0.0),
                    self.decode_mem.get(out_start + 2).copied().unwrap_or(0.0),
                    self.decode_mem.get(out_start + 3).copied().unwrap_or(0.0),
                    self.decode_mem.get(out_start + 4).copied().unwrap_or(0.0),
                    self.decode_mem.get(out_start + 5).copied().unwrap_or(0.0),
                    self.decode_mem.get(out_start + 6).copied().unwrap_or(0.0),
                    self.decode_mem.get(out_start + 7).copied().unwrap_or(0.0),
                    ovl_abs_sum,
                    self.decode_mem
                        .get(out_start + frame_size_48k)
                        .copied()
                        .unwrap_or(0.0),
                    self.decode_mem
                        .get(out_start + frame_size_48k + 1)
                        .copied()
                        .unwrap_or(0.0),
                    self.decode_mem
                        .get(out_start + frame_size_48k + 2)
                        .copied()
                        .unwrap_or(0.0),
                    self.decode_mem
                        .get(out_start + frame_size_48k + 3)
                        .copied()
                        .unwrap_or(0.0),
                    self.decode_mem
                        .get(out_start + frame_size_48k + 4)
                        .copied()
                        .unwrap_or(0.0),
                    self.decode_mem
                        .get(out_start + frame_size_48k + 5)
                        .copied()
                        .unwrap_or(0.0),
                    self.decode_mem
                        .get(out_start + frame_size_48k + 6)
                        .copied()
                        .unwrap_or(0.0),
                    self.decode_mem
                        .get(out_start + frame_size_48k + 7)
                        .copied()
                        .unwrap_or(0.0)
                );
                // #region agent log
                Self::append_runtime_debug_log(
                    "run-pkt6-long-imdct-v1",
                    "H82",
                    "crates/opus-decoder/src/celt/mod.rs:decode_frame",
                    "rust_pkt6_long_imdct_out",
                    &data,
                );
                // #endregion
            }
            if packet_idx == 0 || (2127..=2129).contains(&packet_idx) {
                // #region agent log
                if let Ok(mut f) = std::fs::OpenOptions::new()
                    .create(true)
                    .append(true)
                    .open("/Users/tadeusz/Opus/Rasopus/.cursor/debug-bea564.log")
                {
                    let ts = std::time::SystemTime::now()
                        .duration_since(std::time::UNIX_EPOCH)
                        .map(|d| d.as_millis())
                        .unwrap_or(0);
                    let head_abs: f32 = self.decode_mem[out_start..out_start + self.mode.overlap]
                        .iter()
                        .map(|x| x.abs())
                        .sum();
                    let tail_abs: f32 = self.decode_mem[out_start + frame_size_48k
                        ..out_start + frame_size_48k + self.mode.overlap]
                        .iter()
                        .map(|x| x.abs())
                        .sum();
                    let line = format!(
                        "{{\"sessionId\":\"4200c5\",\"runId\":\"run-synthesis-path-scan\",\"hypothesisId\":\"H17\",\"location\":\"crates/opus-decoder/src/celt/mod.rs:854\",\"message\":\"long_mdct_snapshot\",\"data\":{{\"packet_idx\":{},\"frame_size_48k\":{},\"overlap\":{},\"mdct_head_abs_sum\":{},\"mdct_tail_abs_sum\":{}}},\"timestamp\":{}}}\n",
                        packet_idx, frame_size_48k, self.mode.overlap, head_abs, tail_abs, ts
                    );
                    let _ = std::io::Write::write_all(&mut f, line.as_bytes());
                }
                // #endregion
            }
            base_synth.copy_from_slice(&self.decode_mem[out_start..out_start + frame_size_48k]);
        }
        if packet_idx == 0 || (2127..=2129).contains(&packet_idx) {
            let overlap_tail = self.overlap_tail();
            let ovl_sum: f32 = overlap_tail.iter().map(|x| x.abs()).sum();
            let ovl_n = overlap_tail.len().min(8);
            debug_trace!(
                "R pkt{} overlap_out len={} abs_sum={:.6} first8: {:?}",
                packet_idx,
                overlap_tail.len(),
                ovl_sum,
                &overlap_tail[..ovl_n]
            );
            // #region agent log
            if let Ok(mut f) = std::fs::OpenOptions::new()
                .create(true)
                .append(true)
                .open("/Users/tadeusz/Opus/Rasopus/.cursor/debug-bea564.log")
            {
                let ts = std::time::SystemTime::now()
                    .duration_since(std::time::UNIX_EPOCH)
                    .map(|d| d.as_millis())
                    .unwrap_or(0);
                let line = format!(
                    "{{\"sessionId\":\"4200c5\",\"runId\":\"run-postfilter-overlap-scan\",\"hypothesisId\":\"H13\",\"location\":\"crates/opus-decoder/src/celt/mod.rs:793\",\"message\":\"overlap_out_snapshot\",\"data\":{{\"packet_idx\":{},\"coded_channels\":{},\"overlap_len\":{},\"overlap_abs_sum\":{},\"overlap_first8\":[{:.6},{:.6},{:.6},{:.6},{:.6},{:.6},{:.6},{:.6}]}} ,\"timestamp\":{}}}\n",
                    packet_idx,
                    coded_channels,
                    overlap_tail.len(),
                    ovl_sum,
                    overlap_tail.first().copied().unwrap_or(0.0),
                    overlap_tail.get(1).copied().unwrap_or(0.0),
                    overlap_tail.get(2).copied().unwrap_or(0.0),
                    overlap_tail.get(3).copied().unwrap_or(0.0),
                    overlap_tail.get(4).copied().unwrap_or(0.0),
                    overlap_tail.get(5).copied().unwrap_or(0.0),
                    overlap_tail.get(6).copied().unwrap_or(0.0),
                    overlap_tail.get(7).copied().unwrap_or(0.0),
                    ts
                );
                let _ = std::io::Write::write_all(&mut f, line.as_bytes());
            }
            // #endregion
        }
        if packet_idx == 905 {
            let post_imdct_abs: f32 = base_synth.iter().map(|x| x.abs()).sum();
            let first_n = base_synth.len().min(8);
            debug_trace!(
                "R pkt905 post_imdct abs_sum={:.6} first{}={:?}",
                post_imdct_abs,
                first_n,
                &base_synth[..first_n]
            );
        }
        if debug_packet {
            let post_imdct_abs: f32 = base_synth.iter().map(|x| x.abs()).sum();
            let overlap_abs_out: f32 = self.overlap_tail().iter().map(|x| x.abs()).sum();
            let mut first8_csv = String::new();
            for x in base_synth.iter().take(8) {
                if !first8_csv.is_empty() {
                    first8_csv.push(';');
                }
                first8_csv.push_str(&format!("{:.6}", x));
            }
            let data = format!(
                "{{\"packet_idx\":{},\"is_transient\":{},\"lm\":{},\"post_imdct_abs\":{},\"overlap_abs_out\":{},\"base_first8_csv\":\"{}\"}}",
                packet_idx, is_transient, lm, post_imdct_abs, overlap_abs_out, first8_csv
            );
            // #region agent log
            Self::append_debug_log(
                "run-onset-map-v1",
                "H2",
                "crates/opus-decoder/src/celt/mod.rs:decode_frame",
                "post_imdct_overlap_out",
                &data,
            );
            // #endregion
        }
        if trace_this_packet {
            let post_imdct_abs: f32 = base_synth.iter().map(|x| x.abs()).sum();
            // #region agent log
            debug_trace!(
                "R pkt{} comb_pre c=0 abs_sum={:.6}",
                packet_idx,
                post_imdct_abs
            );
            debug_trace!(
                "R pkt{} comb_pre_first8 c=0: {:.6} {:.6} {:.6} {:.6} {:.6} {:.6} {:.6} {:.6}",
                packet_idx,
                base_synth.first().copied().unwrap_or(0.0),
                base_synth.get(1).copied().unwrap_or(0.0),
                base_synth.get(2).copied().unwrap_or(0.0),
                base_synth.get(3).copied().unwrap_or(0.0),
                base_synth.get(4).copied().unwrap_or(0.0),
                base_synth.get(5).copied().unwrap_or(0.0),
                base_synth.get(6).copied().unwrap_or(0.0),
                base_synth.get(7).copied().unwrap_or(0.0),
            );
            // #endregion
        }
        if trace_this_packet && self.channels == 2 {
            let right_pre = &self.decode_mem_right[out_start..out_start + frame_size_48k];
            let right_pre_abs: f32 = right_pre.iter().map(|x| x.abs()).sum();
            // #region agent log
            debug_trace!(
                "R pkt{} comb_pre c=1 abs_sum={:.6}",
                packet_idx,
                right_pre_abs
            );
            debug_trace!(
                "R pkt{} comb_pre_first8 c=1: {:.6} {:.6} {:.6} {:.6} {:.6} {:.6} {:.6} {:.6}",
                packet_idx,
                right_pre.first().copied().unwrap_or(0.0),
                right_pre.get(1).copied().unwrap_or(0.0),
                right_pre.get(2).copied().unwrap_or(0.0),
                right_pre.get(3).copied().unwrap_or(0.0),
                right_pre.get(4).copied().unwrap_or(0.0),
                right_pre.get(5).copied().unwrap_or(0.0),
                right_pre.get(6).copied().unwrap_or(0.0),
                right_pre.get(7).copied().unwrap_or(0.0),
            );
            // #endregion
        }
        if self.channels == 2 {
            let window = self.mode.window;
            let overlap = self.mode.overlap;
            if let Some(denorm_right) = denorm_right_for_stereo.as_deref() {
                Self::synthesise_channel_to_mem(
                    denorm_right,
                    frame_size_48k,
                    lm,
                    is_transient,
                    window,
                    overlap,
                    &self.mdct,
                    &self.mdct_480,
                    &self.mdct_240,
                    &self.mdct_short,
                    &mut self.decode_mem_right,
                )?;
            } else {
                Self::synthesise_channel_to_mem(
                    &denorm,
                    frame_size_48k,
                    lm,
                    is_transient,
                    window,
                    overlap,
                    &self.mdct,
                    &self.mdct_480,
                    &self.mdct_240,
                    &self.mdct_short,
                    &mut self.decode_mem_right,
                )?;
            }
        }

        if packet_idx == 5 {
            let overlap_tail = self.overlap_tail();
            let ovl_abs_sum: f32 = overlap_tail.iter().map(|x| x.abs()).sum();
            let data = format!(
                "{{\"packet_idx\":{},\"stage\":\"post_imdct\",\"overlap_tail_abs_sum\":{},\"overlap_tail_first4\":[{:.9},{:.9},{:.9},{:.9}]}}",
                packet_idx,
                ovl_abs_sum,
                overlap_tail.first().copied().unwrap_or(0.0),
                overlap_tail.get(1).copied().unwrap_or(0.0),
                overlap_tail.get(2).copied().unwrap_or(0.0),
                overlap_tail.get(3).copied().unwrap_or(0.0)
            );
            // #region agent log
            Self::append_runtime_debug_log(
                "run-post-imdct-state-pkt5-v1",
                "H34",
                "crates/opus-decoder/src/celt/mod.rs:decode_frame",
                "rust_post_imdct_overlap_tail",
                &data,
            );
            // #endregion
        }

        let postfilter_before_abs: f32 = self.decode_mem[out_start..out_start + frame_size_48k]
            .iter()
            .map(|x| x.abs())
            .sum();
        let pre_s163 = if frame_size_48k > 163 {
            self.decode_mem[out_start + 163]
        } else {
            0.0
        };
        if packet_idx == 0 && frame_size_48k >= self.mode.short_mdct_size {
            let chunk = self.mode.short_mdct_size;
            let chunks = frame_size_48k / chunk;
            if chunks == 8 {
                let mut chunk_abs = [0.0f32; 8];
                for b in 0..8 {
                    let s = b * chunk;
                    let e = s + chunk;
                    chunk_abs[b] = self.decode_mem[out_start + s..out_start + e]
                        .iter()
                        .map(|x| x.abs())
                        .sum();
                }
                // #region agent log
                if let Ok(mut f) = std::fs::OpenOptions::new()
                    .create(true)
                    .append(true)
                    .open("/Users/tadeusz/Opus/Rasopus/.cursor/debug-bea564.log")
                {
                    let ts = std::time::SystemTime::now()
                        .duration_since(std::time::UNIX_EPOCH)
                        .map(|d| d.as_millis())
                        .unwrap_or(0);
                    let line = format!(
                        "{{\"sessionId\":\"4200c5\",\"runId\":\"run-precomb-chunk-scan\",\"hypothesisId\":\"H23\",\"location\":\"crates/opus-decoder/src/celt/mod.rs:1018\",\"message\":\"precomb_chunk_abs\",\"data\":{{\"packet_idx\":{},\"chunk_size\":{},\"chunk_abs\":[{:.6},{:.6},{:.6},{:.6},{:.6},{:.6},{:.6},{:.6}],\"total_abs\":{}}},\"timestamp\":{}}}\n",
                        packet_idx,
                        chunk,
                        chunk_abs[0],
                        chunk_abs[1],
                        chunk_abs[2],
                        chunk_abs[3],
                        chunk_abs[4],
                        chunk_abs[5],
                        chunk_abs[6],
                        chunk_abs[7],
                        postfilter_before_abs,
                        ts
                    );
                    let _ = std::io::Write::write_all(&mut f, line.as_bytes());
                }
                // #endregion
            }
        }
        let pf_period = self.postfilter_period.max(COMBFILTER_MINPERIOD as i32) as usize;
        let pf_period_old = self.postfilter_period_old.max(COMBFILTER_MINPERIOD as i32) as usize;
        let decoded_tapset = postfilter_tapset.clamp(0, 2) as usize;
        let short_n = self.mode.short_mdct_size.min(frame_size_48k);
        let disable_comb_filter = false;
        if !disable_comb_filter {
            Self::comb_filter_in_place(
                &mut self.decode_mem,
                out_start,
                pf_period_old,
                pf_period,
                short_n,
                self.postfilter_gain_old,
                self.postfilter_gain,
                self.postfilter_tapset_old,
                self.postfilter_tapset,
                self.mode.window,
                self.mode.overlap,
            );
            if lm != 0 && frame_size_48k > short_n {
                Self::comb_filter_in_place(
                    &mut self.decode_mem,
                    out_start + short_n,
                    pf_period,
                    postfilter_pitch.max(0) as usize,
                    frame_size_48k - short_n,
                    self.postfilter_gain,
                    postfilter_gain,
                    self.postfilter_tapset,
                    decoded_tapset,
                    self.mode.window,
                    self.mode.overlap,
                );
            }
            if self.channels == 2 {
                Self::comb_filter_in_place(
                    &mut self.decode_mem_right,
                    out_start,
                    pf_period_old,
                    pf_period,
                    short_n,
                    self.postfilter_gain_old,
                    self.postfilter_gain,
                    self.postfilter_tapset_old,
                    self.postfilter_tapset,
                    self.mode.window,
                    self.mode.overlap,
                );
                if lm != 0 && frame_size_48k > short_n {
                    Self::comb_filter_in_place(
                        &mut self.decode_mem_right,
                        out_start + short_n,
                        pf_period,
                        postfilter_pitch.max(0) as usize,
                        frame_size_48k - short_n,
                        self.postfilter_gain,
                        postfilter_gain,
                        self.postfilter_tapset,
                        decoded_tapset,
                        self.mode.window,
                        self.mode.overlap,
                    );
                }
            }
        }
        if packet_idx == 5 {
            let overlap_start = DECODE_BUFFER_SIZE;
            let overlap_end = overlap_start + self.mode.overlap;
            let ovl = &self.decode_mem[overlap_start..overlap_end];
            let ovl_abs_sum: f32 = ovl.iter().map(|x| x.abs()).sum();
            let data = format!(
                "{{\"packet_idx\":{},\"stage\":\"post_comb\",\"overlap_tail_abs_sum\":{},\"overlap_tail_first4\":[{:.9},{:.9},{:.9},{:.9}],\"comb_filter_disabled\":{}}}",
                packet_idx,
                ovl_abs_sum,
                ovl.get(0).copied().unwrap_or(0.0),
                ovl.get(1).copied().unwrap_or(0.0),
                ovl.get(2).copied().unwrap_or(0.0),
                ovl.get(3).copied().unwrap_or(0.0),
                disable_comb_filter
            );
            // #region agent log
            Self::append_runtime_debug_log(
                "run-post-imdct-state-pkt5-v1",
                "H35",
                "crates/opus-decoder/src/celt/mod.rs:decode_frame",
                "rust_post_comb_overlap_tail",
                &data,
            );
            // #endregion
        }
        self.postfilter_period_old = self.postfilter_period;
        self.postfilter_gain_old = self.postfilter_gain;
        self.postfilter_tapset_old = self.postfilter_tapset;
        self.postfilter_period = postfilter_pitch;
        self.postfilter_gain = postfilter_gain;
        self.postfilter_tapset = decoded_tapset;
        if lm != 0 {
            self.postfilter_period_old = self.postfilter_period;
            self.postfilter_gain_old = self.postfilter_gain;
            self.postfilter_tapset_old = self.postfilter_tapset;
        }
        if packet_idx <= 2 || (2127..=2129).contains(&packet_idx) {
            let postfilter_after_abs: f32 = self.decode_mem[out_start..out_start + frame_size_48k]
                .iter()
                .map(|x| x.abs())
                .sum();
            let post_s163 = if frame_size_48k > 163 {
                self.decode_mem[out_start + 163]
            } else {
                0.0
            };
            let mut post_win_csv = String::new();
            if frame_size_48k > 170 {
                for i in 160..=170 {
                    if !post_win_csv.is_empty() {
                        post_win_csv.push(';');
                    }
                    post_win_csv.push_str(&format!("{i}:{:.6}", self.decode_mem[out_start + i]));
                }
            }
            let mut post_win2_csv = String::new();
            if frame_size_48k > 220 {
                for i in 200..=220 {
                    if !post_win2_csv.is_empty() {
                        post_win2_csv.push(';');
                    }
                    post_win2_csv.push_str(&format!("{i}:{:.6}", self.decode_mem[out_start + i]));
                }
            }
            let mut post_prefix_csv = String::new();
            if packet_idx == 0 && frame_size_48k > 170 {
                for i in 0..=170 {
                    if !post_prefix_csv.is_empty() {
                        post_prefix_csv.push(';');
                    }
                    post_prefix_csv.push_str(&format!("{i}:{:.6}", self.decode_mem[out_start + i]));
                }
            }
            // #region agent log
            if let Ok(mut f) = std::fs::OpenOptions::new()
                .create(true)
                .append(true)
                .open("/Users/tadeusz/Opus/Rasopus/.cursor/debug-bea564.log")
            {
                let ts = std::time::SystemTime::now()
                    .duration_since(std::time::UNIX_EPOCH)
                    .map(|d| d.as_millis())
                    .unwrap_or(0);
                let line = format!(
                    "{{\"sessionId\":\"4200c5\",\"runId\":\"run-postfilter-apply\",\"hypothesisId\":\"H19\",\"location\":\"crates/opus-decoder/src/celt/mod.rs:980\",\"message\":\"comb_filter_applied\",\"data\":{{\"packet_idx\":{},\"lm\":{},\"postfilter_pitch\":{},\"postfilter_gain\":{},\"postfilter_tapset\":{},\"state_pitch\":{},\"state_gain\":{},\"state_tapset\":{},\"pre_abs_sum\":{},\"post_abs_sum\":{},\"pre_s163\":{},\"post_s163\":{},\"post_win_csv\":\"{}\",\"post_win2_csv\":\"{}\",\"post_prefix_csv\":\"{}\"}},\"timestamp\":{}}}\n",
                    packet_idx,
                    lm,
                    postfilter_pitch,
                    postfilter_gain,
                    decoded_tapset,
                    pf_period,
                    self.postfilter_gain,
                    self.postfilter_tapset,
                    postfilter_before_abs,
                    postfilter_after_abs,
                    pre_s163,
                    post_s163,
                    post_win_csv,
                    post_win2_csv,
                    post_prefix_csv,
                    ts
                );
                let _ = std::io::Write::write_all(&mut f, line.as_bytes());
            }
            // #endregion
        }
        if packet_idx == 905 {
            let post_comb = &self.decode_mem[out_start..out_start + frame_size_48k];
            let post_comb_abs: f32 = post_comb.iter().map(|x| x.abs()).sum();
            let first_n = post_comb.len().min(8);
            debug_trace!(
                "R pkt905 post_comb abs_sum={:.6} first{}={:?}",
                post_comb_abs,
                first_n,
                &post_comb[..first_n]
            );
        }
        if debug_packet {
            let post_comb = &self.decode_mem[out_start..out_start + frame_size_48k];
            let post_comb_abs: f32 = post_comb.iter().map(|x| x.abs()).sum();
            let mut first8_csv = String::new();
            for x in post_comb.iter().take(8) {
                if !first8_csv.is_empty() {
                    first8_csv.push(';');
                }
                first8_csv.push_str(&format!("{:.6}", x));
            }
            let post_s163 = if frame_size_48k > 163 {
                post_comb[163]
            } else {
                0.0
            };
            let mut post_win_160_170_csv = String::new();
            if frame_size_48k > 170 {
                for i in 160..=170 {
                    if !post_win_160_170_csv.is_empty() {
                        post_win_160_170_csv.push(';');
                    }
                    post_win_160_170_csv.push_str(&format!("{}:{:.6}", i, post_comb[i]));
                }
            }
            let data = format!(
                "{{\"packet_idx\":{},\"disable_comb_filter\":{},\"post_comb_abs\":{},\"post_comb_first8_csv\":\"{}\",\"post_comb_s163\":{},\"post_comb_160_170_csv\":\"{}\",\"pf_period_old_in\":{},\"pf_period_in\":{},\"pf_period_old_out\":{},\"pf_period_out\":{},\"pf_gain_old_out\":{},\"pf_gain_out\":{},\"pf_tapset_old_out\":{},\"pf_tapset_out\":{}}}",
                packet_idx,
                disable_comb_filter,
                post_comb_abs,
                first8_csv,
                post_s163,
                post_win_160_170_csv,
                pf_period_old,
                pf_period,
                self.postfilter_period_old,
                self.postfilter_period,
                self.postfilter_gain_old,
                self.postfilter_gain,
                self.postfilter_tapset_old,
                self.postfilter_tapset
            );
            // #region agent log
            Self::append_debug_log(
                "run-onset-map-v2",
                "H2",
                "crates/opus-decoder/src/celt/mod.rs:decode_frame",
                "post_comb_and_pf_state",
                &data,
            );
            // #endregion
        }
        if trace_this_packet {
            let post_comb = &self.decode_mem[out_start..out_start + frame_size_48k];
            let post_comb_abs: f32 = post_comb.iter().map(|x| x.abs()).sum();
            // #region agent log
            debug_trace!(
                "R pkt{} comb_post c=0 abs_sum={:.6} first16: {:.6} {:.6} {:.6} {:.6} {:.6} {:.6} {:.6} {:.6} {:.6} {:.6} {:.6} {:.6} {:.6} {:.6} {:.6} {:.6}",
                packet_idx,
                post_comb_abs,
                post_comb.first().copied().unwrap_or(0.0),
                post_comb.get(1).copied().unwrap_or(0.0),
                post_comb.get(2).copied().unwrap_or(0.0),
                post_comb.get(3).copied().unwrap_or(0.0),
                post_comb.get(4).copied().unwrap_or(0.0),
                post_comb.get(5).copied().unwrap_or(0.0),
                post_comb.get(6).copied().unwrap_or(0.0),
                post_comb.get(7).copied().unwrap_or(0.0),
                post_comb.get(8).copied().unwrap_or(0.0),
                post_comb.get(9).copied().unwrap_or(0.0),
                post_comb.get(10).copied().unwrap_or(0.0),
                post_comb.get(11).copied().unwrap_or(0.0),
                post_comb.get(12).copied().unwrap_or(0.0),
                post_comb.get(13).copied().unwrap_or(0.0),
                post_comb.get(14).copied().unwrap_or(0.0),
                post_comb.get(15).copied().unwrap_or(0.0),
            );
            // #endregion
        }
        if trace_this_packet && self.channels == 2 {
            let post_comb_r = &self.decode_mem_right[out_start..out_start + frame_size_48k];
            let post_comb_r_abs: f32 = post_comb_r.iter().map(|x| x.abs()).sum();
            // #region agent log
            debug_trace!(
                "R pkt{} comb_post c=1 abs_sum={:.6} first16: {:.6} {:.6} {:.6} {:.6} {:.6} {:.6} {:.6} {:.6} {:.6} {:.6} {:.6} {:.6} {:.6} {:.6} {:.6} {:.6}",
                packet_idx,
                post_comb_r_abs,
                post_comb_r.first().copied().unwrap_or(0.0),
                post_comb_r.get(1).copied().unwrap_or(0.0),
                post_comb_r.get(2).copied().unwrap_or(0.0),
                post_comb_r.get(3).copied().unwrap_or(0.0),
                post_comb_r.get(4).copied().unwrap_or(0.0),
                post_comb_r.get(5).copied().unwrap_or(0.0),
                post_comb_r.get(6).copied().unwrap_or(0.0),
                post_comb_r.get(7).copied().unwrap_or(0.0),
                post_comb_r.get(8).copied().unwrap_or(0.0),
                post_comb_r.get(9).copied().unwrap_or(0.0),
                post_comb_r.get(10).copied().unwrap_or(0.0),
                post_comb_r.get(11).copied().unwrap_or(0.0),
                post_comb_r.get(12).copied().unwrap_or(0.0),
                post_comb_r.get(13).copied().unwrap_or(0.0),
                post_comb_r.get(14).copied().unwrap_or(0.0),
                post_comb_r.get(15).copied().unwrap_or(0.0),
            );
            if frame_size_48k > 750 {
                debug_trace!(
                    "R pkt{} comb_post_740_750 c=1: {:.6} {:.6} {:.6} {:.6} {:.6} {:.6} {:.6} {:.6} {:.6} {:.6} {:.6}",
                    packet_idx,
                    post_comb_r[740],
                    post_comb_r[741],
                    post_comb_r[742],
                    post_comb_r[743],
                    post_comb_r[744],
                    post_comb_r[745],
                    post_comb_r[746],
                    post_comb_r[747],
                    post_comb_r[748],
                    post_comb_r[749],
                    post_comb_r[750],
                );
            }
            // #endregion
        }
        if packet_idx == 5 {
            let overlap_tail = self.overlap_tail();
            let ovl_abs_sum: f32 = overlap_tail.iter().map(|x| x.abs()).sum();
            let data = format!(
                "{{\"packet_idx\":{},\"stage\":\"final_state\",\"overlap_abs_sum\":{},\"overlap_first4\":[{:.9},{:.9},{:.9},{:.9}],\"separate_overlap_buffer\":false}}",
                packet_idx,
                ovl_abs_sum,
                overlap_tail.first().copied().unwrap_or(0.0),
                overlap_tail.get(1).copied().unwrap_or(0.0),
                overlap_tail.get(2).copied().unwrap_or(0.0),
                overlap_tail.get(3).copied().unwrap_or(0.0)
            );
            // #region agent log
            Self::append_runtime_debug_log(
                "run-post-imdct-state-pkt5-v1",
                "H36",
                "crates/opus-decoder/src/celt/mod.rs:decode_frame",
                "rust_final_state_overlap",
                &data,
            );
            // #endregion
        }

        let nb_ebands = self.mode.nb_ebands;
        let state_len = 2 * nb_ebands;
        if coded_channels == 1 {
            let (left, right) = self.prev_energy.split_at_mut(nb_ebands);
            right.copy_from_slice(left);
        }
        if !is_transient {
            self.old_log_energy2[..state_len].copy_from_slice(&self.old_log_energy[..state_len]);
            self.old_log_energy[..state_len].copy_from_slice(&self.prev_energy[..state_len]);
        } else {
            for i in 0..state_len {
                self.old_log_energy[i] = self.old_log_energy[i].min(self.prev_energy[i]);
            }
        }
        let m = 1usize << lm;
        let max_background_increase = (m.min(160) as f32) * 0.001;
        for i in 0..state_len {
            self.background_log_energy[i] =
                (self.background_log_energy[i] + max_background_increase).min(self.prev_energy[i]);
        }
        for c in 0..2usize {
            let base = c * nb_ebands;
            for i in 0..start.min(nb_ebands) {
                self.prev_energy[base + i] = 0.0;
                self.old_log_energy[base + i] = LOG_ENERGY_FLOOR_DB;
                self.old_log_energy2[base + i] = LOG_ENERGY_FLOOR_DB;
            }
            for i in end.min(nb_ebands)..nb_ebands {
                self.prev_energy[base + i] = 0.0;
                self.old_log_energy[base + i] = LOG_ENERGY_FLOOR_DB;
                self.old_log_energy2[base + i] = LOG_ENERGY_FLOOR_DB;
            }
        }
        if trace_this_packet {
            debug_trace!(
                "R pkt{} energy_state_post prev={:?} old1={:?} old2={:?}",
                packet_idx,
                &self.prev_energy[..nb_ebands],
                &self.old_log_energy[..nb_ebands],
                &self.old_log_energy2[..nb_ebands]
            );
        }

        if packet_idx == 6 {
            let pre = &self.decode_mem[out_start..out_start + frame_size_48k];
            let pre_abs_sum: f32 = pre.iter().map(|x| x.abs()).sum();
            let data = format!(
                "{{\"packet_idx\":{},\"stage\":\"pre_deemph\",\"pre_abs_sum\":{},\"pre_first8\":[{:.9},{:.9},{:.9},{:.9},{:.9},{:.9},{:.9},{:.9}],\"deemph_mem0_in\":{}}}",
                packet_idx,
                pre_abs_sum,
                pre.get(0).copied().unwrap_or(0.0),
                pre.get(1).copied().unwrap_or(0.0),
                pre.get(2).copied().unwrap_or(0.0),
                pre.get(3).copied().unwrap_or(0.0),
                pre.get(4).copied().unwrap_or(0.0),
                pre.get(5).copied().unwrap_or(0.0),
                pre.get(6).copied().unwrap_or(0.0),
                pre.get(7).copied().unwrap_or(0.0),
                self.deemph_mem[0]
            );
            // #region agent log
            Self::append_runtime_debug_log(
                "run-pkt6-deemph-v1",
                "H71",
                "crates/opus-decoder/src/celt/mod.rs:decode_frame",
                "rust_pkt6_pre_deemph",
                &data,
            );
            // #endregion
        }
        for ch in 0..self.channels as usize {
            let decode_mem_ch = if ch == 0 {
                &self.decode_mem
            } else {
                &self.decode_mem_right
            };
            let mut ch_synth = decode_mem_ch[out_start..out_start + frame_size_48k].to_vec();
            self.apply_deemph(ch, &mut ch_synth);
            if packet_idx == 6 && ch == 0 {
                let post_abs_sum: f32 = ch_synth.iter().map(|x| x.abs()).sum();
                let data = format!(
                    "{{\"packet_idx\":{},\"stage\":\"post_deemph\",\"post_abs_sum\":{},\"post_first8\":[{:.9},{:.9},{:.9},{:.9},{:.9},{:.9},{:.9},{:.9}],\"deemph_mem0_out\":{}}}",
                    packet_idx,
                    post_abs_sum,
                    ch_synth.get(0).copied().unwrap_or(0.0),
                    ch_synth.get(1).copied().unwrap_or(0.0),
                    ch_synth.get(2).copied().unwrap_or(0.0),
                    ch_synth.get(3).copied().unwrap_or(0.0),
                    ch_synth.get(4).copied().unwrap_or(0.0),
                    ch_synth.get(5).copied().unwrap_or(0.0),
                    ch_synth.get(6).copied().unwrap_or(0.0),
                    ch_synth.get(7).copied().unwrap_or(0.0),
                    self.deemph_mem[ch]
                );
                // #region agent log
                Self::append_runtime_debug_log(
                    "run-pkt6-deemph-v1",
                    "H72",
                    "crates/opus-decoder/src/celt/mod.rs:decode_frame",
                    "rust_pkt6_post_deemph",
                    &data,
                );
                // #endregion
            }
            if packet_idx == 905 && ch == 0 {
                let post_deemph_abs: f32 = ch_synth.iter().map(|x| x.abs()).sum();
                let first_n = ch_synth.len().min(8);
                debug_trace!(
                    "R pkt905 post_deemph abs_sum={:.6} first{}={:?}",
                    post_deemph_abs,
                    first_n,
                    &ch_synth[..first_n]
                );
            }
            if debug_packet && ch == 0 {
                let post_deemph_abs: f32 = ch_synth.iter().map(|x| x.abs()).sum();
                let mut first8_csv = String::new();
                for x in ch_synth.iter().take(8) {
                    if !first8_csv.is_empty() {
                        first8_csv.push(';');
                    }
                    first8_csv.push_str(&format!("{:.6}", x));
                }
                let data = format!(
                    "{{\"packet_idx\":{},\"post_deemph_abs\":{},\"deemph_mem0_out\":{},\"post_deemph_first8_csv\":\"{}\"}}",
                    packet_idx, post_deemph_abs, self.deemph_mem[ch], first8_csv
                );
                // #region agent log
                Self::append_debug_log(
                    "run-onset-map-v1",
                    "H1",
                    "crates/opus-decoder/src/celt/mod.rs:decode_frame",
                    "post_deemph",
                    &data,
                );
                // #endregion
            }
            if trace_this_packet && ch == 0 {
                let post_deemph_abs: f32 = ch_synth.iter().map(|x| x.abs()).sum();
                // #region agent log
                debug_trace!(
                    "R pkt{} post_deemph_abs_sum={:.6}",
                    packet_idx,
                    post_deemph_abs
                );
                debug_trace!(
                    "R pkt{} pcm[0..8]: {} {} {} {} {} {} {} {}",
                    packet_idx,
                    Self::float_to_i16(ch_synth.first().copied().unwrap_or(0.0)),
                    Self::float_to_i16(ch_synth.get(1).copied().unwrap_or(0.0)),
                    Self::float_to_i16(ch_synth.get(2).copied().unwrap_or(0.0)),
                    Self::float_to_i16(ch_synth.get(3).copied().unwrap_or(0.0)),
                    Self::float_to_i16(ch_synth.get(4).copied().unwrap_or(0.0)),
                    Self::float_to_i16(ch_synth.get(5).copied().unwrap_or(0.0)),
                    Self::float_to_i16(ch_synth.get(6).copied().unwrap_or(0.0)),
                    Self::float_to_i16(ch_synth.get(7).copied().unwrap_or(0.0))
                );
                // #endregion
            }
            if trace_this_packet && ch == 1 {
                let post_deemph_abs: f32 = ch_synth.iter().map(|x| x.abs()).sum();
                // #region agent log
                debug_trace!(
                    "R pkt{} post_deemph_abs_sum_ch1={:.6}",
                    packet_idx,
                    post_deemph_abs
                );
                debug_trace!(
                    "R pkt{} pcm_ch1[0..8]: {} {} {} {} {} {} {} {}",
                    packet_idx,
                    Self::float_to_i16(ch_synth.first().copied().unwrap_or(0.0)),
                    Self::float_to_i16(ch_synth.get(1).copied().unwrap_or(0.0)),
                    Self::float_to_i16(ch_synth.get(2).copied().unwrap_or(0.0)),
                    Self::float_to_i16(ch_synth.get(3).copied().unwrap_or(0.0)),
                    Self::float_to_i16(ch_synth.get(4).copied().unwrap_or(0.0)),
                    Self::float_to_i16(ch_synth.get(5).copied().unwrap_or(0.0)),
                    Self::float_to_i16(ch_synth.get(6).copied().unwrap_or(0.0)),
                    Self::float_to_i16(ch_synth.get(7).copied().unwrap_or(0.0))
                );
                if frame_size_48k > 750 {
                    debug_trace!(
                        "R pkt{} pcm_ch1[740..750]: {} {} {} {} {} {} {} {} {} {} {}",
                        packet_idx,
                        Self::float_to_i16(ch_synth[740]),
                        Self::float_to_i16(ch_synth[741]),
                        Self::float_to_i16(ch_synth[742]),
                        Self::float_to_i16(ch_synth[743]),
                        Self::float_to_i16(ch_synth[744]),
                        Self::float_to_i16(ch_synth[745]),
                        Self::float_to_i16(ch_synth[746]),
                        Self::float_to_i16(ch_synth[747]),
                        Self::float_to_i16(ch_synth[748]),
                        Self::float_to_i16(ch_synth[749]),
                        Self::float_to_i16(ch_synth[750]),
                    );
                }
                debug_trace!(
                    "R pkt{} deemph_mem_ch1_out={:.6}",
                    packet_idx,
                    self.deemph_mem[1]
                );
                // #endregion
            }
            if packet_idx == 0 && ch == 0 && frame_size_48k > 170 {
                let mut win_csv = String::new();
                for i in 160..=170 {
                    if !win_csv.is_empty() {
                        win_csv.push(';');
                    }
                    let si = Self::float_to_i16(ch_synth[i]);
                    win_csv.push_str(&format!("{i}:{:.6}:{si}", ch_synth[i]));
                }
                let mut win2_csv = String::new();
                if frame_size_48k > 220 {
                    for i in 200..=220 {
                        if !win2_csv.is_empty() {
                            win2_csv.push(';');
                        }
                        let si = Self::float_to_i16(ch_synth[i]);
                        win2_csv.push_str(&format!("{i}:{:.6}:{si}", ch_synth[i]));
                    }
                }
                // #region agent log
                if let Ok(mut f) = std::fs::OpenOptions::new()
                    .create(true)
                    .append(true)
                    .open("/Users/tadeusz/Opus/Rasopus/.cursor/debug-bea564.log")
                {
                    let ts = std::time::SystemTime::now()
                        .duration_since(std::time::UNIX_EPOCH)
                        .map(|d| d.as_millis())
                        .unwrap_or(0);
                    let line = format!(
                        "{{\"sessionId\":\"4200c5\",\"runId\":\"run-deemph-scan\",\"hypothesisId\":\"H26\",\"location\":\"crates/opus-decoder/src/celt/mod.rs:1110\",\"message\":\"deemph_window_packet0\",\"data\":{{\"packet_idx\":{},\"channel\":{},\"window_csv\":\"{}\",\"window2_csv\":\"{}\"}},\"timestamp\":{}}}\n",
                        packet_idx, ch, win_csv, win2_csv, ts
                    );
                    let _ = std::io::Write::write_all(&mut f, line.as_bytes());
                }
                // #endregion
            }
            self.write_output_channel_i16(out, &ch_synth, ch, accum)?;
            if packet_idx == 6 && ch == 0 {
                let out_len = output_samples * self.channels as usize;
                let out_slice = &out[..out_len];
                let out_abs_sum: i64 = out_slice.iter().map(|&x| (x as i64).abs()).sum();
                let data = format!(
                    "{{\"packet_idx\":{},\"stage\":\"post_quant_i16\",\"out_abs_sum\":{},\"out_first8\":[{},{},{},{},{},{},{},{}]}}",
                    packet_idx,
                    out_abs_sum,
                    out_slice.get(0).copied().unwrap_or(0),
                    out_slice.get(1).copied().unwrap_or(0),
                    out_slice.get(2).copied().unwrap_or(0),
                    out_slice.get(3).copied().unwrap_or(0),
                    out_slice.get(4).copied().unwrap_or(0),
                    out_slice.get(5).copied().unwrap_or(0),
                    out_slice.get(6).copied().unwrap_or(0),
                    out_slice.get(7).copied().unwrap_or(0)
                );
                // #region agent log
                Self::append_runtime_debug_log(
                    "run-pkt6-deemph-v1",
                    "H73",
                    "crates/opus-decoder/src/celt/mod.rs:decode_frame",
                    "rust_pkt6_post_quant_i16",
                    &data,
                );
                // #endregion
            }
        }

        if packet_idx == 38 {
            // #region agent log H103
            if let Ok(mut f) = std::fs::OpenOptions::new()
                .create(true)
                .append(true)
                .open("/Users/tadeusz/Opus/Rasopus/.cursor/debug-9a4e1c.log")
            {
                let ts = std::time::SystemTime::now()
                    .duration_since(std::time::UNIX_EPOCH)
                    .map(|d| d.as_millis())
                    .unwrap_or(0);
                let line = format!(
                    "{{\"sessionId\":\"9a4e1c\",\"runId\":\"tv09-pkt38-celt-stage\",\"hypothesisId\":\"H103\",\"location\":\"crates/opus-decoder/src/celt/mod.rs:3300\",\"message\":\"pkt38_before_final_range\",\"data\":{{\"tell\":{},\"tell_frac\":{},\"rng\":{},\"seed\":{},\"is_transient\":{},\"anti_collapse_on\":{},\"lm\":{}}},\"timestamp\":{}}}\n",
                    ec.tell(),
                    ec.tell_frac(),
                    ec.rng(),
                    self.rng_seed,
                    is_transient,
                    anti_collapse_on,
                    lm,
                    ts
                );
                let _ = std::io::Write::write_all(&mut f, line.as_bytes());
            }
        }
        self.final_range = ec.final_range();
        if packet_idx < 5 {
            debug_trace!(
                "pkt{} final_range: got={:08x}",
                packet_idx,
                self.final_range
            );
        }
        self.rng_seed = ec.rng();
        let _ = (
            OVERLAP_48K_20MS,
            self.mode.preemph[0],
            self.mode.overlap,
            self.prev_energy.len(),
            alloc.intensity,
            alloc.dual_stereo,
            quant_bands::e_means()[0],
            postfilter_pitch,
            rate::pulses2bits(self.mode, 0, lm as i32, 0),
        );
        Ok(CeltFrameDecode {
            samples_per_channel: output_samples,
            is_transient,
        })
    }
}

/// Map CELT or Hybrid Opus config to active end band.
///
/// Params: TOC config value `(toc >> 3) & 0x1f`.
/// Returns: exclusive end band index.
fn bandwidth_end(config: u8) -> usize {
    match config {
        12 | 13 => 19,
        14 | 15 => 21,
        16..=19 => 13,
        20..=23 => 17,
        24..=27 => 19,
        28..=31 => 21,
        _ => 21,
    }
}

/// Decode CELT TF flags for active bands.
///
/// Params: band range, transient flag, mutable tf array, LM, frame bit budget and range decoder.
/// Returns: nothing; `tf_res` updated in-place.
fn tf_decode(
    start: usize,
    end: usize,
    is_transient: bool,
    tf_res: &mut [i32],
    lm: usize,
    total_bits: i32,
    dec: &mut EcDec<'_>,
) {
    let mut budget = total_bits.max(0);
    let mut tell = dec.tell();
    let mut logp = if is_transient { 2 } else { 4 };
    let tf_select_rsv = lm > 0 && tell + logp + 1 <= budget;
    if tf_select_rsv {
        budget = budget.saturating_sub(1);
    }
    let mut tf_changed = 0i32;
    let mut curr = 0i32;
    for i in start..end {
        if tell + logp <= budget {
            curr ^= i32::from(dec.dec_bit_logp(logp as u32));
            tell = dec.tell();
            tf_changed |= curr;
        }
        tf_res[i] = curr;
        logp = if is_transient { 4 } else { 5 };
    }
    let mut tf_select = 0i32;
    let idx0 = 4 * usize::from(is_transient) + tf_changed as usize;
    let idx1 = 4 * usize::from(is_transient) + 2 + tf_changed as usize;
    if tf_select_rsv && TF_SELECT_TABLE[lm][idx0] != TF_SELECT_TABLE[lm][idx1] {
        tf_select = i32::from(dec.dec_bit_logp(1));
    }
    for t in tf_res.iter_mut().take(end).skip(start) {
        let idx = 4 * usize::from(is_transient) + 2 * tf_select as usize + *t as usize;
        *t = TF_SELECT_TABLE[lm][idx] as i32;
    }
}
