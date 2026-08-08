//! CELT bit allocation logic (`rate.c`) for decoder path.
#![allow(clippy::too_many_arguments)]

use crate::celt::modes::CeltMode;
use crate::entropy::EcDec;

const BITRES: i32 = 3;
const FINE_OFFSET: i32 = 21;
const MAX_FINE_BITS: i32 = 8;
const ALLOC_STEPS: i32 = 6;
const LOG2_FRAC_TABLE: [u8; 24] = [
    0, 8, 13, 16, 19, 21, 23, 24, 26, 27, 28, 29, 30, 31, 32, 32, 33, 34, 34, 35, 36, 36, 37, 37,
];
const LOG_MAX_PSEUDO: usize = 6;

/// Convert pseudo-pulse index to real pulse count.
///
/// Params: `i` is pseudo index from CELT pulse cache.
/// Returns: actual pulse count for PVQ decode.
pub(crate) fn get_pulses(i: i32) -> i32 {
    if i < 8 {
        i
    } else {
        (8 + (i & 7)) << ((i >> 3) - 1)
    }
}

/// Convert allocated bits to pseudo pulse index.
///
/// Params: `mode` CELT mode, `band` index, `lm` frame size log2, `bits` in BITRES units.
/// Returns: pseudo pulse index used by `get_pulses`.
pub(crate) fn bits2pulses(mode: &CeltMode, band: usize, lm: i32, bits: i32) -> i32 {
    let lm1 = (lm + 1).max(0) as usize;
    let cache_base = mode.cache.index[lm1 * mode.nb_ebands + band] as usize;
    let cache = &mode.cache.bits[cache_base..];
    let mut lo = 0i32;
    let mut hi = cache[0] as i32;
    let target = bits - 1;
    for _ in 0..LOG_MAX_PSEUDO {
        let mid = (lo + hi + 1) >> 1;
        if cache[mid as usize] as i32 >= target {
            hi = mid;
        } else {
            lo = mid;
        }
    }
    let lo_bits = if lo == 0 {
        -1
    } else {
        cache[lo as usize] as i32
    };
    if target - lo_bits <= cache[hi as usize] as i32 - target {
        lo
    } else {
        hi
    }
}

/// Convert pseudo pulse index back to spent bits.
///
/// Params: `mode` CELT mode, `band` index, `lm` frame size log2, `pulses` pseudo pulse index.
/// Returns: number of bits in BITRES units spent by this pulse count.
pub(crate) fn pulses2bits(mode: &CeltMode, band: usize, lm: i32, pulses: i32) -> i32 {
    if pulses == 0 {
        return 0;
    }
    let lm1 = (lm + 1).max(0) as usize;
    let cache_base = mode.cache.index[lm1 * mode.nb_ebands + band] as usize;
    let cache = &mode.cache.bits[cache_base..];
    cache[pulses as usize] as i32 + 1
}

/// Allocation result arrays produced by CELT `clt_compute_allocation`.
#[derive(Debug, Clone)]
pub(crate) struct AllocationResult {
    pub coded_bands: usize,
    pub intensity: i32,
    pub dual_stereo: i32,
    pub balance: i32,
    pub pulses: Vec<i32>,
    pub fine_quant: Vec<i32>,
    pub fine_priority: Vec<i32>,
}

/// Initialize CELT per-band bit caps for given `LM` and channel count.
///
/// Params: `mode` is CELT mode table, `lm` is frame size log ratio, `channels`
/// is decode channel count.
/// Returns: per-band cap values in BITRES units.
pub(crate) fn init_caps(mode: &CeltMode, lm: usize, channels: usize) -> Vec<i32> {
    let mut cap = vec![0i32; mode.nb_ebands];
    for (i, cap_i) in cap.iter_mut().enumerate() {
        let n = ((mode.e_bands[i + 1] - mode.e_bands[i]) as usize) << lm;
        let idx = mode.nb_ebands * (2 * lm + channels - 1) + i;
        *cap_i = (((mode.cache.caps[idx] as i32) + 64) * channels as i32 * n as i32) >> 2;
    }
    cap
}

