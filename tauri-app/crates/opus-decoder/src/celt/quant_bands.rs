//! CELT energy dequantization (`quant_bands.c`) for decoder path.
#![allow(
    unused_variables,
    unused_assignments,
    dead_code,
    clippy::too_many_arguments,
    clippy::needless_range_loop,
    clippy::unnecessary_cast
)]

use crate::celt::laplace::ec_laplace_decode;
use crate::celt::modes::CeltMode;
use crate::entropy::EcDec;
use std::sync::atomic::{AtomicUsize, Ordering};

const MAX_FINE_BITS: i32 = 8;
const E_MEANS: [f32; 25] = [
    6.4375, 6.25, 5.75, 5.3125, 5.0625, 4.8125, 4.5, 4.375, 4.875, 4.6875, 4.5625, 4.4375, 4.875,
    4.625, 4.3125, 4.5, 4.375, 4.625, 4.75, 4.4375, 3.75, 3.75, 3.75, 3.75, 3.75,
];
const PRED_COEF: [f32; 4] = [
    29440.0 / 32768.0,
    26112.0 / 32768.0,
    21248.0 / 32768.0,
    16384.0 / 32768.0,
];
const BETA_COEF: [f32; 4] = [
    30147.0 / 32768.0,
    22282.0 / 32768.0,
    12124.0 / 32768.0,
    6554.0 / 32768.0,
];
const BETA_INTRA: f32 = 4915.0 / 32768.0;
const SMALL_ENERGY_ICDF: [u8; 3] = [2, 1, 0];
static TRACE_COARSE_CALL_IDX: AtomicUsize = AtomicUsize::new(0);
static TRACE_FINE_CALL_IDX: AtomicUsize = AtomicUsize::new(0);
static TRACE_FINALISE_CALL_IDX: AtomicUsize = AtomicUsize::new(0);

const E_PROB_MODEL: [[[u8; 42]; 2]; 4] = [
    [
        [
            72, 127, 65, 129, 66, 128, 65, 128, 64, 128, 62, 128, 64, 128, 64, 128, 92, 78, 92, 79,
            92, 78, 90, 79, 116, 41, 115, 40, 114, 40, 132, 26, 132, 26, 145, 17, 161, 12, 176, 10,
            177, 11,
        ],
        [
            24, 179, 48, 138, 54, 135, 54, 132, 53, 134, 56, 133, 55, 132, 55, 132, 61, 114, 70,
            96, 74, 88, 75, 88, 87, 74, 89, 66, 91, 67, 100, 59, 108, 50, 120, 40, 122, 37, 97, 43,
            78, 50,
        ],
    ],
    [
        [
            83, 78, 84, 81, 88, 75, 86, 74, 87, 71, 90, 73, 93, 74, 93, 74, 109, 40, 114, 36, 117,
            34, 117, 34, 143, 17, 145, 18, 146, 19, 162, 12, 165, 10, 178, 7, 189, 6, 190, 8, 177,
            9,
        ],
        [
            23, 178, 54, 115, 63, 102, 66, 98, 69, 99, 74, 89, 71, 91, 73, 91, 78, 89, 86, 80, 92,
            66, 93, 64, 102, 59, 103, 60, 104, 60, 117, 52, 123, 44, 138, 35, 133, 31, 97, 38, 77,
            45,
        ],
    ],
    [
        [
            61, 90, 93, 60, 105, 42, 107, 41, 110, 45, 116, 38, 113, 38, 112, 38, 124, 26, 132, 27,
            136, 19, 140, 20, 155, 14, 159, 16, 158, 18, 170, 13, 177, 10, 187, 8, 192, 6, 175, 9,
            159, 10,
        ],
        [
            21, 178, 59, 110, 71, 86, 75, 85, 84, 83, 91, 66, 88, 73, 87, 72, 92, 75, 98, 72, 105,
            58, 107, 54, 115, 52, 114, 55, 112, 56, 129, 51, 132, 40, 150, 33, 140, 29, 98, 35, 77,
            42,
        ],
    ],
    [
        [
            42, 121, 96, 66, 108, 43, 111, 40, 117, 44, 123, 32, 120, 36, 119, 33, 127, 33, 134,
            34, 139, 21, 147, 23, 152, 20, 158, 25, 154, 26, 166, 21, 173, 16, 184, 13, 184, 10,
            150, 13, 139, 15,
        ],
        [
            22, 178, 63, 114, 74, 82, 84, 83, 92, 82, 103, 62, 96, 72, 96, 67, 101, 73, 107, 72,
            113, 55, 118, 52, 125, 52, 118, 52, 117, 55, 135, 49, 137, 39, 157, 32, 145, 29, 97,
            33, 77, 40,
        ],
    ],
];

