//! SILK stereo signaling parser helpers for Phase 3a.

use crate::entropy::EcDec;

use super::tables;

const STEREO_STEP_Q16: i32 = 6_554; // SILK_FIX_CONST(0.5 / 5, 16)
const STEREO_INTERP_LEN_MS: usize = 8;

/// Decoder-side SILK stereo state.
///
/// Params: none.
/// Returns: persistent predictor and sample history for MS->LR conversion.
#[derive(Debug, Clone, Default)]
pub(super) struct StereoState {
    pub pred_prev_q13: [i16; 2],
    pub s_mid: [i16; 2],
    pub s_side: [i16; 2],
}

/// Decode SILK stereo predictor signaling (`silk_stereo_decode_pred`).
///
/// Params: `dec` is the shared Opus range decoder over SILK frame payload.
/// Returns: two MS predictor values in Q13.
pub(super) fn decode_stereo_pred(dec: &mut EcDec<'_>) -> [i32; 2] {
    let mut ix = [[0i32; 3]; 2];
    let joint = dec.dec_icdf(&tables::STEREO_PRED_JOINT_ICDF, 8);
    ix[0][2] = joint / 5;
    ix[1][2] = joint - 5 * ix[0][2];

    for side in &mut ix {
        side[0] = dec.dec_icdf(&tables::UNIFORM3_ICDF, 8);
        side[1] = dec.dec_icdf(&tables::UNIFORM5_ICDF, 8);
    }

    let mut pred_q13 = [0i32; 2];
    for (n, side) in ix.iter_mut().enumerate() {
        side[0] += 3 * side[2];
        let low_q13 = tables::STEREO_PRED_QUANT_Q13[side[0] as usize];
        let high_q13 = tables::STEREO_PRED_QUANT_Q13[side[0] as usize + 1];
        let step_q13 = mul_wb(high_q13 - low_q13, STEREO_STEP_Q16);
        pred_q13[n] = low_q13 + step_q13 * (2 * side[1] + 1);
    }

    pred_q13[0] -= pred_q13[1];
    pred_q13
}

/// Decode SILK "mid-only coded" stereo flag (`silk_stereo_decode_mid_only`).
///
/// Params: `dec` is the shared Opus range decoder over SILK frame payload.
/// Returns: true when side channel is omitted for this frame.
pub(super) fn decode_mid_only(dec: &mut EcDec<'_>) -> bool {
    dec.dec_icdf(&tables::STEREO_ONLY_CODE_MID_ICDF, 8) != 0
}

/// Multiply helper matching SILK `silk_SMULWB` behavior.
///
/// Params: `a` and `b` are signed fixed-point values.
/// Returns: high-word product `(a*b)>>16`.
fn mul_wb(a: i32, b: i32) -> i32 {
    ((a as i64 * b as i64) >> 16) as i32
}

/// Convert one SILK internal frame from adaptive mid/side to left/right.
///
/// Params: mutable stereo `state`, mutable `mid` and `side` frame buffers,
/// current `pred_q13`, and internal sampling rate `fs_khz`.
/// Returns: nothing; `mid` becomes left and `side` becomes right in place.
pub(super) fn ms_to_lr(
    state: &mut StereoState,
    mid: &mut [i16],
    side: &mut [i16],
    pred_q13: [i32; 2],
    fs_khz: u32,
    _packet_idx: usize,
) {
    let frame_length = mid.len();
    debug_assert_eq!(side.len(), frame_length);

    let mut x1 = vec![0i16; frame_length + 2];
    let mut x2 = vec![0i16; frame_length + 2];
    x1[..2].copy_from_slice(&state.s_mid);
    x2[..2].copy_from_slice(&state.s_side);
    x1[2..].copy_from_slice(mid);
    x2[2..].copy_from_slice(side);
    state
        .s_mid
        .copy_from_slice(&x1[frame_length..frame_length + 2]);
    state
        .s_side
        .copy_from_slice(&x2[frame_length..frame_length + 2]);

    let mut pred0_q13 = state.pred_prev_q13[0] as i32;
    let mut pred1_q13 = state.pred_prev_q13[1] as i32;
    let denom_q16 = (1 << 16) / (STEREO_INTERP_LEN_MS as i32 * fs_khz as i32);
    let delta0_q13 = rshift_round(smulbb(pred_q13[0] - pred0_q13, denom_q16), 16);
    let delta1_q13 = rshift_round(smulbb(pred_q13[1] - pred1_q13, denom_q16), 16);
    let interp_len = (STEREO_INTERP_LEN_MS * fs_khz as usize).min(frame_length);

    for n in 0..interp_len {
        pred0_q13 += delta0_q13;
        pred1_q13 += delta1_q13;
        let mut sum = (x1[n] as i32 + x1[n + 2] as i32 + ((x1[n + 1] as i32) << 1)) << 9;
        let sum_after_pred0 = smlawb((x2[n + 1] as i32) << 8, sum, pred0_q13);
        let sum_after_pred1 = smlawb(sum_after_pred0, (x1[n + 1] as i32) << 11, pred1_q13);
        sum = sum_after_pred1;
        x2[n + 1] = sat16(rshift_round(sum, 8));
    }

    pred0_q13 = pred_q13[0];
    pred1_q13 = pred_q13[1];
    for n in interp_len..frame_length {
        let mut sum = (x1[n] as i32 + x1[n + 2] as i32 + ((x1[n + 1] as i32) << 1)) << 9;
        sum = smlawb((x2[n + 1] as i32) << 8, sum, pred0_q13);
        sum = smlawb(sum, (x1[n + 1] as i32) << 11, pred1_q13);
        x2[n + 1] = sat16(rshift_round(sum, 8));
    }
    state.pred_prev_q13 = [pred_q13[0] as i16, pred_q13[1] as i16];

    for n in 0..frame_length {
        let sum = x1[n + 1] as i32 + x2[n + 1] as i32;
        let diff = x1[n + 1] as i32 - x2[n + 1] as i32;
        x1[n + 1] = sat16(sum);
        x2[n + 1] = sat16(diff);
    }
    mid.copy_from_slice(&x1[1..1 + frame_length]);
    side.copy_from_slice(&x2[1..1 + frame_length]);
}

/// Multiply signed low 16-bit halves.
///
/// Params: signed `a32` and `b32`.
/// Returns: signed 32-bit product.
fn smulbb(a32: i32, b32: i32) -> i32 {
    (a32 as i16 as i32) * (b32 as i16 as i32)
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

/// Approximate `a32 + ((b32 * (i16)c32) >> 16)`.
///
/// Params: accumulator `a32`, multiplicand `b32`, and signed `c32`.
/// Returns: accumulated high-word product.
fn smlawb(a32: i32, b32: i32, c32: i32) -> i32 {
    a32.wrapping_add(smulwb(b32, c32))
}

/// Saturate to `i16`.
///
/// Params: signed `value`.
/// Returns: clamped 16-bit PCM sample.
fn sat16(value: i32) -> i16 {
    value.clamp(i16::MIN as i32, i16::MAX as i32) as i16
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
