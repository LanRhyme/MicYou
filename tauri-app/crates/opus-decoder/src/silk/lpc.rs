//! SILK fixed-point LPC helpers.

use super::tables::{LSF_COS_TAB, MAX_LPC_ORDER};

const QA: usize = 16;
const INV_PRED_GAIN_QA: usize = 24;
const MAX_LPC_STABILIZE_ITERATIONS: usize = 16;
const A_LIMIT_QA24: i32 = 16_773_022;
const MAX_PREDICTION_POWER_GAIN_INV_Q30: i32 = 107_374;

/// Convert NLSF values in Q15 to LPC coefficients in Q12.
///
/// Params: mutable `lpc_q12`, stabilized `nlsf_q15`, and LPC `order` (10 or 16).
/// Returns: nothing; `lpc_q12[..order]` is overwritten.
pub(super) fn nlsf2a(lpc_q12: &mut [i16], nlsf_q15: &[i16], order: usize) {
    let ordering = match order {
        10 => &[0usize, 9, 6, 3, 4, 5, 8, 1, 2, 7][..],
        16 => &[0usize, 15, 8, 7, 4, 11, 12, 3, 2, 13, 10, 5, 6, 9, 14, 1][..],
        _ => panic!("unsupported LPC order {order}"),
    };

    let mut cos_lsf_qa = [0i32; MAX_LPC_ORDER];
    for k in 0..order {
        let f_int = (nlsf_q15[k] as i32 >> 8) as usize;
        let f_frac = nlsf_q15[k] as i32 - ((f_int as i32) << 8);
        let cos_val = LSF_COS_TAB[f_int] as i32;
        let delta = LSF_COS_TAB[f_int + 1] as i32 - cos_val;
        let interp = (cos_val << 8) + delta * f_frac;
        cos_lsf_qa[ordering[k]] = rshift_round(interp, 20 - QA);
    }

    let dd = order / 2;
    let mut p = [0i32; MAX_LPC_ORDER / 2 + 1];
    let mut q = [0i32; MAX_LPC_ORDER / 2 + 1];
    nlsf2a_find_poly(&mut p, &cos_lsf_qa[0..], dd);
    nlsf2a_find_poly(&mut q, &cos_lsf_qa[1..], dd);

    let mut a32_qa1 = [0i32; MAX_LPC_ORDER];
    for k in 0..dd {
        let ptmp = p[k + 1] + p[k];
        let qtmp = q[k + 1] - q[k];
        a32_qa1[k] = -qtmp - ptmp;
        a32_qa1[order - k - 1] = qtmp - ptmp;
    }

    lpc_fit(lpc_q12, &mut a32_qa1, 12, QA + 1, order);
    for i in 0..MAX_LPC_STABILIZE_ITERATIONS {
        let inv_gain = lpc_inv_pred_gain(&lpc_q12[..order], order);
        if inv_gain != 0 {
            break;
        }
        bw_expand_32(&mut a32_qa1[..order], order, 65_536 - (2 << i));
        for k in 0..order {
            lpc_q12[k] = rshift_round(a32_qa1[k], QA + 1 - 12) as i16;
        }
    }
}

/// Compute inverse prediction gain and reject unstable LPC filters.
///
/// Params: `lpc_q12` LPC coefficients in Q12 and LPC `order`.
/// Returns: inverse prediction gain in Q30, or `0` if unstable.
pub(super) fn lpc_inv_pred_gain(lpc_q12: &[i16], order: usize) -> i32 {
    let mut a_tmp_qa = [0i32; MAX_LPC_ORDER];
    let mut dc_resp = 0i32;
    for k in 0..order {
        dc_resp += lpc_q12[k] as i32;
        a_tmp_qa[k] = (lpc_q12[k] as i32) << (INV_PRED_GAIN_QA - 12);
    }
    if dc_resp >= 4096 {
        return 0;
    }
    lpc_inverse_pred_gain_qa(&mut a_tmp_qa[..order], order)
}

/// Apply Q16 bandwidth expansion to Q12 LPC coefficients.
///
/// Params: mutable `lpc_q12`, LPC `order`, and `chirp_q16` factor.
/// Returns: nothing; coefficients are scaled in place.
#[allow(dead_code)]
pub(super) fn bw_expand(lpc_q12: &mut [i16], order: usize, chirp_q16: i32) {
    let mut chirp_q16_local = chirp_q16;
    let chirp_minus_one_q16 = chirp_q16_local - 65_536;
    for coeff in lpc_q12.iter_mut().take(order.saturating_sub(1)) {
        *coeff = rshift_round(((chirp_q16_local as i64) * (*coeff as i64)) as i32, 16) as i16;
        chirp_q16_local += rshift_round(
            ((chirp_q16_local as i64) * (chirp_minus_one_q16 as i64)) as i32,
            16,
        );
    }
    lpc_q12[order - 1] = rshift_round(
        ((chirp_q16_local as i64) * (lpc_q12[order - 1] as i64)) as i32,
        16,
    ) as i16;
}