/// Decode coarse per-band energies.
///
/// Params: mode/band range, mutable `old_ebands`, `intra` flag, decoder state,
/// channel count, LM and total frame bits.
/// Returns: nothing; `old_ebands` updated in-place.
pub(crate) fn unquant_coarse_energy(
    mode: &CeltMode,
    start: usize,
    end: usize,
    old_ebands: &mut [f32],
    intra: bool,
    dec: &mut EcDec<'_>,
    channels: usize,
    lm: usize,
    total_bits: i32,
) {
    let target_idx = None;
    let coarse_call_idx = TRACE_COARSE_CALL_IDX.fetch_add(1, Ordering::SeqCst);
    let trace_this_call = target_idx == Some(coarse_call_idx);
    if trace_this_call {
        debug_trace!(
            "R coarse_call_idx={} start tell_frac={}",
            coarse_call_idx,
            dec.tell_frac()
        );
    }
    let prob_model = &E_PROB_MODEL[lm][usize::from(intra)];
    let mut prev = [0.0f32; 2];
    let (coef, beta) = if intra {
        (0.0, BETA_INTRA)
    } else {
        (PRED_COEF[lm], BETA_COEF[lm])
    };
    let budget = (dec.tell() + (mode.window.len() as i32 * 0)).max(0); // keep branchless placeholder
    let _ = budget;

    for i in start..end {
        for c in 0..channels {
            let tell = dec.tell();
            let tell_frac_before = dec.tell_frac();
            let qi = if total_bits - tell >= 15 {
                let pi = 2 * i.min(20);
                ec_laplace_decode(
                    dec,
                    (prob_model[pi] as u32) << 7,
                    (prob_model[pi + 1] as u32) << 6,
                )
            } else if total_bits - tell >= 2 {
                let q = dec.dec_icdf(&SMALL_ENERGY_ICDF, 2);
                (q >> 1) ^ -((q & 1) as i32)
            } else if total_bits - tell >= 1 {
                -i32::from(dec.dec_bit_logp(1))
            } else {
                -1
            };
            if trace_this_call {
                debug_trace!(
                    "R pkt{} coarse band={} c={} qi={} tell_frac={}",
                    coarse_call_idx,
                    i,
                    c,
                    qi,
                    dec.tell_frac()
                );
            }
            let q = qi as f32;
            let idx = i + c * mode.nb_ebands;
            old_ebands[idx] = old_ebands[idx].max(-9.0);
            let tmp = coef * old_ebands[idx] + prev[c] + q;
            if target_idx == Some(0) && coarse_call_idx == 0 && i == 0 && c == 0 {
                debug_trace!(
                    "[RUST] pkt0 band0: qi={} q={:.6} coef={:.6} old_before={:.6} prev={:.6} tmp={:.6}",
                    qi,
                    q,
                    coef,
                    old_ebands[idx],
                    prev[c],
                    tmp
                );
            }
            old_ebands[idx] = tmp.clamp(-28.0, 28.0);
            prev[c] = prev[c] + q - beta * q;
        }
    }
}

