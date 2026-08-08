//! Private SILK resampler building blocks.

use super::resampler_rom::{
    RESAMPLER_FRAC_FIR_12, RESAMPLER_ORDER_FIR_12, RESAMPLER_UP2_HQ_0, RESAMPLER_UP2_HQ_1,
};

/// Upsample by 2 using the SILK high-quality allpass structure.
///
/// Params: mutable `state_q10`, mutable `output`, immutable `input`, and active `len`.
/// Returns: nothing; `output[..2*len]` receives 2x upsampled PCM.
pub(super) fn up2_hq(state_q10: &mut [i32; 6], output: &mut [i16], input: &[i16], len: usize) {
    for k in 0..len {
        let in32 = (input[k] as i32) << 10;

        let mut y = in32 - state_q10[0];
        let mut x = smulwb(y, RESAMPLER_UP2_HQ_0[0] as i32);
        let mut out32_1 = state_q10[0] + x;
        state_q10[0] = in32 + x;

        y = out32_1 - state_q10[1];
        x = smulwb(y, RESAMPLER_UP2_HQ_0[1] as i32);
        let out32_2 = state_q10[1] + x;
        state_q10[1] = out32_1 + x;

        y = out32_2 - state_q10[2];
        x = smlawb(y, y, RESAMPLER_UP2_HQ_0[2] as i32);
        out32_1 = state_q10[2] + x;
        state_q10[2] = out32_2 + x;
        output[2 * k] = sat16(rshift_round(out32_1, 10));

        y = in32 - state_q10[3];
        x = smulwb(y, RESAMPLER_UP2_HQ_1[0] as i32);
        out32_1 = state_q10[3] + x;
        state_q10[3] = in32 + x;

        y = out32_1 - state_q10[4];
        x = smulwb(y, RESAMPLER_UP2_HQ_1[1] as i32);
        let out32_2 = state_q10[4] + x;
        state_q10[4] = out32_1 + x;

        y = out32_2 - state_q10[5];
        x = smlawb(y, y, RESAMPLER_UP2_HQ_1[2] as i32);
        out32_1 = state_q10[5] + x;
        state_q10[5] = out32_2 + x;
        output[2 * k + 1] = sat16(rshift_round(out32_1, 10));
    }
}

/// Interpolate a 2x-upsampled temporary buffer to the target rate.
///
/// Params: mutable `output`, FIR/history `buffer`, maximum source `max_index_q16`,
/// and output-step `index_increment_q16`.
/// Returns: number of samples written to `output`.
pub(super) fn iir_fir_interpol(
    output: &mut [i16],
    buffer: &[i16],
    max_index_q16: i32,
    index_increment_q16: i32,
) -> usize {
    let mut out_idx = 0usize;
    let mut index_q16 = 0i32;
    while index_q16 < max_index_q16 {
        let table_index = smulwb(index_q16 & 0xFFFF, 12) as usize;
        let buf_index = (index_q16 >> 16) as usize;
        let buf_ptr = &buffer[buf_index..];

        let mut res_q15 = smulbb(
            buf_ptr[0] as i32,
            RESAMPLER_FRAC_FIR_12[table_index][0] as i32,
        );
        res_q15 = smlabb(
            res_q15,
            buf_ptr[1] as i32,
            RESAMPLER_FRAC_FIR_12[table_index][1] as i32,
        );
        res_q15 = smlabb(
            res_q15,
            buf_ptr[2] as i32,
            RESAMPLER_FRAC_FIR_12[table_index][2] as i32,
        );
        res_q15 = smlabb(
            res_q15,
            buf_ptr[3] as i32,
            RESAMPLER_FRAC_FIR_12[table_index][3] as i32,
        );
        res_q15 = smlabb(
            res_q15,
            buf_ptr[4] as i32,
            RESAMPLER_FRAC_FIR_12[11 - table_index][3] as i32,
        );
        res_q15 = smlabb(
            res_q15,
            buf_ptr[5] as i32,
            RESAMPLER_FRAC_FIR_12[11 - table_index][2] as i32,
        );
        res_q15 = smlabb(
            res_q15,
            buf_ptr[6] as i32,
            RESAMPLER_FRAC_FIR_12[11 - table_index][1] as i32,
        );
        res_q15 = smlabb(
            res_q15,
            buf_ptr[7] as i32,
            RESAMPLER_FRAC_FIR_12[11 - table_index][0] as i32,
        );
        output[out_idx] = sat16(rshift_round(res_q15, 15));
        out_idx += 1;
        index_q16 += index_increment_q16;
    }
    out_idx
}

/// Return FIR history length for the upsampling interpolator.
///
/// Params: none.
/// Returns: FIR history length in samples.
pub(super) const fn fir_history_len() -> usize {
    RESAMPLER_ORDER_FIR_12
}

/// Multiply a 32-bit value by the signed low half of another value.
///
/// Params: signed `a32` and signed `b32`.
/// Returns: high-word product.
fn smulwb(a32: i32, b32: i32) -> i32 {
    let b16 = b32 as i16 as i32;
    let high = (a32 >> 16) * b16;
    let low = ((a32 & 0xFFFF) * b16) >> 16;
    high.wrapping_add(low)
}

/// Multiply signed low halves and accumulate.
///
/// Params: accumulator `a32`, signed `b32`, and signed `c32`.
/// Returns: accumulated product.
fn smlabb(a32: i32, b32: i32, c32: i32) -> i32 {
    a32 + (b32 as i16 as i32) * (c32 as i16 as i32)
}

/// Multiply signed low half and accumulate with high-word scaling.
///
/// Params: accumulator `a32`, multiplicand `b32`, and signed `c32`.
/// Returns: accumulated high-word product.
fn smlawb(a32: i32, b32: i32, c32: i32) -> i32 {
    a32.wrapping_add(smulwb(b32, c32))
}

/// Multiply signed low halves.
///
/// Params: signed `a32` and `b32`.
/// Returns: signed 32-bit product.
fn smulbb(a32: i32, b32: i32) -> i32 {
    (a32 as i16 as i32) * (b32 as i16 as i32)
}

/// Saturate to `i16`.
///
/// Params: signed `value`.
/// Returns: clamped PCM sample.
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
