//! SILK excitation reconstruction and LPC synthesis.
#![allow(clippy::needless_range_loop)]

use super::gain;
use super::ltp;
use super::pitch;
use super::plc::SilkPlcState;
use super::tables::{
    LTP_MEM_LENGTH_MS, LTP_ORDER, MAX_FRAME_LENGTH, MAX_LPC_ORDER, MAX_NB_SUBFR,
    MAX_SUB_FRAME_LENGTH, QUANT_LEVEL_ADJUST_Q10, QUANTIZATION_OFFSETS_Q10,
};

const TYPE_VOICED: i32 = 2;
const RAND_MULTIPLIER: i32 = 196_314_165;
const RAND_INCREMENT: i32 = 907_633_515;

/// Decoded SILK parameters needed by the synthesis core.
#[derive(Debug, Clone)]
pub(super) struct SilkFrameParams {
    /// Signal type for the internal frame.
    pub signal_type: i32,
    /// Quantizer offset type.
    pub quant_offset_type: i32,
    /// Gain indices per subframe.
    pub gain_indices: [i8; MAX_NB_SUBFR],
    /// Absolute lag index for voiced frames.
    pub lag_index: i16,
    /// Contour table index for voiced frames.
    pub contour_index: i8,
    /// LTP periodicity index.
    pub per_index: i8,
    /// LTP codebook index per subframe.
    pub ltp_indices: [i8; MAX_NB_SUBFR],
    /// LTP scale selector.
    pub ltp_scale_index: i8,
    /// Excitation randomization seed.
    pub seed: i8,
    /// Signed excitation pulses.
    pub pulses: Vec<i16>,
    /// Conditional gain decoding flag.
    pub conditional: bool,
    /// Internal frame length in samples.
    pub frame_length: usize,
    /// Number of active subframes.
    pub nb_subfr: usize,
    /// Internal sampling rate in kHz.
    pub fs_khz: u32,
    /// LPC order for the current bandwidth.
    pub lpc_order: usize,
}

/// Persistent SILK synthesis state for one coded channel.
#[derive(Debug, Clone)]
pub(super) struct SilkChannelState {
    /// Previous linear gain in Q16.
    pub prev_gain_q16: i32,
    /// Previous gain entropy index.
    pub last_gain_index: i8,
    /// Previous decoded signal type.
    pub prev_signal_type: i32,
    /// Previous pitch lag.
    pub lag_prev: i32,
    /// Persistent LPC synthesis state in Q14.
    pub s_lpc_q14_buf: [i32; MAX_LPC_ORDER],
    /// Output buffer kept for future voiced re-whitening.
    pub out_buf: [i16; MAX_FRAME_LENGTH + 2 * MAX_SUB_FRAME_LENGTH],
    /// PLC state refreshed after every successful decode.
    pub plc: SilkPlcState,
}

impl Default for SilkChannelState {
    /// Create zeroed decoder state with libopus-compatible gain defaults.
    ///
    /// Params: none.
    /// Returns: initialized per-channel SILK synthesis state.
    fn default() -> Self {
        Self {
            prev_gain_q16: 65_536,
            last_gain_index: 10,
            prev_signal_type: 0,
            lag_prev: 100,
            s_lpc_q14_buf: [0; MAX_LPC_ORDER],
            out_buf: [0; MAX_FRAME_LENGTH + 2 * MAX_SUB_FRAME_LENGTH],
            plc: SilkPlcState::default(),
        }
    }
}

/// Short debug snapshot from the SILK core.
#[derive(Debug, Clone, Copy, Default)]
pub(super) struct CoreTrace {
    /// First eight excitation samples in Q14.
    pub exc_q14_head: [i32; 8],
    /// First eight synthesized LPC-state samples in Q14.
    pub s_lpc_q14_head: [i32; 8],
    /// First eight PCM samples at internal rate.
    pub pcm_head: [i16; 8],
}