/// Decode fine per-band energies.
///
/// Params: mode/band range, mutable `old_ebands`, per-band extra quant bits,
/// decoder and channel count.
/// Returns: nothing; `old_ebands` updated in-place.
pub(crate) fn unquant_fine_energy(
    mode: &CeltMode,
    start: usize,
    end: usize,
    old_ebands: &mut [f32],
    extra_quant: &[i32],
    dec: &mut EcDec<'_>,
    channels: usize,
    packet_idx: usize,
) {
    let target_idx = None;
    let fine_call_idx = TRACE_FINE_CALL_IDX.fetch_add(1, Ordering::SeqCst);
    for i in start..end {
        let extra = extra_quant[i];
        if extra <= 0 {
            continue;
        }
        let tell_before_band = dec.tell_frac();
        let guard_ok = dec.tell() + (channels as i32) * extra <= (dec.storage() as i32) * 8;
        for c in 0..channels {
            let nbits_before = dec.nbits_total();
            let tell_before = dec.tell();
            let q2 = dec.dec_bits(extra as u32) as f32;
            let offset =
                (q2 + 0.5) * ((1u32 << (14 - extra as u32)) as f32) * (1.0 / 16384.0) - 0.5;
            old_ebands[i + c * mode.nb_ebands] += offset;
        }
        if target_idx == Some(fine_call_idx) {
            debug_trace!(
                "R pkt{} fine band={} extra={} tell:{}->{}",
                fine_call_idx,
                i,
                extra,
                tell_before_band,
                dec.tell_frac()
            );
        }
    }
}

/// Spend remaining bits on final 1-bit fine energy refinement.
///
/// Params: mode/band range, optional mutable energies, fine quant/priority
/// arrays, remaining whole bits, decoder and channel count.
/// Returns: nothing.
pub(crate) fn unquant_energy_finalise(
    mode: &CeltMode,
    start: usize,
    end: usize,
    old_ebands: Option<&mut [f32]>,
    fine_quant: &[i32],
    fine_priority: &[i32],
    bits_left: i32,
    dec: &mut EcDec<'_>,
    channels: usize,
) {
    let target_idx = None;
    let finalise_call_idx = TRACE_FINALISE_CALL_IDX.fetch_add(1, Ordering::SeqCst);
    let mut bits_left_mut = bits_left;
    let mut old_opt = old_ebands;
    for prio in 0..2 {
        for i in start..end {
            if bits_left_mut < channels as i32 {
                return;
            }
            if fine_quant[i] >= MAX_FINE_BITS || fine_priority[i] != prio {
                continue;
            }
            for c in 0..channels {
                if target_idx == Some(0) && finalise_call_idx == 0 && i == 0 && c == 0 {
                    debug_trace!(
                        "[RUST] finalise band0 pre-read: tell={} end_offs={} nb_end_bits={}",
                        dec.tell(),
                        dec.end_offs(),
                        dec.nend_bits()
                    );
                }
                let q2 = dec.dec_bits(1) as f32;
                let offset = (q2 - 0.5)
                    * ((1u32 << (14 - fine_quant[i] as u32 - 1)) as f32)
                    * (1.0 / 16384.0);
                if target_idx == Some(0) && finalise_call_idx == 0 && i == 0 && c == 0 {
                    debug_trace!(
                        "[RUST] finalise band0: fine_quant={} fine_priority={} q2={} offset={:.6}",
                        fine_quant[0],
                        fine_priority[0],
                        q2 as i32,
                        offset
                    );
                }
                if let Some(ref mut old) = old_opt {
                    old[i + c * mode.nb_ebands] += offset;
                }
                bits_left_mut -= 1;
            }
        }
    }
}

/// Return CELT eMeans table used by energy logic.
///
/// Params: none.
/// Returns: static per-band mean energy table.
pub(crate) fn e_means() -> &'static [f32; 25] {
    &E_MEANS
}
