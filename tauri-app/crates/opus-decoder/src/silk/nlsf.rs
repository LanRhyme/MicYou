//! SILK NLSF reconstruction helpers.

use super::tables::{self, MAX_LPC_ORDER, NlsfCodebook};

const MAX_LOOPS: usize = 20;
const NLSF_QUANT_LEVEL_ADJ_Q10: i32 = 102;

/// Two-stage VQ decode: stage1 codebook plus predictive residuals.
///
/// Params: `nlsf_q15` output slice, `nlsf_indices` packed stage1+stage2 indices,
/// and `codebook` selecting NB/MB or WB tables.
/// Returns: nothing; `nlsf_q15[..order]` is filled with stabilized Q15 NLSFs.
pub(super) fn nlsf_decode(nlsf_q15: &mut [i16], nlsf_indices: &[i8], codebook: &NlsfCodebook) {
    let order = codebook.order;
    debug_assert!(nlsf_q15.len() >= order);
    debug_assert!(nlsf_indices.len() > order);

    let cb1_index = nlsf_indices[0] as usize;
    let mut pred_q8 = [0u8; MAX_LPC_ORDER];
    let mut res_q10 = [0i16; MAX_LPC_ORDER];
    unpack_predictors(&mut pred_q8, codebook, cb1_index);
    residual_dequant(
        &mut res_q10,
        &nlsf_indices[1..=order],
        &pred_q8,
        codebook.quant_step_size_q16,
        order,
    );

    let cb_base = cb1_index * order;
    for i in 0..order {
        let weighted_q15 = ((res_q10[i] as i32) << 14) / codebook.cb1_wght_q9[cb_base + i] as i32;
        let centroid_q15 = (codebook.cb1_nlsf_q8[cb_base + i] as i32) << 7;
        nlsf_q15[i] = (weighted_q15 + centroid_q15).clamp(0, 32_767) as i16;
    }

    nlsf_stabilize(nlsf_q15, order);
}

/// Stabilize NLSFs with the canonical SILK minimum-delta constraints.
///
/// Params: mutable `nlsf_q15` vector and LPC `order` (10 or 16).
/// Returns: nothing; the slice is modified in place.
pub(super) fn nlsf_stabilize(nlsf_q15: &mut [i16], order: usize) {
    let delta_min_q15 = delta_min_for_order(order);
    debug_assert!(nlsf_q15.len() >= order);

    for _ in 0..MAX_LOOPS {
        let mut min_diff_q15 = nlsf_q15[0] as i32 - delta_min_q15[0] as i32;
        let mut split = 0usize;

        for i in 1..order {
            let diff_q15 = nlsf_q15[i] as i32 - (nlsf_q15[i - 1] as i32 + delta_min_q15[i] as i32);
            if diff_q15 < min_diff_q15 {
                min_diff_q15 = diff_q15;
                split = i;
            }
        }

        let tail_diff_q15 = (1 << 15) - (nlsf_q15[order - 1] as i32 + delta_min_q15[order] as i32);
        if tail_diff_q15 < min_diff_q15 {
            min_diff_q15 = tail_diff_q15;
            split = order;
        }

        if min_diff_q15 >= 0 {
            return;
        }

        if split == 0 {
            nlsf_q15[0] = delta_min_q15[0];
        } else if split == order {
            nlsf_q15[order - 1] = ((1 << 15) - delta_min_q15[order] as i32) as i16;
        } else {
            let mut min_center_q15 = 0i32;
            for &delta in &delta_min_q15[..split] {
                min_center_q15 += delta as i32;
            }
            min_center_q15 += (delta_min_q15[split] as i32) >> 1;

            let mut max_center_q15 = 1 << 15;
            for &delta in &delta_min_q15[split + 1..=order] {
                max_center_q15 -= delta as i32;
            }
            max_center_q15 -= (delta_min_q15[split] as i32) >> 1;

            let center_q15 = limit32(
                rshift_round(nlsf_q15[split - 1] as i32 + nlsf_q15[split] as i32, 1),
                min_center_q15,
                max_center_q15,
            );
            nlsf_q15[split - 1] = (center_q15 - ((delta_min_q15[split] as i32) >> 1)) as i16;
            nlsf_q15[split] = (nlsf_q15[split - 1] as i32 + delta_min_q15[split] as i32) as i16;
        }
    }

    nlsf_q15[..order].sort_unstable();
    nlsf_q15[0] = nlsf_q15[0].max(delta_min_q15[0]);
    for i in 1..order {
        let min_allowed = nlsf_q15[i - 1].saturating_add(delta_min_q15[i]);
        nlsf_q15[i] = nlsf_q15[i].max(min_allowed);
    }
    let max_last = ((1 << 15) - delta_min_q15[order] as i32) as i16;
    nlsf_q15[order - 1] = nlsf_q15[order - 1].min(max_last);
    for i in (0..order - 1).rev() {
        let max_allowed = nlsf_q15[i + 1] - delta_min_q15[i + 1];
        nlsf_q15[i] = nlsf_q15[i].min(max_allowed);
    }
}