/// Compute CELT allocation for one frame (decode path).
///
/// Params: `mode` CELT mode, `start..end` active bands, `offsets` dynamic
/// allocation boosts, `cap` max bits per band, `alloc_trim` trim symbol,
/// `total` available bits in BITRES units, `channels`, `lm`, and `dec`.
/// Returns: full allocation result (coded bands, pulses, fine bits, balance).
pub(crate) fn clt_compute_allocation(
    mode: &CeltMode,
    start: usize,
    end: usize,
    offsets: &[i32],
    cap: &[i32],
    alloc_trim: i32,
    total: i32,
    channels: usize,
    lm: usize,
    dec: &mut EcDec<'_>,
    _packet_idx: usize,
) -> AllocationResult {
    let mut total_bits = total.max(0);
    let skip_rsv = if total_bits >= (1 << BITRES) {
        1 << BITRES
    } else {
        0
    };
    total_bits -= skip_rsv;

    let mut intensity_rsv = 0i32;
    let mut dual_stereo_rsv = 0i32;
    if channels == 2 {
        intensity_rsv = LOG2_FRAC_TABLE[end - start] as i32;
        if intensity_rsv > total_bits {
            intensity_rsv = 0;
        } else {
            total_bits -= intensity_rsv;
            dual_stereo_rsv = if total_bits >= (1 << BITRES) {
                1 << BITRES
            } else {
                0
            };
            total_bits -= dual_stereo_rsv;
        }
    }

    let len = mode.nb_ebands;
    let mut bits1 = vec![0i32; len];
    let mut bits2 = vec![0i32; len];
    let mut thresh = vec![0i32; len];
    let mut trim_offset = vec![0i32; len];
    let c = channels as i32;
    for j in start..end {
        let band_n = (mode.e_bands[j + 1] - mode.e_bands[j]) as i32;
        thresh[j] = (c << BITRES).max((3 * band_n << lm << BITRES) >> 4);
        trim_offset[j] = c
            * band_n
            * (alloc_trim - 5 - lm as i32)
            * (end - j - 1) as i32
            * (1 << (lm as i32 + BITRES))
            >> 6;
        if (band_n << lm) == 1 {
            trim_offset[j] -= c << BITRES;
        }
    }
    let mut lo = 1i32;
    let mut hi = mode.nb_alloc_vectors as i32 - 1;
    while lo <= hi {
        let mut done = false;
        let mut psum = 0i32;
        let mid = (lo + hi) >> 1;
        for j in (start..end).rev() {
            let n = (mode.e_bands[j + 1] - mode.e_bands[j]) as i32;
            let mut bits_j =
                c * n * ((mode.alloc_vectors[mid as usize * len + j] as i32) << lm) >> 2;
            if bits_j > 0 {
                bits_j = (bits_j + trim_offset[j]).max(0);
            }
            bits_j += offsets[j];
            if bits_j >= thresh[j] || done {
                done = true;
                psum += bits_j.min(cap[j]);
            } else if bits_j >= c << BITRES {
                psum += c << BITRES;
            }
        }
        if psum > total_bits {
            hi = mid - 1;
        } else {
            lo = mid + 1;
        }
    }
    hi = lo;
    lo -= 1;

    let mut skip_start = start;
    for j in start..end {
        let n = (mode.e_bands[j + 1] - mode.e_bands[j]) as i32;
        let mut bits1j = c * n * ((mode.alloc_vectors[lo as usize * len + j] as i32) << lm) >> 2;
        let mut bits2j = if hi as usize >= mode.nb_alloc_vectors {
            cap[j]
        } else {
            c * n * ((mode.alloc_vectors[hi as usize * len + j] as i32) << lm) >> 2
        };
        if bits1j > 0 {
            bits1j = (bits1j + trim_offset[j]).max(0);
        }
        if bits2j > 0 {
            bits2j = (bits2j + trim_offset[j]).max(0);
        }
        if lo > 0 {
            bits1j += offsets[j];
        }
        bits2j += offsets[j];
        if offsets[j] > 0 {
            skip_start = j;
        }
        bits2j = (bits2j - bits1j).max(0);
        bits1[j] = bits1j;
        bits2[j] = bits2j;
    }

    let mut pulses = vec![0i32; len];
    let mut fine_quant = vec![0i32; len];
    let mut fine_priority = vec![0i32; len];
    let mut intensity = 0i32;
    let mut dual_stereo = 0i32;
    let (coded_bands, balance) = interp_bits2pulses(
        mode,
        start,
        end,
        skip_start,
        &bits1,
        &bits2,
        &thresh,
        cap,
        total_bits,
        skip_rsv,
        &mut intensity,
        intensity_rsv,
        &mut dual_stereo,
        dual_stereo_rsv,
        &mut pulses,
        &mut fine_quant,
        &mut fine_priority,
        channels,
        lm,
        dec,
        _packet_idx,
    );

    AllocationResult {
        coded_bands,
        intensity,
        dual_stereo,
        balance,
        pulses,
        fine_quant,
        fine_priority,
    }
}

