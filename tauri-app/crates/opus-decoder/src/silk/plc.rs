//! SILK packet loss concealment helpers.
//!
//! This module stores the subset of libopus PLC state needed by the current
//! Rust decoder and synthesizes one concealed SILK internal frame at a time.

use super::tables::{MAX_FRAME_LENGTH, MAX_LPC_ORDER, MAX_NB_SUBFR};

/// Metadata captured from one decoded SILK internal frame for PLC refresh.
#[derive(Debug, Clone)]
pub(super) struct SilkDecodedFrame {
    /// Pitch lag from the last voiced subframe, or zero for unvoiced frames.
    pub lag: i32,
    /// Collapsed per-subframe pitch gains in Q16.
    pub ltp_gains: [i32; MAX_NB_SUBFR],
    /// Synthesized internal-rate PCM for the decoded frame.
    pub output: Vec<i16>,
}

/// Mirrors the libopus PLC state needed by the Rust SILK concealment path.
#[derive(Debug, Clone)]
pub(super) struct SilkPlcState {
    /// Pitch lag in internal-rate samples from the last good voiced frame.
    pub pitch_lag: i32,
    /// Pitch gain per subframe in Q16.
    pub pitch_gain: [i32; MAX_NB_SUBFR],
    /// Saved LPC coefficients in Q12.
    pub lpc_coeffs: [i32; MAX_LPC_ORDER],
    /// Noise-shaping gain in Q14.
    pub shape_gain: i32,
    /// PLC pseudo-random seed.
    pub rand_seed: i32,
    /// Last synthesized internal-rate frame for pitch repetition.
    pub last_frame_out: Vec<i16>,
    /// Number of active subframes in one concealed internal frame.
    pub nb_subfr: usize,
    /// Samples per subframe at the internal SILK rate.
    pub subfr_length: usize,
    /// Internal sampling rate in kHz.
    pub fs_khz: i32,
    /// Active LPC order.
    pub nb_coefs: usize,
}

impl Default for SilkPlcState {
    /// Create PLC state with stable defaults for the first concealment frame.
    ///
    /// Parameters: none.
    /// Returns: initialized PLC state.
    fn default() -> Self {
        Self {
            pitch_lag: 0,
            pitch_gain: [0; MAX_NB_SUBFR],
            lpc_coeffs: [0; MAX_LPC_ORDER],
            shape_gain: 1 << 14,
            rand_seed: 1,
            last_frame_out: Vec::new(),
            nb_subfr: 0,
            subfr_length: 0,
            fs_khz: 0,
            nb_coefs: 0,
        }
    }
}

/// Save decoded SILK state needed for future PLC synthesis.
///
/// Parameters: mutable `plc` state, decoded `frame` metadata, saved `lpc_q12`,
/// frame layout (`nb_subfr`, `subfr_length`), `fs_khz`, and active `nb_coefs`.
/// Returns: nothing; `plc` is refreshed from the good frame.
pub(super) fn plc_update(
    plc: &mut SilkPlcState,
    frame: &SilkDecodedFrame,
    lpc_q12: &[i32],
    nb_subfr: usize,
    subfr_length: usize,
    fs_khz: i32,
    nb_coefs: usize,
) {
    plc.pitch_lag = frame.lag.max(0);
    plc.pitch_gain.fill(0);
    for (dst, src) in plc
        .pitch_gain
        .iter_mut()
        .zip(frame.ltp_gains.iter())
        .take(nb_subfr.min(MAX_NB_SUBFR))
    {
        *dst = (*src).clamp(0, 1 << 16);
    }

    plc.lpc_coeffs.fill(0);
    for (dst, src) in plc
        .lpc_coeffs
        .iter_mut()
        .zip(lpc_q12.iter())
        .take(nb_coefs.min(MAX_LPC_ORDER))
    {
        *dst = *src;
    }

    plc.nb_subfr = nb_subfr;
    plc.subfr_length = subfr_length;
    plc.fs_khz = fs_khz;
    plc.nb_coefs = nb_coefs.min(MAX_LPC_ORDER);
    plc.last_frame_out.clear();
    plc.last_frame_out.extend_from_slice(&frame.output);
}