/// Build the even or odd Chebyshev polynomial used by `nlsf2a`.
///
/// Params: mutable `out`, interleaved `c_lsf_qa`, and half-order `dd`.
/// Returns: nothing; `out[..=dd]` is written.
fn nlsf2a_find_poly(out: &mut [i32; MAX_LPC_ORDER / 2 + 1], c_lsf_qa: &[i32], dd: usize) {
    out[0] = 1 << QA;
    out[1] = -c_lsf_qa[0];
    for k in 1..dd {
        let ftmp = c_lsf_qa[2 * k];
        out[k + 1] = (out[k - 1] << 1) - rshift_round64((ftmp as i64) * out[k] as i64, QA);
        for n in (2..=k).rev() {
            out[n] += out[n - 2] - rshift_round64((ftmp as i64) * out[n - 1] as i64, QA);
        }
        out[1] -= ftmp;
    }
}

/// Fit wide intermediate LPC coefficients into `i16` Q12 range.
///
/// Params: mutable `lpc_q12`, mutable `a_qin`, output Q `qout`, input Q `qin`, and LPC `order`.
/// Returns: nothing; `lpc_q12[..order]` receives clipped or chirped coefficients.
fn lpc_fit(
    lpc_q12: &mut [i16],
    a_qin: &mut [i32; MAX_LPC_ORDER],
    qout: usize,
    qin: usize,
    order: usize,
) {
    let mut peak_index = 0usize;
    for _ in 0..10 {
        let mut max_abs = 0i32;
        for (idx, &value) in a_qin.iter().take(order).enumerate() {
            let abs_value = value.saturating_abs();
            if abs_value > max_abs {
                max_abs = abs_value;
                peak_index = idx;
            }
        }
        let shifted = rshift_round(max_abs, qin - qout);
        if shifted <= i16::MAX as i32 {
            for k in 0..order {
                lpc_q12[k] = rshift_round(a_qin[k], qin - qout) as i16;
            }
            return;
        }

        let capped = shifted.min(163_838);
        let numerator = ((capped - i16::MAX as i32) as i64) << 14;
        let denominator = ((capped as i64) * (peak_index as i64 + 1)) >> 2;
        let chirp_q16 = 65_470 - (numerator / denominator) as i32;
        bw_expand_32(&mut a_qin[..order], order, chirp_q16);
    }

    for k in 0..order {
        let rounded = rshift_round(a_qin[k], qin - qout);
        let clipped = rounded.clamp(i16::MIN as i32, i16::MAX as i32) as i16;
        lpc_q12[k] = clipped;
        a_qin[k] = (clipped as i32) << (qin - qout);
    }
}

/// Apply bandwidth expansion directly to 32-bit LPC intermediates.
///
/// Params: mutable `ar_qa1`, LPC `order`, and `chirp_q16` factor.
/// Returns: nothing; coefficients are chirped in place.
fn bw_expand_32(ar_qa1: &mut [i32], order: usize, chirp_q16: i32) {
    let mut chirp_q16_local = chirp_q16;
    let chirp_minus_one_q16 = chirp_q16_local - 65_536;
    for coeff in ar_qa1.iter_mut().take(order.saturating_sub(1)) {
        *coeff = ((*coeff as i64 * chirp_q16_local as i64) >> 16) as i32;
        chirp_q16_local += rshift_round(
            ((chirp_q16_local as i64) * (chirp_minus_one_q16 as i64)) as i32,
            16,
        );
    }
    ar_qa1[order - 1] = ((ar_qa1[order - 1] as i64 * chirp_q16_local as i64) >> 16) as i32;
}

/// Port of `LPC_inverse_pred_gain_QA_c` for Q24 LPC coefficients.
///
/// Params: mutable `a_qa` scratch LPC vector and LPC `order`.
/// Returns: inverse prediction gain in Q30, or `0` when unstable.
fn lpc_inverse_pred_gain_qa(a_qa: &mut [i32], order: usize) -> i32 {
    let mut inv_gain_q30 = 1 << 30;
    for k in (1..order).rev() {
        let coeff = a_qa[k];
        if !(-A_LIMIT_QA24..=A_LIMIT_QA24).contains(&coeff) {
            return 0;
        }

        let rc_q31 = -(coeff << (31 - INV_PRED_GAIN_QA));
        let rc_mult1_q30 = (1 << 30) - smmul(rc_q31, rc_q31);
        inv_gain_q30 = smmul(inv_gain_q30, rc_mult1_q30) << 2;
        if inv_gain_q30 < MAX_PREDICTION_POWER_GAIN_INV_Q30 {
            return 0;
        }

        let mult2_q = 32 - (rc_mult1_q30.unsigned_abs().leading_zeros() as usize);
        let rc_mult2 = inverse32_var_q(rc_mult1_q30, mult2_q + 30);
        for n in 0..((k + 1) >> 1) {
            let tmp1 = a_qa[n];
            let tmp2 = a_qa[k - n - 1];
            let lhs = tmp1.saturating_sub(mul32_frac_q(tmp2, rc_q31, 31));
            let rhs = tmp2.saturating_sub(mul32_frac_q(tmp1, rc_q31, 31));
            let next0 = rshift_round64((lhs as i64) * rc_mult2 as i64, mult2_q);
            let next1 = rshift_round64((rhs as i64) * rc_mult2 as i64, mult2_q);
            a_qa[n] = next0;
            a_qa[k - n - 1] = next1;
        }
    }

    if !(-A_LIMIT_QA24..=A_LIMIT_QA24).contains(&a_qa[0]) {
        return 0;
    }
    let rc_q31 = -(a_qa[0] << (31 - INV_PRED_GAIN_QA));
    let rc_mult1_q30 = (1 << 30) - smmul(rc_q31, rc_q31);
    inv_gain_q30 = smmul(inv_gain_q30, rc_mult1_q30) << 2;
    if inv_gain_q30 < MAX_PREDICTION_POWER_GAIN_INV_Q30 {
        0
    } else {
        inv_gain_q30
    }
}