/// Unpack predictor choices from the packed `ec_sel` map.
///
/// Params: mutable `pred_q8`, source `codebook`, and stage-1 `cb1_index`.
/// Returns: nothing; predictor coefficients are written into `pred_q8[..order]`.
fn unpack_predictors(pred_q8: &mut [u8; MAX_LPC_ORDER], codebook: &NlsfCodebook, cb1_index: usize) {
    let order = codebook.order;
    let base = cb1_index * order / 2;
    let entries = &codebook.ec_sel[base..base + order / 2];
    for i in (0..order).step_by(2) {
        let entry = entries[i / 2];
        pred_q8[i] = codebook.pred_q8[i + ((entry & 1) as usize) * (order - 1)];
        pred_q8[i + 1] = codebook.pred_q8[i + (((entry >> 4) & 1) as usize) * (order - 1) + 1];
    }
}

/// Reconstruct predictive stage-2 residuals in Q10.
///
/// Params: mutable `res_q10`, signed stage-2 `indices`, predictor `pred_q8`,
/// residual step `quant_step_size_q16`, and LPC `order`.
/// Returns: nothing; `res_q10[..order]` is filled in reverse predictive order.
fn residual_dequant(
    res_q10: &mut [i16; MAX_LPC_ORDER],
    indices: &[i8],
    pred_q8: &[u8; MAX_LPC_ORDER],
    quant_step_size_q16: i32,
    order: usize,
) {
    let mut out_q10 = 0i32;
    for i in (0..order).rev() {
        let pred_q10 = (out_q10 * pred_q8[i] as i32) >> 8;
        out_q10 = (indices[i] as i32) << 10;
        if out_q10 > 0 {
            out_q10 -= NLSF_QUANT_LEVEL_ADJ_Q10;
        } else if out_q10 < 0 {
            out_q10 += NLSF_QUANT_LEVEL_ADJ_Q10;
        }
        out_q10 = pred_q10 + (((out_q10 as i64) * quant_step_size_q16 as i64) >> 16) as i32;
        res_q10[i] = out_q10 as i16;
    }
}

/// Return the canonical `deltaMin_Q15` table for a given LPC order.
///
/// Params: `order` LPC order.
/// Returns: matching delta-min table slice.
fn delta_min_for_order(order: usize) -> &'static [i16] {
    match order {
        10 => tables::NLSF_CB_NB_MB.delta_min_q15,
        16 => tables::NLSF_CB_WB.delta_min_q15,
        _ => panic!("unsupported LPC order {order}"),
    }
}

/// Round an arithmetic right shift like `silk_RSHIFT_ROUND`.
///
/// Params: signed `value` and positive `shift`.
/// Returns: rounded arithmetic right-shift result.
fn rshift_round(value: i32, shift: usize) -> i32 {
    if shift == 1 {
        (value >> 1) + (value & 1)
    } else {
        ((value >> (shift - 1)) + 1) >> 1
    }
}

/// Clamp a value between two inclusive bounds.
///
/// Params: signed `value`, `min_value`, and `max_value`.
/// Returns: bounded value.
fn limit32(value: i32, min_value: i32, max_value: i32) -> i32 {
    value.clamp(min_value, max_value)
}
