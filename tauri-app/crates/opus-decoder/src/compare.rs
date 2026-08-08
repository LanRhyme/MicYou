//! Conformance comparison helper.
//!
//! This ports libopus' `src/opus_compare.c` quality metric, used by the
//! reference `tests/run_vectors.sh` script when validating RFC test vectors.
//!
//! NOTE: This is intentionally *not* a bit-exact PCM comparator. Minor output
//! differences can happen between builds/platforms (especially in CELT), while
//! still being considered acceptable by the reference test harness.

#![allow(
    dead_code,
    clippy::too_many_arguments,
    clippy::needless_range_loop,
    clippy::identity_op,
    clippy::erasing_op
)]

use core::f32;

const NBANDS: usize = 21;
const NFREQS: usize = 240;

// Bark-derived CELT bands used by opus_compare.c.
const BANDS: [usize; NBANDS + 1] = [
    0, 2, 4, 6, 8, 10, 12, 14, 16, 20, 24, 28, 32, 40, 48, 56, 68, 80, 96, 120, 156, 200,
];

const TEST_WIN_SIZE: usize = 480;
const TEST_WIN_STEP: usize = 120;

#[derive(Debug, Clone, Copy)]
pub struct QualityResult {
    pub quality_percent: f32,
    pub internal_weighted_error: f64,
}

impl QualityResult {
    pub fn passes_vectors(&self) -> bool {
        self.quality_percent >= 0.0
    }
}

