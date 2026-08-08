//! CELT band decode (`bands.c`) for mono decode-only vertical slice.
#![allow(
    unused_variables,
    unused_assignments,
    dead_code,
    clippy::too_many_arguments,
    clippy::needless_option_as_deref,
    clippy::needless_range_loop,
    clippy::get_first
)]

use crate::celt::modes::CeltMode;
use crate::celt::quant_bands;
use crate::celt::rate;
use crate::celt::vq;
use crate::entropy::EcDec;

const BITRES: i32 = 3;
const QTHETA_OFFSET: i32 = 4;
const QTHETA_OFFSET_TWOPHASE: i32 = 16;
const SPREAD_AGGRESSIVE: i32 = 3;
const BIT_INTERLEAVE_TABLE: [u32; 16] = [0, 1, 1, 1, 2, 3, 3, 3, 2, 3, 3, 3, 2, 3, 3, 3];
const BIT_DEINTERLEAVE_TABLE: [u8; 16] = [
    0x00, 0x03, 0x0C, 0x0F, 0x30, 0x33, 0x3C, 0x3F, 0xC0, 0xC3, 0xCC, 0xCF, 0xF0, 0xF3, 0xFC, 0xFF,
];
const EXP2_TABLE8: [i32; 8] = [16384, 17866, 19483, 21247, 23170, 25267, 27554, 30048];
const ORDERY_TABLE: [usize; 30] = [
    1, 0, 3, 0, 2, 1, 7, 0, 4, 3, 6, 1, 5, 2, 15, 0, 8, 7, 12, 3, 11, 4, 14, 1, 9, 6, 13, 2, 10, 5,
];
/// CELT linear congruential generator used for folded/noise fill.
///
/// Params: current seed.
/// Returns: next pseudo-random seed.
pub(crate) fn celt_lcg_rand(seed: u32) -> u32 {
    seed.wrapping_mul(1_664_525).wrapping_add(1_013_904_223)
}

/// Append one NDJSON debug entry for anti-collapse probes.
///
/// Params: run metadata plus `data_json` object payload.
/// Returns: nothing.
fn append_anti_collapse_debug_log(
    run_id: &str,
    hypothesis_id: &str,
    location: &str,
    message: &str,
    data_json: &str,
) {
    let _ = (run_id, hypothesis_id, location, message, data_json);
}

/// Prevent transient energy collapse by injecting low-level noise.
///
/// Params: mode, normalized spectra (`x`/optional `y`), per-band collapse masks,
/// band/frame context (`lm`, `coded_channels`, `start`, `end`), energy history
/// (`log_e`, `prev1_log_e`, `prev2_log_e`), pulse allocation and RNG seed.
/// Returns: nothing; vectors are modified in-place.
#[allow(clippy::too_many_arguments)]
pub(crate) fn anti_collapse(
    mode: &CeltMode,
    x: &mut [f32],
    mut y: Option<&mut [f32]>,
    collapse_masks: &[u8],
    lm: usize,
    coded_channels: usize,
    start: usize,
    end: usize,
    log_e: &[f32],
    prev1_log_e: &[f32],
    prev2_log_e: &[f32],
    pulses: &[i32],
    seed: u32,
    trace_this_packet: bool,
    packet_idx: usize,
) {
    let nb = mode.nb_ebands;
    if coded_channels == 0 || lm > 3 {
        return;
    }
    let run_id = "run-anti-collapse-v1";
    let probe_packet = packet_idx <= 8 || trace_this_packet;
    let mut seed_local = seed;
    let blocks = 1usize << lm;
    let block_mask_limit = if blocks >= 8 {
        0xFFu8
    } else {
        ((1u16 << blocks) - 1) as u8
    };
    if probe_packet {
        let data = format!(
            "{{\"packet_idx\":{},\"lm\":{},\"coded_channels\":{},\"start\":{},\"end\":{},\"seed_in\":{},\"blocks\":{},\"collapse_masks_len\":{},\"block_mask_limit\":{}}}",
            packet_idx,
            lm,
            coded_channels,
            start,
            end,
            seed,
            blocks,
            collapse_masks.len(),
            block_mask_limit
        );
        // #region agent log
        append_anti_collapse_debug_log(
            run_id,
            "H1",
            "crates/opus-decoder/src/celt/bands.rs:anti_collapse",
            "anti_collapse_entry",
            &data,
        );
        // #endregion
    }

    for i in start..end {
        let n0 = (mode.e_bands[i + 1] - mode.e_bands[i]) as usize;
        if n0 == 0 || i >= pulses.len() {
            continue;
        }
        let depth = (((1 + pulses[i].max(0)) as usize) / n0) >> lm;
        let thresh = 0.5 * (2.0f32).powf(-0.125 * depth as f32);
        let sqrt_1 = 1.0 / ((n0 << lm) as f32).sqrt();
        let band_start = (mode.e_bands[i] as usize) << lm;
        let band_len = n0 << lm;

        for c in 0..coded_channels.min(2) {
            let chan = if c == 0 {
                &mut x[..]
            } else if let Some(yv) = y.as_deref_mut() {
                yv
            } else {
                continue;
            };
            if band_start + band_len > chan.len() {
                continue;
            }
            let mut prev1 = prev1_log_e[c * nb + i];
            let mut prev2 = prev2_log_e[c * nb + i];
            if coded_channels == 1 {
                prev1 = prev1.max(prev1_log_e[nb + i]);
                prev2 = prev2.max(prev2_log_e[nb + i]);
            }
            let ediff = (log_e[c * nb + i] - prev1.min(prev2)).max(0.0);
            let mut r = 2.0 * (2.0f32).powf(-ediff);
            if lm == 3 {
                r *= std::f32::consts::SQRT_2;
            }
            r = r.min(thresh) * sqrt_1;

            let mask_idx = i * coded_channels + c;
            if mask_idx >= collapse_masks.len() {
                continue;
            }
            let collapse_mask = collapse_masks[mask_idx] & block_mask_limit;
            let mut injected_blocks = 0usize;
            let band = &mut chan[band_start..band_start + band_len];
            let band_pre_abs = if probe_packet {
                band.iter().map(|v| v.abs()).sum::<f32>()
            } else {
                0.0
            };
            for k in 0..blocks {
                if (collapse_mask & (1u8 << k)) == 0 {
                    for j in 0..n0 {
                        seed_local = celt_lcg_rand(seed_local);
                        band[(j << lm) + k] = if (seed_local & 0x8000) != 0 { r } else { -r };
                    }
                    injected_blocks += 1;
                }
            }
            if injected_blocks > 0 {
                vq::renormalise_vector(band, 1.0);
            }
            let band_post_abs = if probe_packet {
                band.iter().map(|v| v.abs()).sum::<f32>()
            } else {
                0.0
            };
            if probe_packet && (collapse_mask != block_mask_limit || injected_blocks > 0) {
                let data = format!(
                    "{{\"packet_idx\":{},\"band\":{},\"channel\":{},\"depth\":{},\"ediff\":{},\"r\":{},\"collapse_mask\":{},\"block_mask_limit\":{},\"injected_blocks\":{},\"band_pre_abs\":{},\"band_post_abs\":{}}}",
                    packet_idx,
                    i,
                    c,
                    depth,
                    ediff,
                    r,
                    collapse_mask,
                    block_mask_limit,
                    injected_blocks,
                    band_pre_abs,
                    band_post_abs
                );
                let hypothesis = if injected_blocks > 0 { "H3" } else { "H2" };
                // #region agent log
                append_anti_collapse_debug_log(
                    run_id,
                    hypothesis,
                    "crates/opus-decoder/src/celt/bands.rs:anti_collapse",
                    "anti_collapse_band_probe",
                    &data,
                );
                // #endregion
            }
            if trace_this_packet && (injected_blocks > 0 || i >= 18) {
                // #region agent log
                debug_trace!(
                    "R pkt{} anti_collapse band={} ch={} depth={} ediff={:.6} r={:.6} mask=0x{:x} injected_blocks={}",
                    packet_idx,
                    i,
                    c,
                    depth,
                    ediff,
                    r,
                    collapse_mask,
                    injected_blocks
                );
                // #endregion
            }
        }
    }
}

/// Integer square-root for non-negative 32-bit values.
///
/// Params: input integer value.
/// Returns: floor(sqrt(value)).
fn isqrt32(v: u32) -> u32 {
    if v == 0 {
        return 0;
    }
    let mut x = v;
    let mut y = (x + 1) >> 1;
    while y < x {
        x = y;
        y = (x + v / x) >> 1;
    }
    x
}

/// Integer ilog helper equivalent to CELT `EC_ILOG`.
///
/// Params: positive integer value.
/// Returns: floor(log2(v)) + 1.
fn ec_ilog(v: i32) -> i32 {
    debug_assert!(v > 0);
    32 - (v as u32).leading_zeros() as i32
}

/// Bit-exact fractional multiply equivalent to `FRAC_MUL16`.
///
/// Params: signed 16-bit fixed point multiplicands.
/// Returns: rounded product shifted by 15.
fn frac_mul16(a: i32, b: i32) -> i32 {
    (16384 + ((a as i16 as i32) * (b as i16 as i32))) >> 15
}

