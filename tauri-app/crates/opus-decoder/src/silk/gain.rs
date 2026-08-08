//! SILK gain dequantization helpers.

use super::tables::{MAX_DELTA_GAIN_QUANT, MIN_DELTA_GAIN_QUANT, N_LEVELS_QGAIN};

const MIN_QGAIN_DB: i32 = 2;
const MAX_QGAIN_DB: i32 = 88;
const OFFSET: i32 = ((MIN_QGAIN_DB * 128) / 6) + 16 * 128;
const INV_SCALE_Q16: i32 =
    (65_536 * (((MAX_QGAIN_DB - MIN_QGAIN_DB) * 128) / 6)) / (N_LEVELS_QGAIN - 1);

/// Decode SILK gain indices to linear Q16 gains.
///
/// Params: `gain_indices` decoded entropy indices, mutable `prev_index` state,
/// `conditional` first-subframe coding mode, and active `nb_subfr`.
/// Returns: decoded per-subframe gains in Q16.
pub(super) fn decode_gains(
    gain_indices: &[i8],
    prev_index: &mut i8,
    conditional: bool,
    nb_subfr: usize,
) -> [i32; super::tables::MAX_NB_SUBFR] {
    let mut gains_q16 = [0i32; super::tables::MAX_NB_SUBFR];
    let mut prev = *prev_index as i32;

    for k in 0..nb_subfr {
        if k == 0 && !conditional {
            prev = (gain_indices[k] as i32).max(prev - 16);
        } else {
            let ind_tmp = gain_indices[k] as i32 + MIN_DELTA_GAIN_QUANT;
            let double_step_size_threshold = 2 * MAX_DELTA_GAIN_QUANT - N_LEVELS_QGAIN + prev;
            if ind_tmp > double_step_size_threshold {
                prev += (ind_tmp << 1) - double_step_size_threshold;
            } else {
                prev += ind_tmp;
            }
        }

        prev = prev.clamp(0, N_LEVELS_QGAIN - 1);
        gains_q16[k] = log2lin(smulwb(INV_SCALE_Q16, prev) + OFFSET);
    }

    *prev_index = prev as i8;
    gains_q16
}

/// Convert SILK log-domain gain to linear Q16.
///
/// Params: `in_log_q7` log-domain value in Q7.
/// Returns: linear gain in Q16-compatible integer form.
fn log2lin(in_log_q7: i32) -> i32 {
    if in_log_q7 < 0 {
        return 0;
    }
    if in_log_q7 >= 3967 {
        return i32::MAX;
    }

    let mut out = 1i32 << (in_log_q7 >> 7);
    let frac_q7 = in_log_q7 & 0x7F;
    let curve = smlawb(frac_q7, smulbb(frac_q7, 128 - frac_q7), -174);
    if in_log_q7 < 2048 {
        out = add_rshift32(out, out.saturating_mul(curve), 7);
    } else {
        out = out.saturating_add((out >> 7).saturating_mul(curve));
    }
    out
}

/// Approximate `(a32 * (i16)b32) >> 16`.
///
/// Params: signed `a32` and signed `b32`.
/// Returns: high-word signed product.
fn smulwb(a32: i32, b32: i32) -> i32 {
    let b16 = b32 as i16 as i32;
    let high = (a32 >> 16) * b16;
    let low = ((a32 & 0xFFFF) * b16) >> 16;
    high.wrapping_add(low)
}

/// Approximate `a32 + ((b32 * (i16)c32) >> 16)`.
///
/// Params: accumulator `a32`, multiplicand `b32`, and signed `c32`.
/// Returns: accumulated high-word product.
fn smlawb(a32: i32, b32: i32, c32: i32) -> i32 {
    a32.wrapping_add(smulwb(b32, c32))
}

/// Multiply lower 16-bit halves of two signed values.
///
/// Params: signed `a32` and `b32`.
/// Returns: signed 32-bit product.
fn smulbb(a32: i32, b32: i32) -> i32 {
    (a32 as i16 as i32) * (b32 as i16 as i32)
}

/// Add a rounded right-shifted value.
///
/// Params: base `a`, addend `b`, and arithmetic `shift`.
/// Returns: `a + round(b >> shift)`.
fn add_rshift32(a: i32, b: i32, shift: usize) -> i32 {
    a.saturating_add(rshift_round(b, shift))
}

/// Round a signed arithmetic right shift.
///
/// Params: signed `value` and positive `shift`.
/// Returns: rounded shifted value.
fn rshift_round(value: i32, shift: usize) -> i32 {
    if shift == 1 {
        (value >> 1) + (value & 1)
    } else {
        ((value >> (shift - 1)) + 1) >> 1
    }
}