/// Interpolate allocation vectors and derive pulse/fine bit splits.
///
/// Params: direct port of libopus `interp_bits2pulses` decoder flow.
/// Returns: number of coded bands.
#[allow(clippy::too_many_arguments)]
fn interp_bits2pulses(
    mode: &CeltMode,
    start: usize,
    end: usize,
    skip_start: usize,
    bits1: &[i32],
    bits2: &[i32],
    thresh: &[i32],
    cap: &[i32],
    total: i32,
    skip_rsv: i32,
    intensity: &mut i32,
    mut intensity_rsv: i32,
    dual_stereo: &mut i32,
    mut dual_stereo_rsv: i32,
    bits: &mut [i32],
    ebits: &mut [i32],
    fine_priority: &mut [i32],
    channels: usize,
    lm: usize,
    dec: &mut EcDec<'_>,
    _packet_idx: usize,
) -> (usize, i32) {
    let trace_packet0 = false;
    let c = channels as i32;
    let stereo = channels > 1;
    let alloc_floor = c << BITRES;
    let log_m = (lm as i32) << BITRES;

    let mut lo = 0i32;
    let mut hi = 1 << ALLOC_STEPS;
    for _ in 0..ALLOC_STEPS {
        let mid = (lo + hi) >> 1;
        let mut psum = 0i32;
        let mut done = false;
        for j in (start..end).rev() {
            let tmp = bits1[j] + ((mid * bits2[j]) >> ALLOC_STEPS);
            if tmp >= thresh[j] || done {
                done = true;
                psum += tmp.min(cap[j]);
            } else if tmp >= alloc_floor {
                psum += alloc_floor;
            }
        }
        if psum > total {
            hi = mid;
        } else {
            lo = mid;
        }
    }

    let mut psum = 0i32;
    let mut done = false;
    for j in (start..end).rev() {
        let mut tmp = bits1[j] + ((lo * bits2[j]) >> ALLOC_STEPS);
        if tmp < thresh[j] && !done {
            tmp = if tmp >= alloc_floor { alloc_floor } else { 0 };
        } else {
            done = true;
        }
        tmp = tmp.min(cap[j]);
        bits[j] = tmp;
        psum += tmp;
    }

    let mut coded_bands = end;
    let mut total_adj = total;
    loop {
        let j = coded_bands - 1;
        if j <= skip_start {
            // Match libopus: give back reserved skip bit only when skipping loop ends naturally.
            total_adj += skip_rsv;
            break;
        }
        let left = total - psum;
        let denom = (mode.e_bands[coded_bands] - mode.e_bands[start]) as i32;
        let percoeff = if denom > 0 { left / denom } else { 0 };
        let left_rem = left - denom * percoeff;
        let rem = (left_rem - (mode.e_bands[j] - mode.e_bands[start]) as i32).max(0);
        let band_width = (mode.e_bands[coded_bands] - mode.e_bands[j]) as i32;
        let mut band_bits = bits[j] + percoeff * band_width + rem;
        if band_bits >= thresh[j].max(alloc_floor + (1 << BITRES)) {
            if dec.dec_bit_logp(1) {
                break;
            }
            psum += 1 << BITRES;
            band_bits -= 1 << BITRES;
        }
        psum -= bits[j] + intensity_rsv;
        if intensity_rsv > 0 {
            intensity_rsv = LOG2_FRAC_TABLE[j - start] as i32;
        }
        psum += intensity_rsv;
        bits[j] = if band_bits >= alloc_floor {
            alloc_floor
        } else {
            0
        };
        psum += bits[j];
        coded_bands -= 1;
    }
    coded_bands = coded_bands.max(start + 1);

    if intensity_rsv > 0 {
        *intensity = start as i32 + dec.dec_uint((coded_bands + 1 - start) as u32) as i32;
    } else {
        *intensity = 0;
    }
    if *intensity <= start as i32 {
        total_adj += dual_stereo_rsv;
        dual_stereo_rsv = 0;
    }
    if dual_stereo_rsv > 0 {
        *dual_stereo = i32::from(dec.dec_bit_logp(1));
    } else {
        *dual_stereo = 0;
    }
    if trace_packet0 {
        debug_trace!(
            "[RUST] alloc: coded_bands={} psum={} total_adj={}",
            coded_bands,
            psum,
            total_adj
        );
    }

    let left = total_adj - psum;
    let denom = (mode.e_bands[coded_bands] - mode.e_bands[start]) as i32;
    let percoeff = if denom > 0 { left / denom } else { 0 };
    let mut left_rem = left - denom * percoeff;
    for (j, bits_j) in bits.iter_mut().enumerate().take(coded_bands).skip(start) {
        *bits_j += percoeff * (mode.e_bands[j + 1] - mode.e_bands[j]) as i32;
        let tmp = left_rem.min((mode.e_bands[j + 1] - mode.e_bands[j]) as i32);
        *bits_j += tmp;
        left_rem -= tmp;
    }

    let mut balance = 0i32;
    let stereo_shift = if stereo { 1 } else { 0 };
    for j in start..coded_bands {
        let n0 = (mode.e_bands[j + 1] - mode.e_bands[j]) as i32;
        let n = n0 << lm;
        let bit = bits[j] + balance;
        let mut excess;
        if n > 1 {
            excess = (bit - cap[j]).max(0);
            bits[j] = bit - excess;
            let den = c * n
                + if channels == 2 && n > 2 && *dual_stereo == 0 && j < *intensity as usize {
                    1
                } else {
                    0
                };
            let nclogn = den * (mode.log_n[j] as i32 + log_m);
            let mut offset = (nclogn >> 1) - den * FINE_OFFSET;
            if n == 2 {
                offset += den << BITRES >> 2;
            }
            if bits[j] + offset < den * 2 << BITRES {
                offset += nclogn >> 2;
            } else if bits[j] + offset < den * 3 << BITRES {
                offset += nclogn >> 3;
            }
            let mut e = (bits[j] + offset + (den << (BITRES - 1))).max(0);
            e = (e / den) >> BITRES;
            if c * e > (bits[j] >> BITRES) {
                e = (bits[j] >> stereo_shift) >> BITRES;
            }
            e = e.min(MAX_FINE_BITS);
            ebits[j] = e;
            fine_priority[j] = i32::from(ebits[j] * (den << BITRES) >= bits[j] + offset);
            bits[j] -= c * ebits[j] << BITRES;
        } else {
            excess = (bit - (c << BITRES)).max(0);
            bits[j] = bit - excess;
            ebits[j] = 0;
            fine_priority[j] = 1;
        }
        if excess > 0 {
            let extra_fine =
                ((excess >> (stereo_shift + BITRES)).max(0)).min(MAX_FINE_BITS - ebits[j]);
            ebits[j] += extra_fine;
            let extra_bits = (extra_fine * c) << BITRES;
            fine_priority[j] = i32::from(extra_bits >= excess - balance);
            excess -= extra_bits;
        }
        balance = excess;
    }
    for j in coded_bands..end {
        ebits[j] = bits[j] >> if stereo { 1 } else { 0 } >> BITRES;
        bits[j] = 0;
        fine_priority[j] = i32::from(ebits[j] < 1);
    }
    (coded_bands, balance)
}