pub fn compare_quality(
    reference_pcm: &[i16],
    candidate_pcm: &[i16],
    reference_channels: usize,
    candidate_channels: usize,
    rate_hz: u32,
) -> Result<QualityResult, String> {
    if reference_channels != 1 && reference_channels != 2 {
        return Err(format!(
            "invalid reference channel count: {reference_channels}"
        ));
    }
    if candidate_channels != 1 && candidate_channels != 2 {
        return Err(format!(
            "invalid candidate channel count: {candidate_channels}"
        ));
    }
    if !matches!(rate_hz, 8000 | 12000 | 16000 | 24000 | 48000) {
        return Err(format!("invalid rate_hz: {rate_hz}"));
    }

    if reference_pcm.len() % reference_channels != 0 {
        return Err("reference pcm is not properly interleaved".to_string());
    }
    if candidate_pcm.len() % candidate_channels != 0 {
        return Err("candidate pcm is not properly interleaved".to_string());
    }

    let downsample = (48_000u32 / rate_hz) as usize;
    if downsample == 0 || 48_000u32 % rate_hz != 0 {
        return Err("rate_hz must divide 48000".to_string());
    }

    let xlength = reference_pcm.len() / reference_channels;
    let ylength = candidate_pcm.len() / candidate_channels;
    if xlength != ylength.saturating_mul(downsample) {
        return Err(format!(
            "sample counts do not match (ref frames {xlength} != cand frames {ylength} * downsample {downsample})"
        ));
    }
    if xlength < TEST_WIN_SIZE {
        return Err(format!(
            "insufficient sample data (ref frames {xlength} < {TEST_WIN_SIZE})"
        ));
    }

    // Convert reference to f32 (mono or stereo per reference_channels).
    let mut x: Vec<f32> = Vec::with_capacity(xlength * candidate_channels);
    if reference_channels == 1 {
        for i in 0..xlength {
            x.push(reference_pcm[i] as f32);
        }
    } else if candidate_channels == 1 {
        for i in 0..xlength {
            let l = reference_pcm[2 * i] as f32;
            let r = reference_pcm[2 * i + 1] as f32;
            x.push(0.5 * (l + r));
        }
    } else {
        for &s in reference_pcm {
            x.push(s as f32);
        }
    }

    let y: Vec<f32> = candidate_pcm.iter().map(|&s| s as f32).collect();

    // Equivalent of: nframes=(xlength-TEST_WIN_SIZE+TEST_WIN_STEP)/TEST_WIN_STEP;
    let nframes = (xlength - TEST_WIN_SIZE + TEST_WIN_STEP) / TEST_WIN_STEP;
    if nframes == 0 {
        return Err("nframes == 0".to_string());
    }

    let (ybands, yfreqs) = match rate_hz {
        48000 => (NBANDS, NFREQS),
        24000 => (19usize, NFREQS / downsample),
        16000 => (17usize, NFREQS / downsample),
        12000 => (15usize, NFREQS / downsample),
        8000 => (13usize, NFREQS / downsample),
        _ => return Err("unreachable rate_hz".to_string()),
    };

    let nch = candidate_channels;

    // xb: per-band masking energy
    let mut xb = vec![0f32; nframes * NBANDS * nch];
    let mut x_ps = vec![0f32; nframes * NFREQS * nch];
    let mut y_ps = vec![0f32; nframes * yfreqs * nch];

    band_energy(
        Some(&mut xb),
        &mut x_ps,
        &x,
        nch,
        nframes,
        TEST_WIN_SIZE,
        TEST_WIN_STEP,
        1,
        NBANDS,
        NFREQS,
    );

    band_energy(
        None,
        &mut y_ps,
        &y,
        nch,
        nframes,
        TEST_WIN_SIZE / downsample,
        TEST_WIN_STEP / downsample,
        downsample,
        ybands,
        yfreqs,
    );

    // Apply masking and smoothing like opus_compare.c.
    for xi in 0..nframes {
        // Frequency masking (low -> high): 10 dB/Bark slope.
        for bi in 1..NBANDS {
            for ci in 0..nch {
                let cur = (xi * NBANDS + bi) * nch + ci;
                let prev = (xi * NBANDS + (bi - 1)) * nch + ci;
                xb[cur] += 0.1 * xb[prev];
            }
        }

        // Frequency masking (high -> low): 15 dB/Bark slope.
        for bi in (0..NBANDS - 1).rev() {
            for ci in 0..nch {
                let cur = (xi * NBANDS + bi) * nch + ci;
                let next = (xi * NBANDS + (bi + 1)) * nch + ci;
                xb[cur] += 0.03 * xb[next];
            }
        }

        // Temporal masking: -3 dB/2.5 ms slope.
        if xi > 0 {
            for bi in 0..NBANDS {
                for ci in 0..nch {
                    let cur = (xi * NBANDS + bi) * nch + ci;
                    let prev = ((xi - 1) * NBANDS + bi) * nch + ci;
                    xb[cur] += 0.5 * xb[prev];
                }
            }
        }

        // Allowing some cross-talk.
        if nch == 2 {
            for bi in 0..NBANDS {
                let l = xb[(xi * NBANDS + bi) * nch + 0];
                let r = xb[(xi * NBANDS + bi) * nch + 1];
                xb[(xi * NBANDS + bi) * nch + 0] += 0.01 * r;
                xb[(xi * NBANDS + bi) * nch + 1] += 0.01 * l;
            }
        }

        // Apply masking to spectral energies.
        for bi in 0..ybands {
            for xj in BANDS[bi]..BANDS[bi + 1] {
                for ci in 0..nch {
                    x_ps[(xi * NFREQS + xj) * nch + ci] += 0.1 * xb[(xi * NBANDS + bi) * nch + ci];
                    y_ps[(xi * yfreqs + xj) * nch + ci] += 0.1 * xb[(xi * NBANDS + bi) * nch + ci];
                }
            }
        }
    }

    // Average consecutive frames (adds previous frame).
    for bi in 0..ybands {
        for xj in BANDS[bi]..BANDS[bi + 1] {
            for ci in 0..nch {
                let mut xtmp = x_ps[(0 * NFREQS + xj) * nch + ci];
                let mut ytmp = y_ps[(0 * yfreqs + xj) * nch + ci];
                for xi in 1..nframes {
                    let xtmp2 = x_ps[(xi * NFREQS + xj) * nch + ci];
                    let ytmp2 = y_ps[(xi * yfreqs + xj) * nch + ci];
                    x_ps[(xi * NFREQS + xj) * nch + ci] += xtmp;
                    y_ps[(xi * yfreqs + xj) * nch + ci] += ytmp;
                    xtmp = xtmp2;
                    ytmp = ytmp2;
                }
            }
        }
    }

    // Max frequency bin to compare (see opus_compare.c).
    let max_compare = if rate_hz == 48_000 {
        BANDS[NBANDS]
    } else if rate_hz == 12_000 {
        BANDS[ybands]
    } else {
        BANDS[ybands].saturating_sub(3)
    };

    // Error integration.
    let mut err: f64 = 0.0;
    for xi in 0..nframes {
        let mut ef: f64 = 0.0;
        for bi in 0..ybands {
            let mut eb: f64 = 0.0;
            for xj in BANDS[bi]..BANDS[bi + 1] {
                if xj >= max_compare {
                    break;
                }
                for ci in 0..nch {
                    let re =
                        y_ps[(xi * yfreqs + xj) * nch + ci] / x_ps[(xi * NFREQS + xj) * nch + ci];
                    let mut im = re - re.ln() - 1.0;
                    if (79..=81).contains(&xj) {
                        im *= 0.1;
                    }
                    if xj == 80 {
                        im *= 0.1;
                    }
                    eb += im as f64;
                }
            }
            eb /= ((BANDS[bi + 1] - BANDS[bi]) * nch) as f64;
            ef += eb * eb;
        }
        ef /= NBANDS as f64;
        ef *= ef;
        err += ef * ef;
    }

    err = (err / nframes as f64).powf(1.0 / 16.0);
    let q = 100.0 * (1.0 - 0.5 * (1.0 + err).ln() / (1.13f64).ln());

    Ok(QualityResult {
        quality_percent: q as f32,
        internal_weighted_error: err,
    })
}

