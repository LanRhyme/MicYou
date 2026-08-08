//! CELT PVQ vector decode helpers (`vq.c`) for decoder path.
#![allow(clippy::too_many_arguments)]

use crate::celt::cwrs;
use crate::entropy::EcDec;

/// CELT spreading mode: none.
pub(crate) const SPREAD_NONE: i32 = 0;

/// PVQ band decode diagnostic information.
#[derive(Debug, Clone, Copy)]
pub(crate) struct PvqDecodeInfo {
    pub collapse_mask: u8,
    pub nc: u32,
    pub index: u32,
}

/// Run one exponential rotation stage.
///
/// Params: mutable band vector, stage stride and sin/cos factors.
/// Returns: nothing.
fn exp_rotation1(x: &mut [f32], stride: usize, c: f32, s: f32) {
    let len = x.len();
    if len <= stride {
        return;
    }
    for i in 0..(len - stride) {
        let x1 = x[i];
        let x2 = x[i + stride];
        x[i + stride] = c * x2 + s * x1;
        x[i] = c * x1 - s * x2;
    }
    if len <= 2 * stride {
        return;
    }
    for i in (0..=(len - 2 * stride - 1)).rev() {
        let x1 = x[i];
        let x2 = x[i + stride];
        x[i + stride] = c * x2 + s * x1;
        x[i] = c * x1 - s * x2;
    }
}

/// Apply CELT spreading rotation.
///
/// Params: mutable band vector, direction, stride, pulses and spread mode.
/// Returns: nothing.
fn exp_rotation(x: &mut [f32], dir: i32, stride: usize, k: i32, spread: i32) {
    const SPREAD_FACTOR: [i32; 3] = [15, 10, 5];
    if 2 * k >= x.len() as i32 || spread == SPREAD_NONE {
        return;
    }
    let factor = SPREAD_FACTOR[(spread - 1) as usize];
    let gain = x.len() as f32 / (x.len() as f32 + factor as f32 * k as f32);
    let theta = 0.5 * gain * gain;
    let angle = core::f32::consts::FRAC_PI_2 * theta;
    let c = angle.cos();
    let s = angle.sin();
    let mut stride2 = 0usize;
    if x.len() >= 8 * stride {
        stride2 = 1;
        while (stride2 * stride2 + stride2) * stride + (stride >> 2) < x.len() {
            stride2 += 1;
        }
    }
    let len = x.len() / stride;
    for i in 0..stride {
        let xs = &mut x[i * len..(i + 1) * len];
        if dir < 0 {
            if stride2 > 0 {
                exp_rotation1(xs, stride2, s, c);
            }
            exp_rotation1(xs, 1, c, s);
        } else {
            exp_rotation1(xs, 1, c, -s);
            if stride2 > 0 {
                exp_rotation1(xs, stride2, s, -c);
            }
        }
    }
}

/// Normalize decoded integer pulses into unit-energy vector.
///
/// Params: signed pulses, output vector and synthesis gain.
/// Returns: sum of squared pulses.
fn normalise_residual(iy: &[i32], x: &mut [f32], gain: f32) -> u32 {
    let ryy = iy
        .iter()
        .fold(0u32, |acc, &v| acc.wrapping_add((v * v) as u32));
    let scale = if ryy == 0 {
        0.0
    } else {
        gain / (ryy as f32).sqrt()
    };
    for (dst, &src) in x.iter_mut().zip(iy.iter()) {
        *dst = src as f32 * scale;
    }
    ryy
}

/// Build collapse mask for transient anti-collapse handling.
///
/// Params: decoded pulse vector, band length and block count.
/// Returns: bit mask with one bit per short block.
fn extract_collapse_mask(iy: &[i32], n: usize, b: usize) -> u8 {
    if b <= 1 {
        return 1;
    }
    // CELT 短块最多 8 个；对异常帧解析出 b > 8 时，若直接 `mask |= 1 << i` 会触发
    // u8 左移溢出（debug 下 panic）。这里钳制到 8 以保证安全并产出合理 mask。
    let b = b.min(8);
    let n0 = n / b;
    let mut mask = 0u8;
    for i in 0..b {
        let mut nonzero = 0i32;
        for j in 0..n0 {
            nonzero |= iy[i * n0 + j];
        }
        if nonzero != 0 {
            mask |= 1 << i;
        }
    }
    mask
}

/// Decode one PVQ normalized band.
///
/// Params: output vector, pulses `k`, spread mode, blocks `b`, decoder and gain.
/// Returns: collapse mask and CWRS decode metadata for this band.
pub(crate) fn alg_unquant(
    x: &mut [f32],
    k: i32,
    spread: i32,
    b: usize,
    band: usize,
    trace_packet: bool,
    dec: &mut EcDec<'_>,
    gain: f32,
) -> PvqDecodeInfo {
    let decoded = cwrs::decode_pulses(dec, x.len(), k as usize);
    normalise_residual(&decoded.pulses, x, gain);
    if trace_packet && band == 6 && x.len() >= 4 {
        debug_trace!(
            "band6 pre_rot:  {:.6} {:.6} {:.6} {:.6}",
            x[0],
            x[1],
            x[2],
            x[3]
        );
    }
    exp_rotation(x, -1, b.max(1), k, spread);
    if trace_packet && band == 6 && x.len() >= 4 {
        debug_trace!(
            "band6 post_rot: {:.6} {:.6} {:.6} {:.6}",
            x[0],
            x[1],
            x[2],
            x[3]
        );
    }
    PvqDecodeInfo {
        collapse_mask: extract_collapse_mask(&decoded.pulses, x.len(), b.max(1)),
        nc: decoded.nc,
        index: decoded.index,
    }
}

/// Renormalize vector to desired gain.
///
/// Params: mutable vector and target gain.
/// Returns: nothing.
pub(crate) fn renormalise_vector(x: &mut [f32], gain: f32) {
    let e = x.iter().map(|v| v * v).sum::<f32>().max(1e-12);
    let g = gain / e.sqrt();
    for v in x.iter_mut() {
        *v *= g;
    }
}
