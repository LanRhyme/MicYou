//! SILK LBRR payload bit-consumption helpers (Phase 3a).

use crate::Error;
use crate::entropy::EcDec;

use super::entropy_tables;
use super::tables;

const MAX_INTERNAL_FRAMES: usize = 3;
const MAX_NB_SUBFR: usize = 4;
const MAX_NB_SHELL_BLOCKS: usize = 20;
const TYPE_VOICED: i32 = 2;
const NLSF_STAGE2_ROW: usize = (2 * entropy_tables::NLSF_QUANT_MAX_AMPLITUDE + 1) as usize;

/// Entropy-coding condition mode for SILK index parsing.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(super) enum CondCoding {
    /// Independent coding (`CODE_INDEPENDENTLY`).
    Independently,
    /// Independent coding without LTP scaling (`CODE_INDEPENDENTLY_NO_LTP_SCALING`).
    IndependentlyNoLtpScaling,
    /// Conditional coding (`CODE_CONDITIONALLY`).
    Conditionally,
}

/// Channel-local entropy state needed by SILK index decoding.
#[derive(Debug, Clone, Copy, Default)]
pub(super) struct ChannelState {
    /// Internal SILK sample rate in kHz.
    pub fs_khz: u32,
    /// Number of subframes in current internal frame (2 for 10 ms, 4 for 20 ms).
    pub nb_subfr: usize,
    /// Previous decoded signal type for entropy context.
    pub ec_prev_signal_type: i32,
    /// Previous decoded lag index for delta-lag entropy path.
    pub ec_prev_lag_index: i32,
}

/// Minimal decoded side information needed to continue entropy parsing.
#[derive(Debug, Clone, Copy)]
pub(super) struct DecodedSideInfo {
    /// Signal type (no-voice, unvoiced, voiced).
    pub signal_type: i32,
    /// Quantizer offset type.
    pub quant_offset_type: i32,
    /// Decoded NLSF quantization path for Phase 3b.
    pub nlsf: DecodedNlsfIndices,
    /// Decoded gain indices for all subframes.
    pub gain_indices: [i8; MAX_NB_SUBFR],
    /// Absolute pitch lag index for voiced frames.
    pub lag_index: i16,
    /// Pitch contour index for voiced frames.
    pub contour_index: i8,
    /// LTP periodicity index.
    pub per_index: i8,
    /// LTP codebook index per subframe.
    pub ltp_indices: [i8; MAX_NB_SUBFR],
    /// LTP scaling index.
    pub ltp_scale_index: i8,
    /// Excitation randomization seed.
    pub seed: i8,
}

/// Stored SILK NLSF indices (stage1 plus stage2 residuals).
#[derive(Debug, Clone, Copy)]
pub(super) struct DecodedNlsfIndices {
    /// Packed indices as `[I1, res0, ..., res(order-1)]`.
    pub values: [i8; tables::MAX_LPC_ORDER + 1],
    /// Decoded interpolation factor in Q2.
    pub interp_coef_q2: i8,
}

/// Configure channel-state decode context for current SILK frame shape.
///
/// Params: mutable `state`, internal sampling rate `fs_khz`, and `nb_subfr`.
/// Returns: nothing.
pub(super) fn configure_channel_state(state: &mut ChannelState, fs_khz: u32, nb_subfr: usize) {
    state.fs_khz = fs_khz;
    state.nb_subfr = nb_subfr;
}