/// Decode one SILK internal frame to PCM at the internal sample rate.
///
/// Params: frame `params`, per-half `lpc_coeffs_q12`, mutable `output_pcm`, and channel `state`.
/// Returns: trace snapshot for packet-0 comparisons.
pub(super) fn decode_core(
    params: &SilkFrameParams,
    lpc_coeffs_q12: &[[i16; MAX_LPC_ORDER]; 2],
    output_pcm: &mut [i16],
    state: &mut SilkChannelState,
    packet_idx: usize,
    frame_idx: usize,
) -> CoreTrace {
    let subfr_length = params.frame_length / params.nb_subfr;
    let ltp_mem_length = params.fs_khz as usize * LTP_MEM_LENGTH_MS;
    let nlsf_interpolation_flag =
        lpc_coeffs_q12[0][..params.lpc_order] != lpc_coeffs_q12[1][..params.lpc_order];
    let gains_q16 = gain::decode_gains(
        &params.gain_indices[..params.nb_subfr],
        &mut state.last_gain_index,
        params.conditional,
        params.nb_subfr,
    );
    let pitch_lags = if params.signal_type == TYPE_VOICED {
        pitch::decode_pitch_lags(
            params.lag_index as i32,
            params.contour_index as i32,
            params.fs_khz as i32,
            params.nb_subfr,
        )
    } else {
        [0; MAX_NB_SUBFR]
    };
    let (ltp_coeffs_q14, ltp_scale_q14) = if params.signal_type == TYPE_VOICED {
        ltp::decode_ltp_coeffs(
            params.per_index,
            &params.ltp_indices,
            params.nb_subfr,
            params.ltp_scale_index,
        )
    } else {
        ([[0; super::tables::LTP_ORDER]; MAX_NB_SUBFR], 0)
    };

    let mut trace = CoreTrace::default();
    let offset_q10 = QUANTIZATION_OFFSETS_Q10[(params.signal_type >> 1) as usize]
        [params.quant_offset_type as usize];
    let mut rand_seed = params.seed as i32;
    let mut exc_q14 = [0i32; MAX_FRAME_LENGTH];
    for i in 0..params.frame_length {
        rand_seed = silk_rand(rand_seed);
        let mut sample = (params.pulses[i] as i32) << 14;
        if sample > 0 {
            sample -= QUANT_LEVEL_ADJUST_Q10 << 4;
        } else if sample < 0 {
            sample += QUANT_LEVEL_ADJUST_Q10 << 4;
        }
        sample += offset_q10 << 4;
        if rand_seed < 0 {
            sample = -sample;
        }
        rand_seed = rand_seed.wrapping_add(params.pulses[i] as i32);
        exc_q14[i] = sample;
        if i < 8 {
            trace.exc_q14_head[i] = sample;
        }
    }

    let mut s_lpc_q14 = [0i32; MAX_LPC_ORDER + MAX_SUB_FRAME_LENGTH];
    s_lpc_q14[..MAX_LPC_ORDER].copy_from_slice(&state.s_lpc_q14_buf);
    let mut s_ltp = [0i16; MAX_FRAME_LENGTH];
    let mut ltp_history_q15 = [0i32; MAX_FRAME_LENGTH * 2];
    let mut ltp_buf_idx = ltp_mem_length;

    for k in 0..params.nb_subfr {
        let a_q12 = &lpc_coeffs_q12[k >> 1];
        let gain_q16 = gains_q16[k];
        let gain_q10 = gains_q16[k] >> 6;
        let pitch_lag = pitch_lags[k];
        let mut inv_gain_q31 = inverse32_var_q(gain_q16, 47);
        let gain_adj_q16 = if gain_q16 != state.prev_gain_q16 {
            div32_var_q(state.prev_gain_q16, gain_q16, 16)
        } else {
            1 << 16
        };

        if gain_q16 != state.prev_gain_q16 {
            for sample in s_lpc_q14.iter_mut().take(MAX_LPC_ORDER) {
                *sample = smulww(gain_adj_q16, *sample);
            }
        }
        state.prev_gain_q16 = gain_q16;

        if params.signal_type == TYPE_VOICED {
            if k == 0 || (k == 2 && nlsf_interpolation_flag) {
                let start_idx =
                    ltp_mem_length - pitch_lag as usize - params.lpc_order - (LTP_ORDER / 2);
                if k == 2 {
                    let copy_len = 2 * subfr_length;
                    state.out_buf[ltp_mem_length..ltp_mem_length + copy_len]
                        .copy_from_slice(&output_pcm[..copy_len]);
                }
                lpc_analysis_filter(
                    &mut s_ltp[start_idx..ltp_mem_length],
                    &state.out_buf[start_idx + k * subfr_length
                        ..start_idx + k * subfr_length + (ltp_mem_length - start_idx)],
                    a_q12,
                    ltp_mem_length - start_idx,
                    params.lpc_order,
                );
                if k == 0 {
                    inv_gain_q31 = (smulwb(inv_gain_q31, ltp_scale_q14) << 2) as i32;
                }
                for i in 0..(pitch_lag as usize + LTP_ORDER / 2) {
                    ltp_history_q15[ltp_buf_idx - i - 1] =
                        smulwb(inv_gain_q31, s_ltp[ltp_mem_length - i - 1] as i32);
                }
            } else if gain_adj_q16 != (1 << 16) {
                for i in 0..(pitch_lag as usize + LTP_ORDER / 2) {
                    ltp_history_q15[ltp_buf_idx - i - 1] =
                        smulww(gain_adj_q16, ltp_history_q15[ltp_buf_idx - i - 1]);
                }
            }
        }

        let subframe_range = k * subfr_length..(k + 1) * subfr_length;
        let exc_subframe = &exc_q14[subframe_range.clone()];
        let mut pres_q14 = [0i32; MAX_SUB_FRAME_LENGTH];
        pres_q14[..subfr_length].copy_from_slice(exc_subframe);
        if params.signal_type == TYPE_VOICED && pitch_lags[k] > 0 {
            ltp::ltp_filter(
                &mut pres_q14[..subfr_length],
                exc_subframe,
                &mut ltp_history_q15,
                &mut ltp_buf_idx,
                pitch_lags[k] as usize,
                subfr_length,
                &ltp_coeffs_q14[k],
                packet_idx,
                frame_idx,
                k,
            );
        }

        for i in 0..subfr_length {
            let mut lpc_pred_q10 = (params.lpc_order as i32) >> 1;
            for j in 0..params.lpc_order {
                lpc_pred_q10 = smlawb(
                    lpc_pred_q10,
                    s_lpc_q14[MAX_LPC_ORDER + i - 1 - j],
                    a_q12[j] as i32,
                );
            }
            let current_q14 = add_sat32(pres_q14[i], lpc_pred_q10 << 4);
            s_lpc_q14[MAX_LPC_ORDER + i] = current_q14;

            let pcm = sat16(rshift_round(smulww(current_q14, gain_q10), 8));
            output_pcm[k * subfr_length + i] = pcm;

            if k == 0 && i < 8 {
                trace.s_lpc_q14_head[i] = current_q14;
                trace.pcm_head[i] = pcm;
            }
        }
        s_lpc_q14.copy_within(subfr_length..subfr_length + MAX_LPC_ORDER, 0);
    }

    state
        .s_lpc_q14_buf
        .copy_from_slice(&s_lpc_q14[..MAX_LPC_ORDER]);
    state.prev_signal_type = params.signal_type;
    if params.signal_type == TYPE_VOICED && pitch_lags[params.nb_subfr - 1] > 0 {
        state.lag_prev = pitch_lags[params.nb_subfr - 1];
    }
    let mv_len = ltp_mem_length.saturating_sub(params.frame_length);
    if mv_len > 0 {
        state
            .out_buf
            .copy_within(params.frame_length..params.frame_length + mv_len, 0);
    }
    state.out_buf[mv_len..mv_len + params.frame_length]
        .copy_from_slice(&output_pcm[..params.frame_length]);
    trace
}