/// Multiply two signed 32-bit values and keep the high 32 bits.
///
/// Params: signed `a` and `b`.
/// Returns: top-word signed product.
fn smmul(a: i32, b: i32) -> i32 {
    (((a as i64) * (b as i64)) >> 32) as i32
}

/// Multiply by a Q-domain fractional value.
///
/// Params: signed `a`, signed `b`, and fractional `q`.
/// Returns: rounded signed product in the original Q-domain.
fn mul32_frac_q(a: i32, b: i32, q: usize) -> i32 {
    rshift_round64((a as i64) * (b as i64), q)
}

/// Compute a saturating absolute value for normalization.
///
/// Params: signed `value`.
/// Returns: non-negative magnitude, saturating `i32::MIN`.
fn abs32_for_clz(value: i32) -> i32 {
    if value == i32::MIN {
        i32::MAX
    } else {
        value.abs()
    }
}

/// Multiply by the signed low 16-bit half of `b32` and keep high word.
///
/// Params: signed `a32` and signed `b32`.
/// Returns: high-word signed product.
fn smulwb(a32: i32, b32: i32) -> i32 {
    let b16 = b32 as i16 as i32;
    let high = (a32 >> 16) * b16;
    let low = ((a32 & 0xFFFF) * b16) >> 16;
    high.wrapping_add(low)
}

/// Approximate `a32 + ((b32 * c32) >> 16)` with wraparound semantics.
///
/// Params: accumulator `a32` and signed multiplicands `b32` and `c32`.
/// Returns: accumulated high-word product.
fn smlaww(a32: i32, b32: i32, c32: i32) -> i32 {
    a32.wrapping_add(((b32 as i64 * c32 as i64) >> 16) as i32)
}

/// Left-shift with saturation.
///
/// Params: signed `value` and non-negative `shift`.
/// Returns: left-shifted value clamped to `i32`.
fn lshift_sat32(value: i32, shift: usize) -> i32 {
    if shift >= 31 {
        if value > 0 {
            i32::MAX
        } else if value < 0 {
            i32::MIN
        } else {
            0
        }
    } else {
        value
            .clamp(i32::MIN >> shift, i32::MAX >> shift)
            .wrapping_shl(shift as u32)
    }
}

/// Approximate `(1 << q_res) / value` in fixed-point.
///
/// Params: signed non-zero `value` and target result Q `q_res`.
/// Returns: fixed-point inverse approximation.
fn inverse32_var_q(value: i32, q_res: usize) -> i32 {
    debug_assert!(value != 0);
    debug_assert!(q_res > 0);

    let b_headrm = abs32_for_clz(value).leading_zeros() as usize - 1;
    let b32_nrm = value << b_headrm;
    let b32_inv = (i32::MAX >> 2) / (b32_nrm >> 16);
    let mut result = b32_inv << 16;
    let err_q32 = ((1i32 << 29) - smulwb(b32_nrm, b32_inv)) << 3;
    result = smlaww(result, err_q32, b32_inv);

    let lshift = 61isize - b_headrm as isize - q_res as isize;
    if lshift <= 0 {
        lshift_sat32(result, (-lshift) as usize)
    } else if lshift < 32 {
        result >> lshift
    } else {
        0
    }
}

/// Round a signed 32-bit arithmetic right shift.
///
/// Params: signed `value` and positive `shift`.
/// Returns: rounded arithmetic shift result.
fn rshift_round(value: i32, shift: usize) -> i32 {
    if shift == 1 {
        (value >> 1) + (value & 1)
    } else {
        ((value >> (shift - 1)) + 1) >> 1
    }
}

/// Round a signed 64-bit arithmetic right shift.
///
/// Params: signed `value` and positive `shift`.
/// Returns: rounded arithmetic shift result.
fn rshift_round64(value: i64, shift: usize) -> i32 {
    if shift == 1 {
        ((value >> 1) + (value & 1)) as i32
    } else {
        (((value >> (shift - 1)) + 1) >> 1) as i32
    }
}