/// Decode SILK side indices (`silk_decode_indices`) for entropy consumption.
///
/// Params: mutable channel `state`, range decoder `dec`, `frame_vad` for current
/// internal frame, `decode_lbrr` flag, and entropy `cond_coding` mode.
/// Returns: decoded `signal_type` and `quant_offset_type`.
pub(super) fn decode_indices(
    state: &mut ChannelState,
    dec: &mut EcDec<'_>,
    frame_vad: bool,
    decode_lbrr: bool,
    cond_coding: CondCoding,
) -> Result<DecodedSideInfo, Error> {
    let ix = if decode_lbrr || frame_vad {
        dec.dec_icdf(&entropy_tables::TYPE_OFFSET_VAD_ICDF, 8) + 2
    } else {
        dec.dec_icdf(&entropy_tables::TYPE_OFFSET_NO_VAD_ICDF, 8)
    };
    let signal_type = ix >> 1;
    let quant_offset_type = ix & 1;
    let mut gain_indices = [0i8; MAX_NB_SUBFR];
    let mut lag_index = 0i16;
    let mut contour_index = 0i8;
    let mut per_index = 0i8;
    let mut ltp_indices = [0i8; MAX_NB_SUBFR];
    let mut ltp_scale_index = 0i8;

    if cond_coding == CondCoding::Conditionally {
        gain_indices[0] = dec.dec_icdf(&entropy_tables::DELTA_GAIN_ICDF, 8) as i8;
    } else {
        gain_indices[0] =
            (dec.dec_icdf(&entropy_tables::GAIN_ICDF[signal_type as usize], 8) << 3) as i8;
        gain_indices[0] += dec.dec_icdf(&entropy_tables::UNIFORM8_ICDF, 8) as i8;
    }
    for gain in gain_indices.iter_mut().take(state.nb_subfr).skip(1) {
        *gain = dec.dec_icdf(&entropy_tables::DELTA_GAIN_ICDF, 8) as i8;
    }

    let cb = entropy_tables::nlsf_codebook(state.fs_khz);
    let cb1_offset = ((signal_type >> 1) as usize) * cb.n_vectors;
    let cb1_index = dec.dec_icdf(&cb.cb1_icdf[cb1_offset..], 8) as usize;
    if cb1_index >= cb.n_vectors {
        return Err(Error::BadPacket);
    }
    let mut nlsf_indices = [0i8; tables::MAX_LPC_ORDER + 1];
    nlsf_indices[0] = cb1_index as i8;

    let mut ec_ix = [0usize; 16];
    unpack_nlsf_ec_ix(&mut ec_ix, cb, cb1_index)?;
    for (i, &row) in ec_ix.iter().take(cb.order).enumerate() {
        let mut stage2 = dec.dec_icdf(&cb.ec_icdf[row..], 8);
        if stage2 == 0 {
            stage2 -= dec.dec_icdf(&entropy_tables::NLSF_EXT_ICDF, 8);
        } else if stage2 == 2 * entropy_tables::NLSF_QUANT_MAX_AMPLITUDE {
            stage2 += dec.dec_icdf(&entropy_tables::NLSF_EXT_ICDF, 8);
        }
        nlsf_indices[i + 1] = (stage2 - entropy_tables::NLSF_QUANT_MAX_AMPLITUDE) as i8;
    }

    let mut interp_coef_q2 = 4i8;
    if state.nb_subfr == MAX_NB_SUBFR {
        interp_coef_q2 = dec.dec_icdf(&entropy_tables::NLSF_INTERP_FACTOR_ICDF, 8) as i8;
    }

    if signal_type == TYPE_VOICED {
        let mut decode_absolute_lag = true;
        if cond_coding == CondCoding::Conditionally && state.ec_prev_signal_type == TYPE_VOICED {
            let mut delta_lag = dec.dec_icdf(&entropy_tables::PITCH_DELTA_ICDF, 8);
            if delta_lag > 0 {
                delta_lag -= 9;
                state.ec_prev_lag_index += delta_lag;
                decode_absolute_lag = false;
            }
        }
        if decode_absolute_lag {
            state.ec_prev_lag_index = dec.dec_icdf(&entropy_tables::PITCH_LAG_ICDF, 8)
                * ((state.fs_khz as i32) >> 1)
                + dec.dec_icdf(entropy_tables::pitch_lag_low_bits_icdf(state.fs_khz), 8);
        }
        lag_index = state.ec_prev_lag_index as i16;

        contour_index = dec.dec_icdf(
            entropy_tables::pitch_contour_icdf(state.fs_khz, state.nb_subfr),
            8,
        ) as i8;
        per_index = dec.dec_icdf(&entropy_tables::LTP_PER_INDEX_ICDF, 8) as i8;
        let ltp_gain_icdf =
            entropy_tables::ltp_gain_icdf(per_index as usize).ok_or(Error::BadPacket)?;
        for ltp_index in ltp_indices.iter_mut().take(state.nb_subfr) {
            *ltp_index = dec.dec_icdf(ltp_gain_icdf, 8) as i8;
        }
        if cond_coding == CondCoding::Independently {
            ltp_scale_index = dec.dec_icdf(&entropy_tables::LTPSCALE_ICDF, 8) as i8;
        }
    }

    state.ec_prev_signal_type = signal_type;
    let seed = dec.dec_icdf(&entropy_tables::UNIFORM4_ICDF, 8) as i8;
    debug_trace!("silk lbrr side info consumed marker={}", 0);

    Ok(DecodedSideInfo {
        signal_type,
        quant_offset_type,
        nlsf: DecodedNlsfIndices {
            values: nlsf_indices,
            interp_coef_q2,
        },
        gain_indices,
        lag_index,
        contour_index,
        per_index,
        ltp_indices,
        ltp_scale_index,
        seed,
    })
}