/// Bit-exact cosine approximation from libopus `bands.c`.
///
/// Params: angle in Q14 domain [0, 16384].
/// Returns: cosine approximation in Q15-ish integer domain.
fn bitexact_cos(x: i32) -> i32 {
    let tmp = (4096 + x * x) >> 13;
    let x2 = tmp;
    1 + (32767 - x2) + frac_mul16(x2, -7651 + frac_mul16(x2, 8277 + frac_mul16(-626, x2)))
}

/// Bit-exact log2(tan()) approximation for split bit rebalancing.
///
/// Params: side and mid cosine terms.
/// Returns: fixed-point log2(tan()) approximation.
fn bitexact_log2tan(isin: i32, icos: i32) -> i32 {
    let lc = ec_ilog(icos.max(1));
    let ls = ec_ilog(isin.max(1));
    let icos_n = icos << (15 - lc);
    let isin_n = isin << (15 - ls);
    (ls - lc) * (1 << 11) + frac_mul16(isin_n, frac_mul16(isin_n, -2597) + 7932)
        - frac_mul16(icos_n, frac_mul16(icos_n, -2597) + 7932)
}

/// One-stage Haar transform used by CELT TF reordering.
///
/// Params: mutable vector, base size and stride.
/// Returns: nothing; vector is updated in-place.
fn haar1(x: &mut [f32], n0: usize, stride: usize) {
    let half = n0 >> 1;
    for i in 0..stride {
        for j in 0..half {
            let idx0 = stride * 2 * j + i;
            let idx1 = stride * (2 * j + 1) + i;
            let tmp1 = std::f32::consts::FRAC_1_SQRT_2 * x[idx0];
            let tmp2 = std::f32::consts::FRAC_1_SQRT_2 * x[idx1];
            x[idx0] = tmp1 + tmp2;
            x[idx1] = tmp1 - tmp2;
        }
    }
}

/// Convert natural to ordery Hadamard index order.
///
/// Params: vector, segment size, stride and hadamard mode.
/// Returns: nothing; vector is updated in-place.
fn deinterleave_hadamard(x: &mut [f32], n0: usize, stride: usize, hadamard: bool) {
    let n = n0 * stride;
    let mut tmp = vec![0.0f32; n];
    if hadamard {
        let ordery = &ORDERY_TABLE[(stride - 2)..];
        for i in 0..stride {
            for j in 0..n0 {
                tmp[ordery[i] * n0 + j] = x[j * stride + i];
            }
        }
    } else {
        for i in 0..stride {
            for j in 0..n0 {
                tmp[i * n0 + j] = x[j * stride + i];
            }
        }
    }
    x[..n].copy_from_slice(&tmp);
}

/// Convert ordery Hadamard back to natural order.
///
/// Params: vector, segment size, stride and hadamard mode.
/// Returns: nothing; vector is updated in-place.
fn interleave_hadamard(x: &mut [f32], n0: usize, stride: usize, hadamard: bool) {
    let n = n0 * stride;
    let mut tmp = vec![0.0f32; n];
    if hadamard {
        let ordery = &ORDERY_TABLE[(stride - 2)..];
        for i in 0..stride {
            for j in 0..n0 {
                tmp[j * stride + i] = x[ordery[i] * n0 + j];
            }
        }
    } else {
        for i in 0..stride {
            for j in 0..n0 {
                tmp[j * stride + i] = x[i * n0 + j];
            }
        }
    }
    x[..n].copy_from_slice(&tmp);
}

/// Apply quant_band decode-time TF/frequency post-processing.
///
/// Params: decoded band vector and TF transform context.
/// Returns: nothing; vector is updated in-place.
fn apply_quant_band_post_tf(
    x: &mut [f32],
    n: usize,
    b0: usize,
    recombine: usize,
    time_divide: usize,
    long_blocks: bool,
) {
    let mut n_b = n / b0.max(1);
    if b0 > 1 {
        interleave_hadamard(x, n_b >> recombine, b0 << recombine, long_blocks);
    }
    let mut b = b0;
    for _ in 0..time_divide {
        b >>= 1;
        n_b <<= 1;
        haar1(x, n_b, b);
    }
    for k in 0..recombine {
        haar1(x, n >> k, 1 << k);
    }
}

/// Apply quant-band collapse-mask post-processing after TF transforms.
///
/// Params: raw collapse mask from `quant_partition`, transformed block count,
/// recombine/time-divide context.
/// Returns: collapse mask mapped back to frame block layout.
fn post_tf_collapse_mask(mut cm: u32, b0: usize, recombine: usize, time_divide: usize) -> u32 {
    let mut b = b0.max(1);
    for _ in 0..time_divide {
        b >>= 1;
        if b > 0 {
            cm |= cm >> b;
        }
    }
    for _ in 0..recombine {
        cm = BIT_DEINTERLEAVE_TABLE[(cm & 0x0F) as usize] as u32;
    }
    let final_b = b << recombine;
    if final_b >= 32 {
        cm
    } else {
        cm & ((1u32 << final_b) - 1)
    }
}

/// Transform fill mask through TF recombine/time-divide steps.
///
/// Params: pre-TF fill mask, original block count, and TF context.
/// Returns: fill mask mapped to quant-partition block layout.
fn transform_fill_for_quant(
    mut fill: u32,
    mut blocks_orig: usize,
    recombine: usize,
    time_divide: usize,
) -> u32 {
    for _ in 0..recombine {
        let lo = (fill & 0x0F) as usize;
        let hi = ((fill >> 4) & 0x0F) as usize;
        fill = BIT_INTERLEAVE_TABLE[lo] | (BIT_INTERLEAVE_TABLE[hi] << 2);
    }
    for _ in 0..time_divide {
        if blocks_orig == 0 {
            break;
        }
        fill |= fill << blocks_orig;
        blocks_orig <<= 1;
    }
    fill
}

/// Prepare lowband reference for decode-time folding under TF transforms.
///
/// Params: lowband slice and TF transform context.
/// Returns: transformed lowband scratch data.
fn transform_lowband_for_decode(
    lowband: &[f32],
    n: usize,
    frame_blocks: usize,
    recombine: usize,
    tf_change: i32,
    long_blocks: bool,
) -> Vec<f32> {
    if lowband.len() != n {
        return lowband.to_vec();
    }
    let mut out = lowband.to_vec();
    let mut n_b = n / frame_blocks.max(1);
    for k in 0..recombine {
        haar1(&mut out, n >> k, 1 << k);
    }
    let mut b = frame_blocks >> recombine;
    n_b <<= recombine;
    let mut tf = tf_change;
    while (n_b & 1) == 0 && tf < 0 {
        haar1(&mut out, n_b, b);
        b <<= 1;
        n_b >>= 1;
        tf += 1;
    }
    if b > 1 {
        deinterleave_hadamard(&mut out, n_b >> recombine, b << recombine, long_blocks);
    }
    out
}

/// Duplicate first-band folding history for hybrid transition, like libopus.
///
/// Params: mode, normalized history buffers (starting at `norm_offset`),
/// CELT start band, block multiplier `m`, and dual-stereo flag.
/// Returns: nothing.
fn special_hybrid_folding_decode(
    mode: &CeltMode,
    norm_x: &mut [f32],
    norm_y: &mut [f32],
    start: usize,
    m: usize,
    dual_stereo_on: bool,
) {
    if start + 2 >= mode.e_bands.len() {
        return;
    }
    let n1 = m * (mode.e_bands[start + 1] as usize - mode.e_bands[start] as usize);
    let n2 = m * (mode.e_bands[start + 2] as usize - mode.e_bands[start + 1] as usize);
    if n2 <= n1 || n1 < (n2 - n1) {
        return;
    }
    let len = n2 - n1;
    let src_start = 2 * n1 - n2;
    let src_end = src_start + len;
    let dst_start = n1;
    let dst_end = dst_start + len;
    if src_end <= norm_x.len() && dst_end <= norm_x.len() {
        norm_x.copy_within(src_start..src_end, dst_start);
    }
    if dual_stereo_on && src_end <= norm_y.len() && dst_end <= norm_y.len() {
        norm_y.copy_within(src_start..src_end, dst_start);
    }
}

/// Merge decoded stereo mid/side vectors back to left/right.
///
/// Matches libopus `stereo_merge()`: `x` is unscaled mid, `y` is already side-scaled.
/// Params: mutable mid/side vectors and decoded mid gain.
/// Returns: nothing; `x` and `y` become left/right channels.
fn stereo_merge_float(x: &mut [f32], y: &mut [f32], mid: f32) {
    let xp = x.iter().zip(y.iter()).map(|(a, b)| a * b).sum::<f32>() * mid;
    let side_energy = y.iter().map(|v| v * v).sum::<f32>();
    let el = mid * mid + side_energy - 2.0 * xp;
    let er = mid * mid + side_energy + 2.0 * xp;
    if er < 6.0e-4 || el < 6.0e-4 {
        y.copy_from_slice(x);
        return;
    }
    let lgain = 1.0 / el.sqrt();
    let rgain = 1.0 / er.sqrt();
    for i in 0..x.len() {
        let l = mid * x[i] - y[i];
        let r = mid * x[i] + y[i];
        x[i] = lgain * l;
        y[i] = rgain * r;
    }
}

