//! SILK long-term prediction helpers.
#![allow(clippy::too_many_arguments)]

use super::tables::{
    LTP_ORDER, LTP_SCALES_Q14, LTP_VQ_0_Q7, LTP_VQ_1_Q7, LTP_VQ_2_Q7, MAX_NB_SUBFR,
};

/// Decode LTP codebook entries to Q14 taps for each active subframe.
///
/// Params: `per_index` codebook selector, per-subframe `ltp_indices`, active `nb_subfr`,
/// and mutable `ltp_scale_q14` output.
/// Returns: decoded per-subframe LTP taps in Q14.
pub(super) fn decode_ltp_coeffs(
    per_index: i8,
    ltp_indices: &[i8; MAX_NB_SUBFR],
    nb_subfr: usize,
    ltp_scale_index: i8,
) -> ([[i16; LTP_ORDER]; MAX_NB_SUBFR], i32) {
    let mut coeffs_q14 = [[0i16; LTP_ORDER]; MAX_NB_SUBFR];
    let ltp_scale_q14 = if per_index >= 0 {
        LTP_SCALES_Q14[ltp_scale_index as usize] as i32
    } else {
        0
    };

    for k in 0..nb_subfr {
        let row = codebook_row(per_index, ltp_indices[k] as usize);
        for i in 0..LTP_ORDER {
            coeffs_q14[k][i] = (row[i] as i16) << 7;
        }
    }

    (coeffs_q14, ltp_scale_q14)
}

/// Apply the 5-tap SILK LTP filter to a subframe.
///
/// Params: mutable `pres_q14`, Q14 `exc_q14`, Q14 `ltp_q15` history buffer, write `buf_idx`,
/// decoded `pitch_lag`, active `subfr_length`, and subframe `ltp_coefs_q14`.
/// Returns: nothing; `pres_q14` and `ltp_q15` are updated in place.
pub(super) fn ltp_filter(
    pres_q14: &mut [i32],
    exc_q14: &[i32],
    ltp_q15: &mut [i32],
    buf_idx: &mut usize,
    pitch_lag: usize,
    subfr_length: usize,
    ltp_coefs_q14: &[i16; LTP_ORDER],
    _packet_idx: usize,
    _frame_idx: usize,
    _subframe_idx: usize,
) {
    let start = *buf_idx - pitch_lag + (LTP_ORDER / 2);
    for i in 0..subfr_length {
        let pred_base = start + i;
        let mut ltp_pred_q13 = 2i32;
        ltp_pred_q13 = smlawb(ltp_pred_q13, ltp_q15[pred_base], ltp_coefs_q14[0] as i32);
        ltp_pred_q13 = smlawb(
            ltp_pred_q13,
            ltp_q15[pred_base - 1],
            ltp_coefs_q14[1] as i32,
        );
        ltp_pred_q13 = smlawb(
            ltp_pred_q13,
            ltp_q15[pred_base - 2],
            ltp_coefs_q14[2] as i32,
        );
        ltp_pred_q13 = smlawb(
            ltp_pred_q13,
            ltp_q15[pred_base - 3],
            ltp_coefs_q14[3] as i32,
        );
        ltp_pred_q13 = smlawb(
            ltp_pred_q13,
            ltp_q15[pred_base - 4],
            ltp_coefs_q14[4] as i32,
        );
        pres_q14[i] = exc_q14[i].saturating_add(ltp_pred_q13 << 1);
        ltp_q15[*buf_idx] = pres_q14[i] << 1;
        *buf_idx += 1;
    }
}

/// Select one LTP VQ row from the periodicity codebook.
///
/// Params: `per_index` codebook selector and row `index`.
/// Returns: reference to the codebook row in Q7.
fn codebook_row(per_index: i8, index: usize) -> &'static [i8; LTP_ORDER] {
    match per_index {
        0 => &LTP_VQ_0_Q7[index],
        1 => &LTP_VQ_1_Q7[index],
        2 => &LTP_VQ_2_Q7[index],
        _ => panic!("unsupported LTP periodicity index {per_index}"),
    }
}

/// Approximate `a32 + ((b32 * (i16)c32) >> 16)`.
///
/// Params: accumulator `a32`, multiplicand `b32`, and signed `c32`.
/// Returns: accumulated high-word product.
fn smlawb(a32: i32, b32: i32, c32: i32) -> i32 {
    let c16 = c32 as i16 as i32;
    let high = (b32 >> 16) * c16;
    let low = ((b32 & 0xFFFF) * c16) >> 16;
    a32.wrapping_add(high).wrapping_add(low)
}
