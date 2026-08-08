//! Laplace-distributed symbol decoding used by CELT energy quantization.

use crate::entropy::EcDec;

const LAPLACE_LOG_MINP: u32 = 0;
const LAPLACE_MINP: u32 = 1 << LAPLACE_LOG_MINP;
const LAPLACE_NMIN: u32 = 16;

/// Compute the first decay bucket frequency for Laplace model.
///
/// Params: `fs0` is probability mass of zero symbol, `decay` is model decay.
/// Returns: frequency for first non-zero bucket.
fn laplace_get_freq1(fs0: u32, decay: u32) -> u32 {
    let ft = 32_768u32 - LAPLACE_MINP * (2 * LAPLACE_NMIN) - fs0;
    ((ft as u64 * (16_384u32.saturating_sub(decay)) as u64) >> 15) as u32
}

/// Decode one integer symbol from CELT Laplace-like distribution.
///
/// Params: `dec` is range decoder state, `fs` is zero-probability frequency,
/// `decay` is decay parameter.
/// Returns: decoded signed integer delta.
pub(crate) fn ec_laplace_decode(dec: &mut EcDec<'_>, fs: u32, decay: u32) -> i32 {
    let mut val = 0i32;
    let fm = dec.decode_bin(15);
    let mut fl = 0u32;
    let mut fs_cur = fs;
    if fm >= fs_cur {
        val += 1;
        fl = fs_cur;
        fs_cur = laplace_get_freq1(fs_cur, decay) + LAPLACE_MINP;
        while fs_cur > LAPLACE_MINP && fm >= fl + 2 * fs_cur {
            fs_cur *= 2;
            fl += fs_cur;
            fs_cur = (((fs_cur - 2 * LAPLACE_MINP) as u64 * decay as u64) >> 15) as u32;
            fs_cur += LAPLACE_MINP;
            val += 1;
        }
        if fs_cur <= LAPLACE_MINP {
            let di = ((fm - fl) >> (LAPLACE_LOG_MINP + 1)) as i32;
            val += di;
            fl += (2 * di as u32) * LAPLACE_MINP;
        }
        if fm < fl + fs_cur {
            val = -val;
        } else {
            fl += fs_cur;
        }
    }
    let fh = (fl + fs_cur).min(32_768);
    dec.update(fl, fh, 32_768);
    val
}