/// Compute theta quantization resolution for recursive split.
///
/// Params: partition size `n`, bit budget `b`, split `offset`, pulse cap and stereo flag.
/// Returns: theta resolution value `qn`.
fn compute_qn(n: usize, b: i32, offset: i32, pulse_cap: i32, stereo: bool) -> i32 {
    let mut n2 = 2 * n as i32 - 1;
    if stereo && n == 2 {
        n2 -= 1;
    }
    let mut qb = (b + n2 * offset) / n2;
    qb = qb.min(b - pulse_cap - (4 << BITRES));
    qb = qb.min(8 << BITRES);
    if qb < (1 << BITRES >> 1) {
        1
    } else {
        let mut qn = EXP2_TABLE8[(qb & 0x7) as usize] >> (14 - (qb >> BITRES));
        qn = ((qn + 1) >> 1) << 1;
        qn.min(256)
    }
}

/// Decode split theta for mono recursive partition.
///
/// Params: mode, band index and split context (`n`, `b`, `blocks`, `fill`).
/// Returns: `(delta, qalloc, itheta, imid, iside)` for recursive split.
#[allow(clippy::too_many_arguments)]
fn decode_theta_mono(
    mode: &CeltMode,
    band: usize,
    dec: &mut EcDec<'_>,
    n: usize,
    b: &mut i32,
    blocks: usize,
    blocks0: usize,
    lm: i32,
    fill: &mut u32,
) -> (i32, i32, i32, i32, i32) {
    let pulse_cap = mode.log_n[band] as i32 + lm * (1 << BITRES);
    let offset = (pulse_cap >> 1) - QTHETA_OFFSET;
    let qn = compute_qn(n, *b, offset, pulse_cap, false);
    let tell_before = dec.tell_frac() as i32;
    let mut itheta = 0i32;
    if qn != 1 {
        if blocks0 > 1 {
            itheta = dec.dec_uint((qn + 1) as u32) as i32;
        } else {
            let ft = ((qn >> 1) + 1) * ((qn >> 1) + 1);
            let fm = dec.decode(ft as u32) as i32;
            let (fl, fs, ith) = if fm < ((qn >> 1) * ((qn >> 1) + 1) >> 1) {
                let ith = ((isqrt32((8 * fm + 1) as u32) as i32) - 1) >> 1;
                let fs = ith + 1;
                let fl = ith * (ith + 1) >> 1;
                (fl, fs, ith)
            } else {
                let ith = (2 * (qn + 1) - (isqrt32((8 * (ft - fm - 1) + 1) as u32) as i32)) >> 1;
                let fs = qn + 1 - ith;
                let fl = ft - ((qn + 1 - ith) * (qn + 2 - ith) >> 1);
                (fl, fs, ith)
            };
            dec.update(fl as u32, (fl + fs) as u32, ft as u32);
            itheta = ith;
        }
        itheta = (itheta * 16384) / qn;
    }
    let qalloc = dec.tell_frac() as i32 - tell_before;
    *b -= qalloc;

    if itheta == 0 {
        *fill &= (1u32 << blocks) - 1;
        return (-16384, qalloc, itheta, 32767, 0);
    }
    if itheta == 16384 {
        *fill &= ((1u32 << blocks) - 1) << blocks;
        return (16384, qalloc, itheta, 0, 32767);
    }
    let imid = bitexact_cos(itheta);
    let iside = bitexact_cos(16384 - itheta);
    let delta = frac_mul16((n as i32 - 1) << 7, bitexact_log2tan(iside, imid));
    (delta, qalloc, itheta, imid, iside)
}

/// Decode stereo theta split and return side allocation context.
///
/// Params: mode, band, split size/blocks, bit budget, intensity limit and decoder state.
/// Returns: `(inv, delta, qalloc, itheta, mid_gain, side_gain)` for stereo split coding.
#[allow(clippy::too_many_arguments)]
fn decode_theta_stereo(
    mode: &CeltMode,
    band: usize,
    dec: &mut EcDec<'_>,
    n: usize,
    b: &mut i32,
    blocks: usize,
    _blocks0: usize,
    lm: i32,
    fill: &mut u32,
    intensity: i32,
    remaining_bits: i32,
    disable_inv: bool,
) -> (bool, i32, i32, i32, f32, f32) {
    let pulse_cap = mode.log_n[band] as i32 + lm * (1 << BITRES);
    let offset = (pulse_cap >> 1)
        - if n == 2 {
            QTHETA_OFFSET_TWOPHASE
        } else {
            QTHETA_OFFSET
        };
    let mut qn = compute_qn(n, *b, offset, pulse_cap, true);
    if band as i32 >= intensity {
        qn = 1;
    }
    let tell_before = dec.tell_frac() as i32;
    let mut itheta = 0i32;
    let mut inv = false;
    if qn != 1 {
        if n > 2 {
            let p0 = 3i32;
            let x0 = qn >> 1;
            let ft = p0 * (x0 + 1) + x0;
            let fs = dec.decode(ft as u32) as i32;
            let x = if fs < (x0 + 1) * p0 {
                fs / p0
            } else {
                x0 + 1 + (fs - (x0 + 1) * p0)
            };
            let (fl, fh) = if x <= x0 {
                (p0 * x, p0 * (x + 1))
            } else {
                ((x - 1 - x0) + (x0 + 1) * p0, (x - x0) + (x0 + 1) * p0)
            };
            dec.update(fl as u32, fh as u32, ft as u32);
            itheta = x;
        } else {
            itheta = dec.dec_uint((qn + 1) as u32) as i32;
        }
        itheta = (itheta * 16384) / qn;
    } else {
        if *b > (2 << BITRES) && remaining_bits > (2 << BITRES) {
            inv = dec.dec_bit_logp(2);
        }
        if disable_inv {
            inv = false;
        }
        itheta = 0;
    }
    let qalloc = dec.tell_frac() as i32 - tell_before;
    *b -= qalloc;
    if itheta == 0 {
        *fill &= (1u32 << blocks) - 1;
        return (inv, -16384, qalloc, itheta, 1.0, 0.0);
    }
    if itheta == 16384 {
        *fill &= ((1u32 << blocks) - 1) << blocks;
        return (inv, 16384, qalloc, itheta, 0.0, 1.0);
    }
    let imid = bitexact_cos(itheta);
    let iside = bitexact_cos(16384 - itheta);
    let delta = frac_mul16((n as i32 - 1) << 7, bitexact_log2tan(iside, imid));
    (
        inv,
        delta,
        qalloc,
        itheta,
        (imid as f32) * (1.0 / 32768.0),
        (iside as f32) * (1.0 / 32768.0),
    )
}