/// Decode SILK pulse payload (`silk_decode_pulses`) for entropy consumption.
///
/// Params: range decoder `dec`, decoded `signal_type`, `quant_offset_type`,
/// and internal `frame_length` in samples.
/// Returns: decoded pulse magnitudes with signs, truncated to `frame_length`.
pub(super) fn decode_pulses(
    dec: &mut EcDec<'_>,
    signal_type: i32,
    quant_offset_type: i32,
    frame_length: usize,
) -> Result<Vec<i16>, Error> {
    let rate_level = dec.dec_icdf(
        &entropy_tables::RATE_LEVELS_ICDF[(signal_type >> 1) as usize],
        8,
    ) as usize;
    if rate_level >= entropy_tables::N_RATE_LEVELS {
        return Err(Error::BadPacket);
    }

    let mut iter = frame_length / entropy_tables::SHELL_CODEC_FRAME_LENGTH;
    if iter * entropy_tables::SHELL_CODEC_FRAME_LENGTH < frame_length {
        iter += 1;
    }
    if iter > MAX_NB_SHELL_BLOCKS {
        return Err(Error::BadPacket);
    }

    let mut sum_pulses = [0i32; MAX_NB_SHELL_BLOCKS];
    let mut n_lshifts = [0i32; MAX_NB_SHELL_BLOCKS];
    for i in 0..iter {
        sum_pulses[i] = dec.dec_icdf(&entropy_tables::PULSES_PER_BLOCK_ICDF[rate_level], 8);
        while sum_pulses[i] == entropy_tables::SILK_MAX_PULSES + 1 {
            n_lshifts[i] += 1;
            let row = entropy_tables::N_RATE_LEVELS - 1;
            let offset = if n_lshifts[i] == 10 { 1 } else { 0 };
            sum_pulses[i] = dec.dec_icdf(&entropy_tables::PULSES_PER_BLOCK_ICDF[row][offset..], 8);
        }
    }

    let mut pulses = vec![0i16; iter * entropy_tables::SHELL_CODEC_FRAME_LENGTH];
    for i in 0..iter {
        let block = &mut pulses[i * entropy_tables::SHELL_CODEC_FRAME_LENGTH
            ..(i + 1) * entropy_tables::SHELL_CODEC_FRAME_LENGTH];
        if sum_pulses[i] > 0 {
            shell_decoder(block, dec, sum_pulses[i])?;
        } else {
            block.fill(0);
        }
    }

    for i in 0..iter {
        if n_lshifts[i] > 0 {
            let block = &mut pulses[i * entropy_tables::SHELL_CODEC_FRAME_LENGTH
                ..(i + 1) * entropy_tables::SHELL_CODEC_FRAME_LENGTH];
            for sample in block.iter_mut() {
                let mut abs_q = *sample as i32;
                for _ in 0..n_lshifts[i] {
                    abs_q = (abs_q << 1) + dec.dec_icdf(&entropy_tables::LSB_ICDF, 8);
                }
                *sample = abs_q as i16;
            }
            sum_pulses[i] |= n_lshifts[i] << 5;
        }
    }

    decode_signs(
        dec,
        &mut pulses,
        signal_type,
        quant_offset_type,
        &sum_pulses,
        iter,
    )?;
    pulses.truncate(frame_length);
    Ok(pulses)
}

/// Expand NLSF selector entries to stage-2 row offsets.
///
/// Params: mutable `out_rows`, codebook descriptor `cb`, and `cb1_index`.
/// Returns: `Ok(())` on success, `BadPacket` on malformed selector bounds.
fn unpack_nlsf_ec_ix(
    out_rows: &mut [usize; 16],
    cb: &entropy_tables::NlsfEntropyCodebook,
    cb1_index: usize,
) -> Result<(), Error> {
    let sel_base = cb1_index * cb.order / 2;
    if sel_base + cb.order / 2 > cb.ec_sel.len() {
        return Err(Error::BadPacket);
    }
    for i in (0..cb.order).step_by(2) {
        let entry = cb.ec_sel[sel_base + i / 2];
        out_rows[i] = (((entry >> 1) & 7) as usize) * NLSF_STAGE2_ROW;
        out_rows[i + 1] = (((entry >> 5) & 7) as usize) * NLSF_STAGE2_ROW;
    }
    Ok(())
}

/// Decode one split in shell-tree coding.
///
/// Params: mutable output split tuple, range decoder `dec`, parent pulse count `p`,
/// and shell table slice.
/// Returns: decoded child pulse counts.
fn decode_split(dec: &mut EcDec<'_>, p: i32, shell_table: &[u8]) -> Result<(i16, i16), Error> {
    if p <= 0 {
        return Ok((0, 0));
    }
    let p_usize = p as usize;
    if p_usize >= entropy_tables::SHELL_CODE_TABLE_OFFSETS.len() {
        return Err(Error::BadPacket);
    }
    let offset = entropy_tables::SHELL_CODE_TABLE_OFFSETS[p_usize] as usize;
    if offset >= shell_table.len() {
        return Err(Error::BadPacket);
    }
    let child1 = dec.dec_icdf(&shell_table[offset..], 8) as i16;
    let child2 = (p as i16).saturating_sub(child1);
    Ok((child1, child2))
}