fn band_energy(
    mut out_bands: Option<&mut [f32]>,
    ps: &mut [f32],
    input: &[f32],
    nchannels: usize,
    nframes: usize,
    window_sz: usize,
    step: usize,
    downsample: usize,
    nbands: usize,
    ps_freqs: usize,
) {
    let mut window = vec![0f32; window_sz];
    let mut c = vec![0f32; window_sz];
    let mut s = vec![0f32; window_sz];
    let mut x = vec![0f32; nchannels * window_sz];

    for xj in 0..window_sz {
        window[xj] =
            0.5 - 0.5 * (2.0 * f32::consts::PI / (window_sz as f32 - 1.0) * xj as f32).cos();
    }
    for xj in 0..window_sz {
        c[xj] = (2.0 * f32::consts::PI / window_sz as f32 * xj as f32).cos();
        s[xj] = (2.0 * f32::consts::PI / window_sz as f32 * xj as f32).sin();
    }

    let ps_sz = window_sz / 2;
    debug_assert_eq!(ps_freqs, ps_sz);

    for xi in 0..nframes {
        for ci in 0..nchannels {
            for xk in 0..window_sz {
                x[ci * window_sz + xk] = window[xk] * input[(xi * step + xk) * nchannels + ci];
            }
        }

        let mut xj = 0usize;
        for bi in 0..nbands {
            let mut p0 = 0f32;
            let mut p1 = 0f32;
            while xj < BANDS[bi + 1] {
                for ci in 0..nchannels {
                    let mut ti = 0usize;
                    let mut re = 0f32;
                    let mut im = 0f32;
                    for xk in 0..window_sz {
                        re += c[ti] * x[ci * window_sz + xk];
                        im -= s[ti] * x[ci * window_sz + xk];
                        ti += xj;
                        if ti >= window_sz {
                            ti -= window_sz;
                        }
                    }
                    let ds = downsample as f32;
                    re *= ds;
                    im *= ds;

                    let idx = (xi * ps_sz + xj) * nchannels + ci;
                    ps[idx] = re * re + im * im + 100_000.0;
                    if ci == 0 {
                        p0 += ps[idx];
                    } else {
                        p1 += ps[idx];
                    }
                }
                xj += 1;
            }

            if let Some(out_bands) = out_bands.as_deref_mut() {
                let denom = (BANDS[bi + 1] - BANDS[bi]) as f32;
                out_bands[(xi * nbands + bi) * nchannels + 0] = p0 / denom;
                if nchannels == 2 {
                    out_bands[(xi * nbands + bi) * nchannels + 1] = p1 / denom;
                }
            }
        }
    }
}