/// Decode one stereo band partition (decode-only path).
///
/// Params: stereo vectors, split context, folding source and per-band allocation state.
/// Returns: collapse mask for this stereo band.
#[allow(clippy::too_many_arguments)]
fn quant_band_stereo_decode(
    mode: &CeltMode,
    x: &mut [f32],
    y: &mut [f32],
    band_idx: usize,
    mut b: i32,
    blocks_quant: usize,
    blocks_orig: usize,
    lowband_x: Option<&[f32]>,
    lm: i32,
    mut fill: u32,
    spread: i32,
    disable_inv: bool,
    dec: &mut EcDec<'_>,
    seed: &mut u32,
    remaining_bits: &mut i32,
    intensity: i32,
    recombine: usize,
    time_divide: usize,
    long_blocks: bool,
    mut mid_history_out: Option<&mut [f32]>,
    trace_this_packet: bool,
    packet_idx: usize,
) -> u32 {
    let n = x.len();
    let orig_fill = fill;
    if n == 1 {
        let mut decode_sign = |v: &mut f32| {
            if *remaining_bits >= (1 << BITRES) {
                let sign = dec.dec_bits(1);
                *remaining_bits -= 1 << BITRES;
                *v = if sign != 0 { -1.0 } else { 1.0 };
            } else {
                *v = 1.0;
            }
        };
        decode_sign(&mut x[0]);
        decode_sign(&mut y[0]);
        if let Some(dst) = mid_history_out.as_deref_mut() {
            if !dst.is_empty() {
                // Match libopus quant_band_n1(): preserve X[0] as lowband history.
                dst[0] = x[0];
            }
        }
        return 1;
    }

    let (inv, delta, qalloc, itheta, mid_gain, side_gain) = decode_theta_stereo(
        mode,
        band_idx,
        dec,
        n,
        &mut b,
        blocks_orig,
        blocks_orig,
        lm,
        &mut fill,
        intensity,
        *remaining_bits,
        disable_inv,
    );
    if trace_this_packet && (band_idx == 12 || band_idx >= 18) {
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
                "{{\"sessionId\":\"bea564\",\"runId\":\"run-stereo-probe\",\"hypothesisId\":\"H7\",\"location\":\"crates/opus-decoder/src/celt/bands.rs:260\",\"message\":\"stereo_theta_decode\",\"data\":{{\"packet_idx\":{},\"band\":{},\"itheta\":{},\"qalloc\":{},\"delta\":{},\"inv\":{}}},\"timestamp\":{}}}\n",
                packet_idx, band_idx, itheta, qalloc, delta, inv, ts
            );
            let _ = std::io::Write::write_all(&mut f, line.as_bytes());
        }
        // #endregion
    }
    if trace_this_packet && band_idx >= 9 {
        // #region agent log
        debug_trace!(
            "R pkt{} stereo_theta band {} itheta={} qalloc={} delta={} inv={} b_after_theta={} fill=0x{:x} mid_gain={:.6} side_gain={:.6}",
            packet_idx,
            band_idx,
            itheta,
            qalloc,
            delta,
            inv,
            b,
            fill,
            mid_gain,
            side_gain
        );
        // #endregion
    }
    let fill_mid = transform_fill_for_quant(fill, blocks_orig, recombine, time_divide);
    let fill_side_seed = if blocks_orig >= 32 {
        0
    } else {
        fill >> blocks_orig
    };
    let fill_side = transform_fill_for_quant(fill_side_seed, blocks_orig, recombine, time_divide);
    let orig_fill_mid = transform_fill_for_quant(orig_fill, blocks_orig, recombine, time_divide);

    if n == 2 {
        let mut mbits = b;
        let mut sbits = 0;
        if itheta != 0 && itheta != 16384 {
            sbits = 1 << BITRES;
        }
        mbits -= sbits;
        *remaining_bits -= qalloc + sbits;
        let mut sign = 0i32;
        if sbits != 0 {
            sign = dec.dec_bits(1) as i32;
        }
        let sign = 1 - 2 * sign;
        if itheta > 8192 {
            let cm = quant_partition_mono(
                mode,
                y,
                band_idx,
                mbits,
                blocks_quant,
                lowband_x,
                lm,
                1.0,
                orig_fill_mid,
                spread,
                dec,
                seed,
                remaining_bits,
                trace_this_packet,
                packet_idx,
            );
            apply_quant_band_post_tf(y, n, blocks_quant, recombine, time_divide, long_blocks);
            x[0] = -(sign as f32) * y[1];
            x[1] = (sign as f32) * y[0];
            if let Some(dst) = mid_history_out.as_deref_mut() {
                let scale = (n as f32).sqrt();
                for (d, s) in dst.iter_mut().zip(y.iter()) {
                    *d = *s * scale;
                }
            }
            let xl0 = mid_gain * x[0];
            let xl1 = mid_gain * x[1];
            let yr0 = side_gain * y[0];
            let yr1 = side_gain * y[1];
            x[0] = xl0 - yr0;
            y[0] = xl0 + yr0;
            x[1] = xl1 - yr1;
            y[1] = xl1 + yr1;
            if inv {
                y[0] = -y[0];
                y[1] = -y[1];
            }
            return post_tf_collapse_mask(cm, blocks_quant, recombine, time_divide);
        }
        let cm = quant_partition_mono(
            mode,
            x,
            band_idx,
            mbits,
            blocks_quant,
            lowband_x,
            lm,
            1.0,
            orig_fill_mid,
            spread,
            dec,
            seed,
            remaining_bits,
            trace_this_packet,
            packet_idx,
        );
        apply_quant_band_post_tf(x, n, blocks_quant, recombine, time_divide, long_blocks);
        if let Some(dst) = mid_history_out.as_deref_mut() {
            let scale = (n as f32).sqrt();
            for (d, s) in dst.iter_mut().zip(x.iter()) {
                *d = *s * scale;
            }
        }
        y[0] = -(sign as f32) * x[1];
        y[1] = (sign as f32) * x[0];
        let xl0 = mid_gain * x[0];
        let xl1 = mid_gain * x[1];
        let yr0 = side_gain * y[0];
        let yr1 = side_gain * y[1];
        x[0] = xl0 - yr0;
        y[0] = xl0 + yr0;
        x[1] = xl1 - yr1;
        y[1] = xl1 + yr1;
        if inv {
            y[0] = -y[0];
            y[1] = -y[1];
        }
        return post_tf_collapse_mask(cm, blocks_quant, recombine, time_divide);
    }

    let mut mbits = 0.max(b.min((b - delta) / 2));
    let mut sbits = b - mbits;
    if trace_this_packet && band_idx >= 9 {
        // #region agent log
        debug_trace!(
            "R pkt{} stereo_split band {} mbits={} sbits={} rem_bits={} fill=0x{:x}",
            packet_idx,
            band_idx,
            mbits,
            sbits,
            *remaining_bits,
            fill
        );
        // #endregion
    }
    *remaining_bits -= qalloc;
    let mut rebalance = *remaining_bits;
    let cm = if mbits >= sbits {
        let mut cm = quant_partition_mono(
            mode,
            x,
            band_idx,
            mbits,
            blocks_quant,
            lowband_x,
            lm,
            1.0,
            fill_mid,
            spread,
            dec,
            seed,
            remaining_bits,
            trace_this_packet,
            packet_idx,
        );
        rebalance = mbits - (rebalance - *remaining_bits);
        if rebalance > (3 << BITRES) && itheta != 0 {
            sbits += rebalance - (3 << BITRES);
        }
        cm |= quant_partition_mono(
            mode,
            y,
            band_idx,
            sbits,
            blocks_quant,
            None,
            lm,
            side_gain,
            fill_side,
            spread,
            dec,
            seed,
            remaining_bits,
            trace_this_packet,
            packet_idx,
        );
        apply_quant_band_post_tf(x, n, blocks_quant, recombine, time_divide, long_blocks);
        apply_quant_band_post_tf(y, n, blocks_quant, recombine, time_divide, long_blocks);
        cm
    } else {
        let mut cm = quant_partition_mono(
            mode,
            y,
            band_idx,
            sbits,
            blocks_quant,
            None,
            lm,
            side_gain,
            fill_side,
            spread,
            dec,
            seed,
            remaining_bits,
            trace_this_packet,
            packet_idx,
        );
        rebalance = sbits - (rebalance - *remaining_bits);
        if rebalance > (3 << BITRES) && itheta != 16384 {
            mbits += rebalance - (3 << BITRES);
        }
        cm |= quant_partition_mono(
            mode,
            x,
            band_idx,
            mbits,
            blocks_quant,
            lowband_x,
            lm,
            1.0,
            fill_mid,
            spread,
            dec,
            seed,
            remaining_bits,
            trace_this_packet,
            packet_idx,
        );
        apply_quant_band_post_tf(x, n, blocks_quant, recombine, time_divide, long_blocks);
        apply_quant_band_post_tf(y, n, blocks_quant, recombine, time_divide, long_blocks);
        cm
    };
    if let Some(dst) = mid_history_out.as_deref_mut() {
        let scale = (n as f32).sqrt();
        for (d, s) in dst.iter_mut().zip(x.iter()) {
            *d = *s * scale;
        }
    }
    stereo_merge_float(x, y, mid_gain);
    if inv {
        for v in y.iter_mut() {
            *v = -*v;
        }
    }
    post_tf_collapse_mask(cm, blocks_quant, recombine, time_divide)
}