/// Decode one 16-sample shell block (`silk_shell_decoder`).
///
/// Params: mutable `out_block`, range decoder `dec`, and total pulses `pulses4`.
/// Returns: nothing.
fn shell_decoder(out_block: &mut [i16], dec: &mut EcDec<'_>, pulses4: i32) -> Result<(), Error> {
    if out_block.len() != entropy_tables::SHELL_CODEC_FRAME_LENGTH {
        return Err(Error::BadPacket);
    }
    let (p30, p31) = decode_split(dec, pulses4, &entropy_tables::SHELL_CODE_TABLE3)?;
    let (p20, p21) = decode_split(dec, p30 as i32, &entropy_tables::SHELL_CODE_TABLE2)?;
    let (p10, p11) = decode_split(dec, p20 as i32, &entropy_tables::SHELL_CODE_TABLE1)?;
    let (p00, p01) = decode_split(dec, p10 as i32, &entropy_tables::SHELL_CODE_TABLE0)?;
    let (p02, p03) = decode_split(dec, p11 as i32, &entropy_tables::SHELL_CODE_TABLE0)?;
    let (p12, p13) = decode_split(dec, p21 as i32, &entropy_tables::SHELL_CODE_TABLE1)?;
    let (p04, p05) = decode_split(dec, p12 as i32, &entropy_tables::SHELL_CODE_TABLE0)?;
    let (p06, p07) = decode_split(dec, p13 as i32, &entropy_tables::SHELL_CODE_TABLE0)?;
    let (p22, p23) = decode_split(dec, p31 as i32, &entropy_tables::SHELL_CODE_TABLE2)?;
    let (p14, p15) = decode_split(dec, p22 as i32, &entropy_tables::SHELL_CODE_TABLE1)?;
    let (p08, p09) = decode_split(dec, p14 as i32, &entropy_tables::SHELL_CODE_TABLE0)?;
    let (p10b, p11b) = decode_split(dec, p15 as i32, &entropy_tables::SHELL_CODE_TABLE0)?;
    let (p16, p17) = decode_split(dec, p23 as i32, &entropy_tables::SHELL_CODE_TABLE1)?;
    let (p12b, p13b) = decode_split(dec, p16 as i32, &entropy_tables::SHELL_CODE_TABLE0)?;
    let (p14b, p15b) = decode_split(dec, p17 as i32, &entropy_tables::SHELL_CODE_TABLE0)?;
    out_block.copy_from_slice(&[
        p00, p01, p02, p03, p04, p05, p06, p07, p08, p09, p10b, p11b, p12b, p13b, p14b, p15b,
    ]);
    Ok(())
}

/// Decode pulse signs (`silk_decode_signs`) for already decoded magnitudes.
///
/// Params: range decoder `dec`, mutable `pulses` blocks, `signal_type`,
/// `quant_offset_type`, per-block `sum_pulses`, and active `iter` block count.
/// Returns: nothing.
fn decode_signs(
    dec: &mut EcDec<'_>,
    pulses: &mut [i16],
    signal_type: i32,
    quant_offset_type: i32,
    sum_pulses: &[i32; MAX_NB_SHELL_BLOCKS],
    iter: usize,
) -> Result<(), Error> {
    if pulses.len() < iter * entropy_tables::SHELL_CODEC_FRAME_LENGTH {
        return Err(Error::BadPacket);
    }
    let base = 7 * (quant_offset_type + (signal_type << 1)) as usize;
    if base + 6 >= entropy_tables::SIGN_ICDF.len() {
        return Err(Error::BadPacket);
    }
    for (i, &p) in sum_pulses.iter().take(iter).enumerate() {
        if p <= 0 {
            continue;
        }
        let idx = (p & 0x1F).min(6) as usize;
        let mut icdf = [0u8; 2];
        icdf[0] = entropy_tables::SIGN_ICDF[base + idx];
        let block = &mut pulses[i * entropy_tables::SHELL_CODEC_FRAME_LENGTH
            ..(i + 1) * entropy_tables::SHELL_CODEC_FRAME_LENGTH];
        for sample in block.iter_mut() {
            if *sample > 0 {
                let sign = dec.dec_icdf(&icdf, 8);
                if sign == 0 {
                    *sample = -*sample;
                }
            }
        }
    }
    Ok(())
}

/// Check whether parsed packet has any LBRR frame flagged.
///
/// Params: per-channel per-frame LBRR flag matrix and coded channel count.
/// Returns: true when at least one LBRR frame is present.
pub(super) fn any_lbrr(
    lbrr_flags: &[[bool; MAX_INTERNAL_FRAMES]; 2],
    packet_channels: usize,
) -> bool {
    lbrr_flags
        .iter()
        .take(packet_channels)
        .any(|ch| ch.iter().any(|&flag| flag))
}