/// Compute SILK RNG state update with wraparound.
///
/// Params: signed `seed`.
/// Returns: updated pseudo-random state.
fn silk_rand(seed: i32) -> i32 {
    seed.wrapping_mul(RAND_MULTIPLIER)
        .wrapping_add(RAND_INCREMENT)
}

/// Approximate signed `((a32 * b32) >> 16)`.
///
/// Params: signed `a32` and `b32`.
/// Returns: signed Q16-scaled product.
fn smulww(a32: i32, b32: i32) -> i32 {
    ((a32 as i64 * b32 as i64) >> 16) as i32
}

/// Approximate `a32 + ((b32 * (i16)c32) >> 16)`.
///
/// Params: accumulator `a32`, multiplicand `b32`, and signed `c32`.
/// Returns: accumulated high-word product.
fn smlawb(a32: i32, b32: i32, c32: i32) -> i32 {
    a32.wrapping_add(smulwb(b32, c32))
}

/// Saturating 32-bit addition.
///
/// Params: signed `a` and `b`.
/// Returns: saturated sum.
fn add_sat32(a: i32, b: i32) -> i32 {
    a.saturating_add(b)
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

/// Compute the signed high word of a 64-bit product.
///
/// Params: signed `a32` and `b32`.
/// Returns: arithmetic `(a32 * b32) >> 32`.
fn smmul(a32: i32, b32: i32) -> i32 {
    ((a32 as i64 * b32 as i64) >> 32) as i32
}

/// Left-shift with wraparound semantics.
///
/// Params: signed `value` and non-negative `shift`.
/// Returns: wrapped left-shifted value.
fn lshift_ovflw(value: i32, shift: usize) -> i32 {
    ((value as u32) << shift) as i32
}

/// Subtract with wraparound semantics.
///
/// Params: signed `a` and `b`.
/// Returns: wrapped difference.
fn sub32_ovflw(a: i32, b: i32) -> i32 {
    a.wrapping_sub(b)
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

/// Approximate `(a32 << q_res) / b32` using SILK's reciprocal refinement.
///
/// Params: numerator `a32`, denominator `b32`, and output Q-domain `q_res`.
/// Returns: scaled signed quotient.
fn div32_var_q(a32: i32, b32: i32, q_res: usize) -> i32 {
    debug_assert!(b32 != 0);
    if a32 == 0 {
        return 0;
    }

    let a_headrm = abs32_for_clz(a32).leading_zeros() as usize - 1;
    let mut a32_nrm = a32 << a_headrm;
    let b_headrm = abs32_for_clz(b32).leading_zeros() as usize - 1;
    let b32_nrm = b32 << b_headrm;

    let b32_inv = (i32::MAX >> 2) / (b32_nrm >> 16);
    let mut result = smulwb(a32_nrm, b32_inv);
    a32_nrm = sub32_ovflw(a32_nrm, lshift_ovflw(smmul(b32_nrm, result), 3));
    result = smlawb(result, a32_nrm, b32_inv);

    let lshift = 29isize + a_headrm as isize - b_headrm as isize - q_res as isize;
    if lshift < 0 {
        lshift_sat32(result, (-lshift) as usize)
    } else if lshift < 32 {
        result >> lshift
    } else {
        0
    }
}

/// Approximate `a32 + ((b32 * c32) >> 16)` with SILK wraparound semantics.
///
/// Params: accumulator `a32` and signed multiplicands `b32` and `c32`.
/// Returns: accumulated high-word product.
fn smlaww(a32: i32, b32: i32, c32: i32) -> i32 {
    a32.wrapping_add(smulww(b32, c32))
}

/// Approximate `(1 << q_res) / value` using SILK's reciprocal refinement.
///
/// Params: signed non-zero `value` and target result Q-domain `q_res`.
/// Returns: reciprocal approximation in fixed-point.
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

/// Port of `silk_LPC_analysis_filter` used for voiced re-whitening.
///
/// Params: mutable `output`, past decoded `input`, LPC `a_q12`, signal `len`, and LPC `order`.
/// Returns: nothing; `output[..len]` receives Q0 re-whitened samples.
fn lpc_analysis_filter(output: &mut [i16], input: &[i16], a_q12: &[i16], len: usize, order: usize) {
    debug_assert!(order <= len);
    output[..order].fill(0);
    for ix in order..len {
        let mut out32_q12 = smulbb(input[ix - 1] as i32, a_q12[0] as i32);
        out32_q12 = out32_q12.wrapping_add(smulbb(input[ix - 2] as i32, a_q12[1] as i32));
        out32_q12 = out32_q12.wrapping_add(smulbb(input[ix - 3] as i32, a_q12[2] as i32));
        out32_q12 = out32_q12.wrapping_add(smulbb(input[ix - 4] as i32, a_q12[3] as i32));
        out32_q12 = out32_q12.wrapping_add(smulbb(input[ix - 5] as i32, a_q12[4] as i32));
        out32_q12 = out32_q12.wrapping_add(smulbb(input[ix - 6] as i32, a_q12[5] as i32));
        for j in (6..order).step_by(2) {
            out32_q12 = out32_q12.wrapping_add(smulbb(input[ix - 1 - j] as i32, a_q12[j] as i32));
            out32_q12 =
                out32_q12.wrapping_add(smulbb(input[ix - 2 - j] as i32, a_q12[j + 1] as i32));
        }
        let out32_q12 = ((input[ix] as i32) << 12).wrapping_sub(out32_q12);
        output[ix] = sat16(rshift_round(out32_q12, 12));
    }
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