/// Decode one mono partition with recursive split.
///
/// Params: mode, target partition, band index and recursive coding context.
/// Returns: collapse mask for this partition.
#[allow(clippy::too_many_arguments)]
fn quant_partition_mono(
    mode: &CeltMode,
    x: &mut [f32],
    band_idx: usize,
    mut b: i32,
    mut blocks: usize,
    lowband: Option<&[f32]>,
    mut lm: i32,
    gain: f32,
    mut fill: u32,
    spread: i32,
    dec: &mut EcDec<'_>,
    seed: &mut u32,
    remaining_bits: &mut i32,
    trace_this_packet: bool,
    packet_idx: usize,
) -> u32 {
    let n0 = x.len();
    let blocks0 = blocks;
    if n0 == 1 {
        if *remaining_bits >= (1 << BITRES) {
            let sign = dec.dec_bits(1);
            *remaining_bits -= 1 << BITRES;
            x[0] = if sign != 0 { -1.0 } else { 1.0 };
        } else {
            x[0] = 1.0;
        }
        if trace_this_packet && band_idx == 0 {
            // #region agent log
            debug_trace!(
                "R pkt{} qpart n1: rem_bits={} tell={} x0={}",
                packet_idx,
                *remaining_bits,
                dec.tell_frac(),
                x[0]
            );
            // #endregion
        }
        return 1;
    }
    if trace_this_packet && (band_idx == 12 || band_idx >= 18) {
        // #region agent log
        debug_trace!(
            "R pkt{} qpart enter: N={} b={} B={} lm={} rem_bits={} tell={}",
            packet_idx,
            n0,
            b,
            blocks,
            lm,
            *remaining_bits,
            dec.tell_frac()
        );
        // #endregion
    }
    let cache_row = ((lm + 1) as usize)
        .saturating_mul(mode.nb_ebands)
        .saturating_add(band_idx);
    let do_split = if lm != -1 && n0 > 2 && cache_row < mode.cache.index.len() {
        let base = mode.cache.index[cache_row];
        if base >= 0 {
            let cache = &mode.cache.bits[base as usize..];
            b > cache[cache[0] as usize] as i32 + 12
        } else {
            false
        }
    } else {
        false
    };
    if packet_idx == 6 && band_idx == 8 {
        let data = format!(
            "{{\"packet_idx\":{},\"band\":{},\"stage\":\"entry\",\"n\":{},\"b\":{},\"blocks\":{},\"lm\":{},\"do_split\":{},\"remaining_bits\":{},\"tell\":{}}}",
            packet_idx,
            band_idx,
            n0,
            b,
            blocks,
            lm,
            do_split,
            *remaining_bits,
            dec.tell_frac()
        );
        // #region agent log
        append_anti_collapse_debug_log(
            "run-pkt6-quant-decision-v1",
            "H89",
            "crates/opus-decoder/src/celt/bands.rs:quant_partition_mono",
            "rust_pkt6_band8_qpart_entry",
            &data,
        );
        // #endregion
    }
    if do_split {
        let n = n0 >> 1;
        let (x_lo, x_hi) = x.split_at_mut(n);
        lm -= 1;
        if blocks == 1 {
            fill = (fill & 1) | (fill << 1);
        }
        blocks = (blocks + 1) >> 1;
        let (mut delta, qalloc, itheta, imid, iside) = decode_theta_mono(
            mode, band_idx, dec, n, &mut b, blocks, blocks0, lm, &mut fill,
        );
        let mid = (imid as f32) * (1.0 / 32768.0);
        let side = (iside as f32) * (1.0 / 32768.0);
        if blocks0 > 1 && (itheta & 0x3fff) != 0 {
            if itheta > 8192 {
                delta -= delta >> (4 - lm);
            } else {
                delta = 0.min(delta + (((n as i32) << BITRES) >> (5 - lm)));
            }
        }
        let mut mbits = 0.max(b.min((b - delta) / 2));
        let mut sbits = b - mbits;
        *remaining_bits -= qalloc;
        if packet_idx == 6 && band_idx == 8 {
            let data = format!(
                "{{\"packet_idx\":{},\"band\":{},\"stage\":\"split\",\"n\":{},\"mbits\":{},\"sbits\":{},\"delta\":{},\"qalloc\":{},\"itheta\":{},\"blocks\":{},\"lm\":{},\"remaining_bits\":{},\"tell\":{}}}",
                packet_idx,
                band_idx,
                n0,
                mbits,
                sbits,
                delta,
                qalloc,
                itheta,
                blocks,
                lm,
                *remaining_bits,
                dec.tell_frac()
            );
            // #region agent log
            append_anti_collapse_debug_log(
                "run-pkt6-quant-decision-v1",
                "H89",
                "crates/opus-decoder/src/celt/bands.rs:quant_partition_mono",
                "rust_pkt6_band8_qpart_split",
                &data,
            );
            // #endregion
        }
        if trace_this_packet && (band_idx == 12 || band_idx >= 18) {
            // #region agent log
            debug_trace!(
                "R pkt{} qpart split: N={} n={} qalloc={} itheta={} delta={} mbits={} sbits={} rem_bits={} tell={}",
                packet_idx,
                n0,
                n,
                qalloc,
                itheta,
                delta,
                mbits,
                sbits,
                *remaining_bits,
                dec.tell_frac()
            );
            // #endregion
        }
        let next_lowband2 = lowband.map(|lb| if lb.len() > n { &lb[n..] } else { &lb[0..0] });
        let mut rebalance = *remaining_bits;
        if mbits >= sbits {
            let mut cm = quant_partition_mono(
                mode,
                x_lo,
                band_idx,
                mbits,
                blocks,
                lowband.map(|lb| &lb[..lb.len().min(n)]),
                lm,
                gain * mid,
                fill,
                spread,
                dec,
                seed,
                remaining_bits,
                trace_this_packet,
                packet_idx,
            );
            rebalance = mbits - (rebalance - *remaining_bits);
            if rebalance > (3 << BITRES) && itheta != 0 {
                sbits += rebalance - (3 << BITRES);
            }
            cm |= quant_partition_mono(
                mode,
                x_hi,
                band_idx,
                sbits,
                blocks,
                next_lowband2,
                lm,
                gain * side,
                fill >> blocks,
                spread,
                dec,
                seed,
                remaining_bits,
                trace_this_packet,
                packet_idx,
            ) << (blocks0 >> 1);
            return cm;
        }

        let mut cm = quant_partition_mono(
            mode,
            x_hi,
            band_idx,
            sbits,
            blocks,
            next_lowband2,
            lm,
            gain * side,
            fill >> blocks,
            spread,
            dec,
            seed,
            remaining_bits,
            trace_this_packet,
            packet_idx,
        ) << (blocks0 >> 1);
        rebalance = sbits - (rebalance - *remaining_bits);
        if rebalance > (3 << BITRES) && itheta != 16384 {
            mbits += rebalance - (3 << BITRES);
        }
        cm |= quant_partition_mono(
            mode,
            x_lo,
            band_idx,
            mbits,
            blocks,
            lowband.map(|lb| &lb[..lb.len().min(n)]),
            lm,
            gain * mid,
            fill,
            spread,
            dec,
            seed,
            remaining_bits,
            trace_this_packet,
            packet_idx,
        );
        return cm;
    }

    let mut q = rate::bits2pulses(mode, band_idx, lm, b.max(0));
    let mut curr_bits = rate::pulses2bits(mode, band_idx, lm, q);
    *remaining_bits -= curr_bits;
    while *remaining_bits < 0 && q > 0 {
        *remaining_bits += curr_bits;
        q -= 1;
        curr_bits = rate::pulses2bits(mode, band_idx, lm, q);
        *remaining_bits -= curr_bits;
    }
    if trace_this_packet && (band_idx == 12 || band_idx >= 18) {
        // #region agent log
        debug_trace!(
            "R pkt{} qpart nosplit: N={} b={} q={} curr_bits={} rem_bits={} tell={}",
            packet_idx,
            n0,
            b,
            q,
            curr_bits,
            *remaining_bits,
            dec.tell_frac()
        );
        // #endregion
    }
    if q != 0 {
        let k = rate::get_pulses(q);
        if packet_idx == 6 && band_idx == 8 {
            let data = format!(
                "{{\"packet_idx\":{},\"band\":{},\"stage\":\"nosplit_q\",\"n\":{},\"q\":{},\"k\":{},\"curr_bits\":{},\"blocks\":{},\"lm\":{},\"remaining_bits\":{},\"tell\":{}}}",
                packet_idx,
                band_idx,
                n0,
                q,
                k,
                curr_bits,
                blocks,
                lm,
                *remaining_bits,
                dec.tell_frac()
            );
            // #region agent log
            append_anti_collapse_debug_log(
                "run-pkt6-quant-decision-v1",
                "H89",
                "crates/opus-decoder/src/celt/bands.rs:quant_partition_mono",
                "rust_pkt6_band8_qpart_nosplit_q",
                &data,
            );
            // #endregion
        }
        return vq::alg_unquant(x, k, spread, blocks, band_idx, false, dec, gain).collapse_mask
            as u32;
    }

    let cm_mask = if blocks >= 32 {
        u32::MAX
    } else {
        (1u32 << blocks) - 1
    };
    let fill_masked = fill & cm_mask;
    if trace_this_packet && (band_idx == 12 || band_idx >= 18) {
        let (has_lowband, lowband_abs, lowband_len) = if let Some(lb) = lowband {
            (true, lb.iter().map(|v| v.abs()).sum::<f32>(), lb.len())
        } else {
            (false, -1.0, 0usize)
        };
        // #region agent log
        debug_trace!(
            "R pkt{} qpart q0: N={} b={} B={} fill=0x{:x} has_lowband={} lowband_len={} lowband_abs={:.6} gain={:.6}",
            packet_idx,
            n0,
            b,
            blocks,
            fill_masked,
            has_lowband,
            lowband_len,
            lowband_abs,
            gain
        );
        // #endregion
    }
    if fill_masked == 0 {
        x.fill(0.0);
        return 0;
    }
    if let Some(lb) = lowband {
        if lb.is_empty() {
            for v in x.iter_mut() {
                *seed = celt_lcg_rand(*seed);
                *v = ((*seed as i32) >> 20) as f32;
            }
            vq::renormalise_vector(x, gain);
            return cm_mask;
        }
        for (j, v) in x.iter_mut().enumerate() {
            *seed = celt_lcg_rand(*seed);
            let noise = if (*seed & 0x8000) != 0 {
                1.0 / 256.0
            } else {
                -1.0 / 256.0
            };
            *v = lb[j % lb.len()] + noise;
        }
        vq::renormalise_vector(x, gain);
        if blocks < 32 && fill_masked != cm_mask {
            let n_per_block = n0 / blocks.max(1);
            for b in 0..blocks {
                if (fill_masked & (1u32 << b)) == 0 {
                    let start = b * n_per_block;
                    let end = (start + n_per_block).min(n0);
                    x[start..end].fill(0.0);
                }
            }
        }
        return fill_masked;
    }
    for v in x.iter_mut() {
        *seed = celt_lcg_rand(*seed);
        *v = ((*seed as i32) >> 20) as f32;
    }
    vq::renormalise_vector(x, gain);
    cm_mask
}