/// Generate one concealed SILK internal frame.
///
/// Parameters: mutable `plc` state, mutable LPC synthesis `lpc_state`, mutable
/// PCM `out`, and packet-level consecutive `loss_count`.
/// Returns: nothing; `out` contains concealed PCM and state is updated in place.
pub(super) fn plc_conceal(
    plc: &mut SilkPlcState,
    lpc_state: &mut [i32],
    out: &mut [i16],
    loss_count: u32,
) {
    let frame_len = plc.subfr_length.saturating_mul(plc.nb_subfr);
    let nb_coefs = plc.nb_coefs.min(MAX_LPC_ORDER).min(lpc_state.len());
    if frame_len == 0 || out.len() != frame_len || nb_coefs == 0 {
        out.fill(0);
        return;
    }

    let attenuate_q16 = if loss_count == 0 {
        1 << 16
    } else {
        ((1 << 16) * (10 - loss_count.min(9)) as i32) / 10
    };
    let base_rand_scale_q16 = (plc.fs_khz.max(1) * 3) << 10;
    let noise_gain_q16 = mul_q14_q16(plc.shape_gain, base_rand_scale_q16);
    let mut synth_q14 = [0i32; MAX_LPC_ORDER + MAX_FRAME_LENGTH];
    synth_q14[..nb_coefs].copy_from_slice(&lpc_state[..nb_coefs]);

    for subframe_idx in 0..plc.nb_subfr {
        let gain_q16 = plc.pitch_gain[subframe_idx.min(MAX_NB_SUBFR - 1)];
        let pitch_offset = subframe_idx * plc.subfr_length;
        for sample_idx in 0..plc.subfr_length {
            let frame_idx = pitch_offset + sample_idx;
            let pitch_sample = plc_pitch_sample(plc, frame_idx);
            let mut excitation = mul_q16(pitch_sample, gain_q16);

            plc.rand_seed = plc
                .rand_seed
                .wrapping_mul(196_314_165)
                .wrapping_add(907_633_515);
            let noise = (plc.rand_seed >> 16) as i16 as i32;
            excitation = excitation.wrapping_add(mul_q16(noise, noise_gain_q16));

            let hist_idx = nb_coefs + frame_idx;
            let mut lpc_pred_q10 = (nb_coefs as i32) >> 1;
            for coef_idx in 0..nb_coefs {
                lpc_pred_q10 = smlawb(
                    lpc_pred_q10,
                    synth_q14[hist_idx - 1 - coef_idx],
                    plc.lpc_coeffs[coef_idx],
                );
            }

            let current_q14 = mul_q16(
                add_sat32(lshift_sat32(excitation, 14), lshift_sat32(lpc_pred_q10, 4)),
                attenuate_q16,
            );
            synth_q14[hist_idx] = current_q14;
            out[frame_idx] = sat16(rshift_round(current_q14, 14));
        }
    }

    lpc_state[..nb_coefs].copy_from_slice(&synth_q14[frame_len..frame_len + nb_coefs]);
    plc.last_frame_out.clear();
    plc.last_frame_out.extend_from_slice(out);
    plc.shape_gain = ((plc.shape_gain as i64 * 16_220) >> 14) as i32;
}

/// Read one pitch source sample from the saved PLC history.
///
/// Parameters: immutable `plc` state and linear `frame_idx` inside the concealed frame.
/// Returns: previous PCM sample used for pitch repetition, or zero when unavailable.
fn plc_pitch_sample(plc: &SilkPlcState, frame_idx: usize) -> i32 {
    if plc.pitch_lag <= 0 || plc.last_frame_out.is_empty() {
        return 0;
    }

    let lag = plc.pitch_lag as usize;
    let history_len = plc.last_frame_out.len();
    let lag_idx = frame_idx.wrapping_sub(lag) % history_len;
    plc.last_frame_out[lag_idx] as i32
}

/// Multiply a signed sample by a Q16 gain.
///
/// Parameters: signed `value` and `gain_q16`.
/// Returns: scaled signed sample.
fn mul_q16(value: i32, gain_q16: i32) -> i32 {
    ((value as i64 * gain_q16 as i64) >> 16) as i32
}

/// Multiply a Q14 value with a Q16 value and keep Q16 scaling.
///
/// Parameters: Q14 `value_q14` and Q16 `value_q16`.
/// Returns: Q16-scaled product.
fn mul_q14_q16(value_q14: i32, value_q16: i32) -> i32 {
    ((value_q14 as i64 * value_q16 as i64) >> 14) as i32
}

/// Approximate `a32 + ((b32 * (i16)c32) >> 16)`.
///
/// Parameters: accumulator `a32`, multiplicand `b32`, and signed `c32`.
/// Returns: accumulated high-word product.
fn smlawb(a32: i32, b32: i32, c32: i32) -> i32 {
    a32.wrapping_add(smulwb(b32, c32))
}

/// Multiply by the signed low 16-bit half of `b32` and keep high word.
///
/// Parameters: signed `a32` and signed `b32`.
/// Returns: high-word signed product.
fn smulwb(a32: i32, b32: i32) -> i32 {
    let b16 = b32 as i16 as i32;
    let high = (a32 >> 16) * b16;
    let low = ((a32 & 0xFFFF) * b16) >> 16;
    high.wrapping_add(low)
}

/// Saturating 32-bit addition.
///
/// Parameters: signed `a` and `b`.
/// Returns: saturated sum.
fn add_sat32(a: i32, b: i32) -> i32 {
    a.saturating_add(b)
}

/// Left-shift with saturation.
///
/// Parameters: signed `value` and non-negative `shift`.
/// Returns: saturated left-shifted value.
fn lshift_sat32(value: i32, shift: usize) -> i32 {
    let widened = (value as i64) << shift;
    widened.clamp(i64::from(i32::MIN), i64::from(i32::MAX)) as i32
}

/// Round a signed arithmetic right shift.
///
/// Parameters: signed `value` and positive `shift`.
/// Returns: rounded shifted value.
fn rshift_round(value: i32, shift: usize) -> i32 {
    if shift == 1 {
        (value >> 1) + (value & 1)
    } else {
        ((value >> (shift - 1)) + 1) >> 1
    }
}

/// Saturate a signed value to `i16`.
///
/// Parameters: signed `value`.
/// Returns: clamped PCM sample.
fn sat16(value: i32) -> i16 {
    value.clamp(i16::MIN as i32, i16::MAX as i32) as i16
}