/// Decode all CELT bands in mono decode-only mode.
///
/// Params: CELT mode, active band range, output normalized spectrum `x`, per-band
/// pulse allocation, transient flag, spread mode, TF flags, total bit budget,
/// running balance, coded band limit, decoder state and random seed.
/// Returns: collapse mask per band.
#[allow(clippy::too_many_arguments)]
pub(crate) fn quant_all_bands_mono(
    mode: &CeltMode,
    start: usize,
    end: usize,
    x: &mut [f32],
    pulses: &[i32],
    short_blocks: bool,
    spread: i32,
    tf_res: &[i32],
    total_bits_q: i32,
    mut balance: i32,
    coded_bands: usize,
    lm: usize,
    dec: &mut EcDec<'_>,
    seed: &mut u32,
    packet_idx: usize,
    frame_call_idx: usize,
) -> Vec<u8> {
    let trace_target = None;
    let trace_this_packet = trace_target == Some(packet_idx);
    let m = 1usize << lm;
    let mut masks = vec![0u8; mode.nb_ebands];
    let mut lowband_offset = 0usize;
    let mut update_lowband = true;
    let norm_offset = m * mode.e_bands[start] as usize;
    let mut norm_hist = vec![0.0f32; x.len()];
    for i in start..end {
        let tell = dec.tell_frac() as i32;
        if i != start {
            balance -= tell;
        }
        let remaining_bits = total_bits_q - tell - 1;
        let b = if i < coded_bands {
            let curr_balance = balance / ((coded_bands - i).min(3) as i32);
            (pulses[i] + curr_balance)
                .clamp(0, 16_383)
                .min(remaining_bits + 1)
        } else {
            0
        };
        let band_start = m * mode.e_bands[i] as usize;
        let band_end = m * mode.e_bands[i + 1] as usize;
        let n = band_end - band_start;
        if i != start && (update_lowband || lowband_offset == 0) {
            lowband_offset = i;
        }
        let frame_blocks = if short_blocks { m } else { 1 };
        let long_blocks = frame_blocks == 1;
        let mut b_blocks = frame_blocks;
        let spread_eff = spread.clamp(0, 3);
        let tf_change = tf_res[i];
        let mut n_b = n / b_blocks.max(1);
        let n_b_initial = n_b;
        let mut effective_lowband = None::<usize>;
        let mut x_cm = if b_blocks >= 32 {
            u32::MAX
        } else {
            (1u32 << b_blocks) - 1
        };
        if lowband_offset != 0 && (spread != SPREAD_AGGRESSIVE || frame_blocks > 1 || tf_change < 0)
        {
            let lowband_start = m * mode.e_bands[lowband_offset] as usize;
            let effective = lowband_start.saturating_sub(norm_offset + n);
            let fold_base = effective + norm_offset;
            let fold_limit = fold_base + n;
            let mut fold_start = lowband_offset;
            while fold_start > 0 {
                fold_start -= 1;
                if (m * mode.e_bands[fold_start] as usize) <= fold_base {
                    break;
                }
            }
            let mut fold_end = lowband_offset;
            while fold_end < i && (m * mode.e_bands[fold_end] as usize) < fold_limit {
                fold_end += 1;
            }
            x_cm = 0;
            for fold_i in fold_start..fold_end {
                x_cm |= masks[fold_i] as u32;
            }
            effective_lowband = Some(effective);
        }
        let mut fill = x_cm;
        let recombine = tf_change.max(0) as usize;
        for _ in 0..recombine {
            let lo = (fill & 0xF) as usize;
            let hi = ((fill >> 4) & 0xF) as usize;
            fill = BIT_INTERLEAVE_TABLE[lo] | (BIT_INTERLEAVE_TABLE[hi] << 2);
        }
        b_blocks >>= recombine;
        n_b <<= recombine;
        let mut tf_for_blocks = tf_change;
        let mut time_divide = 0usize;
        while (n_b & 1) == 0 && tf_for_blocks < 0 {
            if b_blocks < 32 {
                fill |= fill << b_blocks;
            } else {
                fill = u32::MAX;
            }
            b_blocks <<= 1;
            n_b >>= 1;
            tf_for_blocks += 1;
            time_divide += 1;
        }
        if trace_this_packet {
            debug_trace!(
                "R pkt{} frame_call_idx={} band {} params: b={} N={} B={} fill=0x{:x} lowband_offset={} update_lowband={} spread_eff={} tf_change={}",
                packet_idx,
                frame_call_idx,
                i,
                b,
                n,
                b_blocks,
                fill,
                lowband_offset,
                update_lowband,
                spread_eff,
                tf_change
            );
        }
        let mut remaining_band_bits = remaining_bits;
        let lowband_range = effective_lowband.map(|eff| {
            let src_start = norm_offset + eff;
            (src_start, src_start + n)
        });
        if trace_this_packet {
            if let Some((src_start, src_end)) = lowband_range {
                debug_trace!(
                    "R pkt{} band {} fold: lowband_start={} lowband_end={} B={} fill_in=0x{:x}",
                    packet_idx,
                    i,
                    src_start,
                    src_end,
                    b_blocks,
                    fill
                );
            } else {
                debug_trace!(
                    "R pkt{} band {} fold: lowband_start=-1 lowband_end=-1 B={} fill_in=0x{:x}",
                    packet_idx,
                    i,
                    b_blocks,
                    fill
                );
            }
        }
        let (_x_before, x_after) = x.split_at_mut(band_start);
        let lowband =
            lowband_range.and_then(|(src_start, src_end)| norm_hist.get(src_start..src_end));
        let mut lowband_scratch = None;
        let lowband_for_quant = if let Some(lb) = lowband {
            if recombine > 0 || ((n_b_initial & 1) == 0 && tf_change < 0) || frame_blocks > 1 {
                lowband_scratch = Some(transform_lowband_for_decode(
                    lb,
                    n,
                    frame_blocks,
                    recombine,
                    tf_change,
                    long_blocks,
                ));
                lowband_scratch.as_deref()
            } else {
                Some(lb)
            }
        } else {
            None
        };
        if packet_idx == 6 && i == 8 {
            let (raw_abs, raw_first8) = if let Some(lb) = lowband {
                let abs: f32 = lb.iter().map(|v| v.abs()).sum();
                (
                    abs,
                    [
                        lb.get(0).copied().unwrap_or(0.0),
                        lb.get(1).copied().unwrap_or(0.0),
                        lb.get(2).copied().unwrap_or(0.0),
                        lb.get(3).copied().unwrap_or(0.0),
                        lb.get(4).copied().unwrap_or(0.0),
                        lb.get(5).copied().unwrap_or(0.0),
                        lb.get(6).copied().unwrap_or(0.0),
                        lb.get(7).copied().unwrap_or(0.0),
                    ],
                )
            } else {
                (0.0, [0.0; 8])
            };
            let (prep_abs, prep_first8) = if let Some(lb) = lowband_for_quant {
                let abs: f32 = lb.iter().map(|v| v.abs()).sum();
                (
                    abs,
                    [
                        lb.get(0).copied().unwrap_or(0.0),
                        lb.get(1).copied().unwrap_or(0.0),
                        lb.get(2).copied().unwrap_or(0.0),
                        lb.get(3).copied().unwrap_or(0.0),
                        lb.get(4).copied().unwrap_or(0.0),
                        lb.get(5).copied().unwrap_or(0.0),
                        lb.get(6).copied().unwrap_or(0.0),
                        lb.get(7).copied().unwrap_or(0.0),
                    ],
                )
            } else {
                (0.0, [0.0; 8])
            };
            let data = format!(
                "{{\"packet_idx\":{},\"band\":{},\"raw_lowband_abs\":{},\"raw_lowband_first8\":[{:.9},{:.9},{:.9},{:.9},{:.9},{:.9},{:.9},{:.9}],\"prepared_lowband_abs\":{},\"prepared_lowband_first8\":[{:.9},{:.9},{:.9},{:.9},{:.9},{:.9},{:.9},{:.9}],\"recombine\":{},\"tf_change\":{},\"frame_blocks\":{}}}",
                packet_idx,
                i,
                raw_abs,
                raw_first8[0],
                raw_first8[1],
                raw_first8[2],
                raw_first8[3],
                raw_first8[4],
                raw_first8[5],
                raw_first8[6],
                raw_first8[7],
                prep_abs,
                prep_first8[0],
                prep_first8[1],
                prep_first8[2],
                prep_first8[3],
                prep_first8[4],
                prep_first8[5],
                prep_first8[6],
                prep_first8[7],
                recombine,
                tf_change,
                frame_blocks
            );
            // #region agent log
            append_anti_collapse_debug_log(
                "run-pkt6-lowband-v1",
                "H86",
                "crates/opus-decoder/src/celt/bands.rs:quant_all_bands_mono",
                "rust_pkt6_band8_lowband",
                &data,
            );
            // #endregion
        }
        let tell_before = dec.tell_frac();
        let mut cm = quant_partition_mono(
            mode,
            &mut x_after[..n],
            i,
            b,
            b_blocks,
            lowband_for_quant,
            lm as i32,
            1.0,
            fill,
            spread_eff,
            dec,
            seed,
            &mut remaining_band_bits,
            trace_this_packet,
            packet_idx,
        );
        let cm_raw = cm;
        if packet_idx == 6 && i == 8 {
            let pre_post_tf_abs: f32 = x_after[..n].iter().map(|v| v.abs()).sum();
            let data = format!(
                "{{\"packet_idx\":{},\"band\":{},\"stage\":\"after_quant_partition_before_post_tf\",\"abs_sum\":{},\"first8\":[{:.9},{:.9},{:.9},{:.9},{:.9},{:.9},{:.9},{:.9}],\"n\":{},\"b_blocks\":{},\"recombine\":{},\"time_divide\":{}}}",
                packet_idx,
                i,
                pre_post_tf_abs,
                x_after.get(0).copied().unwrap_or(0.0),
                x_after.get(1).copied().unwrap_or(0.0),
                x_after.get(2).copied().unwrap_or(0.0),
                x_after.get(3).copied().unwrap_or(0.0),
                x_after.get(4).copied().unwrap_or(0.0),
                x_after.get(5).copied().unwrap_or(0.0),
                x_after.get(6).copied().unwrap_or(0.0),
                x_after.get(7).copied().unwrap_or(0.0),
                n,
                b_blocks,
                recombine,
                time_divide
            );
            // #region agent log
            append_anti_collapse_debug_log(
                "run-pkt6-quant-stage-v1",
                "H88",
                "crates/opus-decoder/src/celt/bands.rs:quant_all_bands_mono",
                "rust_pkt6_band8_pre_post_tf",
                &data,
            );
            // #endregion
        }
        apply_quant_band_post_tf(
            &mut x_after[..n],
            n,
            b_blocks,
            recombine,
            time_divide,
            long_blocks,
        );
        cm = post_tf_collapse_mask(cm, b_blocks, recombine, time_divide);
        if packet_idx <= 8 || trace_this_packet {
            let data = format!(
                "{{\"packet_idx\":{},\"band\":{},\"frame_blocks\":{},\"b_blocks\":{},\"recombine\":{},\"time_divide\":{},\"cm_raw\":{},\"cm_post\":{},\"cm_used\":{}}}",
                packet_idx,
                i,
                frame_blocks,
                b_blocks,
                recombine,
                time_divide,
                cm_raw,
                post_tf_collapse_mask(cm_raw, b_blocks, recombine, time_divide),
                cm
            );
            // #region agent log
            append_anti_collapse_debug_log(
                "run-collapse-mask-ab-v1",
                "H8",
                "crates/opus-decoder/src/celt/bands.rs:quant_all_bands_mono",
                "collapse_mask_post_tf_mapping",
                &data,
            );
            // #endregion
        }
        if packet_idx == 6 {
            let band_abs_sum: f32 = x_after[..n].iter().map(|v| v.abs()).sum();
            let data = format!(
                "{{\"packet_idx\":{},\"band\":{},\"n\":{},\"b\":{},\"frame_blocks\":{},\"b_blocks\":{},\"tf_change\":{},\"spread_eff\":{},\"recombine\":{},\"time_divide\":{},\"fill\":{},\"cm\":{},\"band_abs_sum\":{},\"band_first4\":[{:.9},{:.9},{:.9},{:.9}],\"band_last4\":[{:.9},{:.9},{:.9},{:.9}],\"lowband_offset\":{},\"lowband_present\":{}}}",
                packet_idx,
                i,
                n,
                b,
                frame_blocks,
                b_blocks,
                tf_change,
                spread_eff,
                recombine,
                time_divide,
                fill,
                cm,
                band_abs_sum,
                x_after.get(0).copied().unwrap_or(0.0),
                x_after.get(1).copied().unwrap_or(0.0),
                x_after.get(2).copied().unwrap_or(0.0),
                x_after.get(3).copied().unwrap_or(0.0),
                x_after.get(n.saturating_sub(4)).copied().unwrap_or(0.0),
                x_after.get(n.saturating_sub(3)).copied().unwrap_or(0.0),
                x_after.get(n.saturating_sub(2)).copied().unwrap_or(0.0),
                x_after.get(n.saturating_sub(1)).copied().unwrap_or(0.0),
                lowband_offset,
                lowband_for_quant.is_some()
            );
            // #region agent log
            append_anti_collapse_debug_log(
                "run-pkt6-quant-band-v1",
                "H85",
                "crates/opus-decoder/src/celt/bands.rs:quant_all_bands_mono",
                "rust_pkt6_quant_band_after",
                &data,
            );
            // #endregion
        }
        if i + 1 < end {
            let scale = (n as f32).sqrt();
            for j in 0..n {
                norm_hist[band_start + j] = x_after[j] * scale;
            }
        }
        let tell_after = dec.tell_frac();
        if trace_this_packet {
            debug_trace!(
                "pkt{} frame_call_idx={} band {} tell: {}->{} ({}bits)",
                packet_idx,
                frame_call_idx,
                i,
                tell_before,
                tell_after,
                tell_after as i32 - tell_before as i32
            );
            debug_trace!(
                "R pkt{} frame_call_idx={} band {} cm_out=0x{:x}",
                packet_idx,
                frame_call_idx,
                i,
                cm
            );
        }
        masks[i] = (cm & 0xFF) as u8;
        balance += pulses[i] + tell;
        update_lowband = b > ((n as i32) << BITRES);
    }
    masks
}

/// Decode all CELT bands in stereo decode-only mode.
///
/// Params: mode, band range, stereo normalized spectra, allocation/TF flags and coder state.
/// Returns: collapse mask per channel-band slot (`2 * nb_ebands`).
#[allow(clippy::too_many_arguments)]
pub(crate) fn quant_all_bands_stereo(
    mode: &CeltMode,
    start: usize,
    end: usize,
    x: &mut [f32],
    y: &mut [f32],
    pulses: &[i32],
    short_blocks: bool,
    spread: i32,
    tf_res: &[i32],
    total_bits_q: i32,
    mut balance: i32,
    coded_bands: usize,
    lm: usize,
    dec: &mut EcDec<'_>,
    seed: &mut u32,
    dual_stereo: i32,
    intensity: i32,
    disable_inv: bool,
    packet_idx: usize,
    frame_call_idx: usize,
) -> Vec<u8> {
    let trace_target = None;
    let trace_this_packet = trace_target == Some(packet_idx);
    let m = 1usize << lm;
    let mut masks = vec![0u8; mode.nb_ebands * 2];
    let mut lowband_offset = 0usize;
    let mut update_lowband = true;
    let norm_offset = m * mode.e_bands[start] as usize;
    let mut dual_stereo_on = dual_stereo != 0;
    let mut norm_hist_x = vec![0.0f32; x.len()];
    let mut norm_hist_y = vec![0.0f32; y.len()];

    for i in start..end {
        let tell = dec.tell_frac() as i32;
        if i != start {
            balance -= tell;
        }
        let remaining_bits = total_bits_q - tell - 1;
        let b = if i < coded_bands {
            let curr_balance = balance / ((coded_bands - i).min(3) as i32);
            (pulses[i] + curr_balance)
                .clamp(0, 16_383)
                .min(remaining_bits + 1)
        } else {
            0
        };
        let band_start = m * mode.e_bands[i] as usize;
        let band_end = m * mode.e_bands[i + 1] as usize;
        let n = band_end - band_start;
        if i != start && (update_lowband || lowband_offset == 0) {
            lowband_offset = i;
        }

        let frame_blocks = if short_blocks { m } else { 1 };
        let long_blocks = frame_blocks == 1;
        let mut b_blocks = frame_blocks;
        let spread_eff = spread.clamp(0, 3);
        let tf_change = tf_res[i];
        let mut n_b = n / b_blocks.max(1);
        let n_b_initial = n_b;

        if i == start + 1 {
            special_hybrid_folding_decode(
                mode,
                &mut norm_hist_x[norm_offset..],
                &mut norm_hist_y[norm_offset..],
                start,
                m,
                dual_stereo_on,
            );
        }

        let mut effective_lowband = None::<usize>;
        let mut x_cm = if b_blocks >= 32 {
            u32::MAX
        } else {
            (1u32 << b_blocks) - 1
        };
        let mut y_cm = x_cm;
        if lowband_offset != 0 && (spread != SPREAD_AGGRESSIVE || frame_blocks > 1 || tf_change < 0)
        {
            let lowband_start = m * mode.e_bands[lowband_offset] as usize;
            let effective = lowband_start.saturating_sub(norm_offset + n);
            let fold_base = effective + norm_offset;
            let fold_limit = fold_base + n;
            let mut fold_start = lowband_offset;
            while fold_start > 0 {
                fold_start -= 1;
                if (m * mode.e_bands[fold_start] as usize) <= fold_base {
                    break;
                }
            }
            let mut fold_end = lowband_offset;
            while fold_end < i && (m * mode.e_bands[fold_end] as usize) < fold_limit {
                fold_end += 1;
            }
            x_cm = 0;
            y_cm = 0;
            for fold_i in fold_start..fold_end {
                x_cm |= masks[fold_i * 2] as u32;
                y_cm |= masks[fold_i * 2 + 1] as u32;
            }
            effective_lowband = Some(effective);
        }

        if dual_stereo_on && i == intensity as usize {
            dual_stereo_on = false;
            for j in norm_offset..band_start {
                norm_hist_x[j] = 0.5 * (norm_hist_x[j] + norm_hist_y[j]);
            }
        }

        let fill_theta = x_cm | y_cm;
        let mut fill = fill_theta;
        let mut fill_x = x_cm;
        let mut fill_y = y_cm;
        let recombine = tf_change.max(0) as usize;
        for _ in 0..recombine {
            let lo = (fill & 0xF) as usize;
            let hi = ((fill >> 4) & 0xF) as usize;
            fill = BIT_INTERLEAVE_TABLE[lo] | (BIT_INTERLEAVE_TABLE[hi] << 2);

            let lox = (fill_x & 0xF) as usize;
            let hix = ((fill_x >> 4) & 0xF) as usize;
            fill_x = BIT_INTERLEAVE_TABLE[lox] | (BIT_INTERLEAVE_TABLE[hix] << 2);

            let loy = (fill_y & 0xF) as usize;
            let hiy = ((fill_y >> 4) & 0xF) as usize;
            fill_y = BIT_INTERLEAVE_TABLE[loy] | (BIT_INTERLEAVE_TABLE[hiy] << 2);
        }

        b_blocks >>= recombine;
        n_b <<= recombine;
        let mut tf_for_blocks = tf_change;
        let mut time_divide = 0usize;
        while (n_b & 1) == 0 && tf_for_blocks < 0 {
            if b_blocks < 32 {
                fill |= fill << b_blocks;
                fill_x |= fill_x << b_blocks;
                fill_y |= fill_y << b_blocks;
            } else {
                fill = u32::MAX;
                fill_x = u32::MAX;
                fill_y = u32::MAX;
            }
            b_blocks <<= 1;
            n_b >>= 1;
            tf_for_blocks += 1;
            time_divide += 1;
        }

        let lowband_range = effective_lowband.map(|eff| {
            let src_start = norm_offset + eff;
            (src_start, src_start + n)
        });
        if trace_this_packet {
            // #region agent log
            debug_trace!(
                "R pkt{} frame_call_idx={} band {} params: b={} N={} lowband_offset={} update_lowband={} spread_eff={} tf_change={} effective_lowband={} x_cm=0x{:x} y_cm=0x{:x} B={}",
                packet_idx,
                frame_call_idx,
                i,
                b,
                n,
                lowband_offset,
                update_lowband,
                spread_eff,
                tf_change,
                effective_lowband.map(|v| v as i32).unwrap_or(-1),
                x_cm,
                y_cm,
                b_blocks
            );
            // #endregion
        }
        let (_x_before, x_after) = x.split_at_mut(band_start);
        let (_y_before, y_after) = y.split_at_mut(band_start);

        let lowband_x =
            lowband_range.and_then(|(src_start, src_end)| norm_hist_x.get(src_start..src_end));
        let lowband_y =
            lowband_range.and_then(|(src_start, src_end)| norm_hist_y.get(src_start..src_end));

        let mut lowband_x_scratch = None;
        let mut lowband_y_scratch = None;
        let lowband_x_for_quant = if let Some(lb) = lowband_x {
            if recombine > 0 || ((n_b_initial & 1) == 0 && tf_change < 0) || frame_blocks > 1 {
                lowband_x_scratch = Some(transform_lowband_for_decode(
                    lb,
                    n,
                    frame_blocks,
                    recombine,
                    tf_change,
                    long_blocks,
                ));
                lowband_x_scratch.as_deref()
            } else {
                Some(lb)
            }
        } else {
            None
        };
        let lowband_y_for_quant = if let Some(lb) = lowband_y {
            if recombine > 0 || ((n_b_initial & 1) == 0 && tf_change < 0) || frame_blocks > 1 {
                lowband_y_scratch = Some(transform_lowband_for_decode(
                    lb,
                    n,
                    frame_blocks,
                    recombine,
                    tf_change,
                    long_blocks,
                ));
                lowband_y_scratch.as_deref()
            } else {
                Some(lb)
            }
        } else {
            None
        };

        let mut remaining_band_bits = remaining_bits;
        let tell_before = dec.tell_frac();
        let (cm_l, cm_r) = if dual_stereo_on {
            let cmx_raw = quant_partition_mono(
                mode,
                &mut x_after[..n],
                i,
                b / 2,
                b_blocks,
                lowband_x_for_quant,
                lm as i32,
                1.0,
                fill_x,
                spread_eff,
                dec,
                seed,
                &mut remaining_band_bits,
                trace_this_packet,
                packet_idx,
            );
            let cmy_raw = quant_partition_mono(
                mode,
                &mut y_after[..n],
                i,
                b / 2,
                b_blocks,
                lowband_y_for_quant,
                lm as i32,
                1.0,
                fill_y,
                spread_eff,
                dec,
                seed,
                &mut remaining_band_bits,
                trace_this_packet,
                packet_idx,
            );
            apply_quant_band_post_tf(
                &mut x_after[..n],
                n,
                b_blocks,
                recombine,
                time_divide,
                long_blocks,
            );
            apply_quant_band_post_tf(
                &mut y_after[..n],
                n,
                b_blocks,
                recombine,
                time_divide,
                long_blocks,
            );
            let cmx = post_tf_collapse_mask(cmx_raw, b_blocks, recombine, time_divide);
            let cmy = post_tf_collapse_mask(cmy_raw, b_blocks, recombine, time_divide);
            if i + 1 < end {
                let scale = (n as f32).sqrt();
                for j in 0..n {
                    norm_hist_x[band_start + j] = x_after[j] * scale;
                    norm_hist_y[band_start + j] = y_after[j] * scale;
                }
            }
            (cmx, cmy)
        } else {
            let mut mid_hist = vec![0.0f32; n];
            if trace_this_packet && (i == 12 || i >= 18) {
                // #region agent log
                let log_path = std::env::current_dir()
                    .unwrap_or_else(|_| std::path::PathBuf::from("."))
                    .join(".cursor")
                    .join("debug-bea564.log");
                if let Ok(mut f) = std::fs::OpenOptions::new()
                    .create(true)
                    .append(true)
                    .open(log_path)
                {
                    let ts = std::time::SystemTime::now()
                        .duration_since(std::time::UNIX_EPOCH)
                        .map(|d| d.as_millis())
                        .unwrap_or(0);
                    let line = format!(
                        "{{\"sessionId\":\"bea564\",\"runId\":\"run-tv01-tv10-debug1\",\"hypothesisId\":\"H2\",\"location\":\"crates/opus-decoder/src/celt/bands.rs:quant_all_bands_stereo\",\"message\":\"stereo_band_entry\",\"data\":{{\"packet_idx\":{},\"band\":{},\"n\":{},\"b\":{},\"b_blocks\":{},\"frame_blocks\":{},\"remaining_band_bits\":{},\"intensity\":{},\"dual_stereo_on\":{},\"tf_change\":{},\"recombine\":{},\"time_divide\":{},\"fill_theta\":{},\"fill_tf\":{}}},\"timestamp\":{}}}\n",
                        packet_idx,
                        i,
                        n,
                        b,
                        b_blocks,
                        frame_blocks,
                        remaining_band_bits,
                        intensity,
                        dual_stereo_on,
                        tf_change,
                        recombine,
                        time_divide,
                        fill_theta,
                        fill,
                        ts
                    );
                    let _ = std::io::Write::write_all(&mut f, line.as_bytes());
                }
                // #endregion
            }
            let cm = quant_band_stereo_decode(
                mode,
                &mut x_after[..n],
                &mut y_after[..n],
                i,
                b,
                b_blocks,
                frame_blocks,
                lowband_x_for_quant.as_deref(),
                lm as i32,
                fill_theta,
                spread_eff,
                disable_inv,
                dec,
                seed,
                &mut remaining_band_bits,
                intensity,
                recombine,
                time_divide,
                long_blocks,
                Some(&mut mid_hist),
                trace_this_packet,
                packet_idx,
            );
            if trace_this_packet && (i == 12 || i >= 18) {
                // #region agent log
                let log_path = std::env::current_dir()
                    .unwrap_or_else(|_| std::path::PathBuf::from("."))
                    .join(".cursor")
                    .join("debug-bea564.log");
                if let Ok(mut f) = std::fs::OpenOptions::new()
                    .create(true)
                    .append(true)
                    .open(log_path)
                {
                    let ts = std::time::SystemTime::now()
                        .duration_since(std::time::UNIX_EPOCH)
                        .map(|d| d.as_millis())
                        .unwrap_or(0);
                    let line = format!(
                        "{{\"sessionId\":\"bea564\",\"runId\":\"run-tv01-tv10-debug1\",\"hypothesisId\":\"H3\",\"location\":\"crates/opus-decoder/src/celt/bands.rs:quant_all_bands_stereo\",\"message\":\"stereo_band_exit\",\"data\":{{\"packet_idx\":{},\"band\":{},\"cm\":{},\"remaining_band_bits\":{}}},\"timestamp\":{}}}\n",
                        packet_idx, i, cm, remaining_band_bits, ts
                    );
                    let _ = std::io::Write::write_all(&mut f, line.as_bytes());
                }
                // #endregion
            }
            if i + 1 < end {
                norm_hist_x[band_start..band_start + n].copy_from_slice(&mid_hist);
            }
            (cm, cm)
        };

        let tell_after = dec.tell_frac();
        if trace_this_packet {
            debug_trace!(
                "pkt{} frame_call_idx={} stereo band {} tell: {}->{} ({}bits) dual_stereo={}",
                packet_idx,
                frame_call_idx,
                i,
                tell_before,
                tell_after,
                tell_after as i32 - tell_before as i32,
                dual_stereo_on
            );
            // #region agent log
            debug_trace!(
                "R pkt{} frame_call_idx={} band {} cm_out: x=0x{:x} y=0x{:x}",
                packet_idx,
                frame_call_idx,
                i,
                cm_l,
                cm_r
            );
            // #endregion
        }
        masks[i * 2] = (cm_l & 0xFF) as u8;
        masks[i * 2 + 1] = (cm_r & 0xFF) as u8;
        balance += pulses[i] + tell;
        update_lowband = b > ((n as i32) << BITRES);
    }
    masks
}

/// De-normalize normalized bands to MDCT bins using decoded energies.
///
/// Params: mode, normalized `x`, output `freq`, decoded `band_loge`, band range,
/// and `silence` flag forcing zero spectrum output.
/// Returns: nothing.
pub(crate) fn denormalise_bands(
    mode: &CeltMode,
    x: &[f32],
    freq: &mut [f32],
    band_loge: &[f32],
    start: usize,
    end: usize,
    lm: usize,
    silence: bool,
) {
    if silence {
        freq.fill(0.0);
        return;
    }
    let m = 1usize << lm;
    for i in start..end {
        let j0 = m * mode.e_bands[i] as usize;
        let j1 = m * mode.e_bands[i + 1] as usize;
        let lg = band_loge[i] + quant_bands::e_means()[i];
        let g = 2.0f32.powf(lg.max(-32.0));
        for j in j0..j1 {
            freq[j] = x[j] * g;
        }
    }
}
