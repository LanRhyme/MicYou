#![allow(dead_code)]

//! Minimal CELT FFT primitives for decoder path.
//!
//! This module intentionally implements only the inverse transform behavior
//! needed by the decoder. It follows the libopus scaling convention where the
//! inverse FFT is unscaled and the forward path applies `1/N`.

use core::f32::consts::PI;

/// Complex number used by CELT FFT/MDCT primitives.
#[derive(Debug, Clone, Copy, PartialEq)]
pub(crate) struct Complex32 {
    /// Real component.
    pub re: f32,
    /// Imaginary component.
    pub im: f32,
}

impl Complex32 {
    /// Create a complex value from real and imaginary parts.
    ///
    /// Params: `re` is the real component, `im` is the imaginary component.
    /// Returns: a new `Complex32`.
    pub const fn new(re: f32, im: f32) -> Self {
        Self { re, im }
    }
}

/// Inverse-only FFT plan for CELT decoder usage.
#[derive(Debug, Clone)]
pub(crate) struct KissFft {
    nfft: usize,
    inv_twiddles: Vec<Complex32>,
}

impl KissFft {
    /// Build an inverse FFT plan with runtime twiddle generation.
    ///
    /// Params: `nfft` is the FFT size.
    /// Returns: a plan containing precomputed inverse twiddles.
    pub fn new(nfft: usize) -> Self {
        let mut inv_twiddles = Vec::with_capacity(nfft);
        for k in 0..nfft {
            let phase = 2.0 * PI * (k as f32) / (nfft as f32);
            inv_twiddles.push(Complex32::new(phase.cos(), phase.sin()));
        }
        Self { nfft, inv_twiddles }
    }

    /// Return FFT size configured in this plan.
    ///
    /// Params: none.
    /// Returns: number of points in the transform.
    pub fn len(&self) -> usize {
        self.nfft
    }

    /// Compute unscaled inverse FFT.
    ///
    /// Params: `input` is frequency-domain complex spectrum, `output` is
    /// destination time-domain buffer and must have length `self.len()`.
    /// Returns: `Ok(())` on success, otherwise a static validation error.
    pub fn ifft(&self, input: &[Complex32], output: &mut [Complex32]) -> Result<(), &'static str> {
        if input.len() != self.nfft || output.len() != self.nfft {
            return Err("kiss_fft length mismatch");
        }
        for out in output.iter_mut() {
            *out = Complex32::new(0.0, 0.0);
        }
        for (n, out) in output.iter_mut().enumerate() {
            let mut acc_re = 0.0f32;
            let mut acc_im = 0.0f32;
            for (k, xk) in input.iter().enumerate() {
                let tw = self.inv_twiddles[(k * n) % self.nfft];
                acc_re += xk.re * tw.re - xk.im * tw.im;
                acc_im += xk.re * tw.im + xk.im * tw.re;
            }
            *out = Complex32::new(acc_re, acc_im);
        }
        Ok(())
    }

    /// Compute unscaled forward FFT.
    ///
    /// Params: `input` is time-domain complex vector, `output` is destination
    /// frequency-domain buffer and must have length `self.len()`.
    /// Returns: `Ok(())` on success, otherwise a static validation error.
    pub fn fft(&self, input: &[Complex32], output: &mut [Complex32]) -> Result<(), &'static str> {
        if input.len() != self.nfft || output.len() != self.nfft {
            return Err("kiss_fft length mismatch");
        }
        for out in output.iter_mut() {
            *out = Complex32::new(0.0, 0.0);
        }
        for (n, out) in output.iter_mut().enumerate() {
            let mut acc_re = 0.0f32;
            let mut acc_im = 0.0f32;
            for (k, xk) in input.iter().enumerate() {
                let tw = self.inv_twiddles[(k * n) % self.nfft];
                // Conjugate inverse twiddle gives forward phase.
                acc_re += xk.re * tw.re + xk.im * tw.im;
                acc_im += -xk.re * tw.im + xk.im * tw.re;
            }
            *out = Complex32::new(acc_re, acc_im);
        }
        Ok(())
    }
}

/// Compute forward DFT on flat interleaved complex buffers.
///
/// Params: `input`/`output` are `[re0, im0, re1, im1, ...]` and `n` is the
/// number of complex samples.
/// Returns: nothing; writes the forward transform to `output`.
pub(crate) fn flat_fft_forward(input: &[f32], output: &mut [f32], n: usize) {
    assert!(input.len() >= 2 * n && output.len() >= 2 * n);
    for k in 0..n {
        let mut sum_re = 0.0f64;
        let mut sum_im = 0.0f64;
        for j in 0..n {
            let angle = -2.0 * PI as f64 * (k as f64) * (j as f64) / (n as f64);
            let (sin_a, cos_a) = angle.sin_cos();
            let re = input[2 * j] as f64;
            let im = input[2 * j + 1] as f64;
            sum_re += re * cos_a - im * sin_a;
            sum_im += re * sin_a + im * cos_a;
        }
        output[2 * k] = sum_re as f32;
        output[2 * k + 1] = sum_im as f32;
    }
}

#[cfg(test)]
mod tests {
    use super::{Complex32, KissFft};
    use core::f32::consts::PI;

    /// Compute libopus-style forward DFT with `1/N` scaling for tests.
    ///
    /// Params: `input` is time-domain complex vector.
    /// Returns: scaled frequency-domain vector.
    fn forward_scaled(input: &[Complex32]) -> Vec<Complex32> {
        let n = input.len();
        let mut out = vec![Complex32::new(0.0, 0.0); n];
        for (k, yk) in out.iter_mut().enumerate() {
            let mut acc_re = 0.0f32;
            let mut acc_im = 0.0f32;
            for (n_idx, xn) in input.iter().enumerate() {
                let phase = -2.0 * PI * (k as f32) * (n_idx as f32) / (n as f32);
                let c = phase.cos();
                let s = phase.sin();
                acc_re += xn.re * c - xn.im * s;
                acc_im += xn.re * s + xn.im * c;
            }
            *yk = Complex32::new(acc_re / (n as f32), acc_im / (n as f32));
        }
        out
    }

    #[test]
    fn ifft_roundtrip_matches_input() {
        let n = 60usize;
        let fft = KissFft::new(n);
        let mut input = Vec::with_capacity(n);
        for i in 0..n {
            let t = i as f32 / n as f32;
            input.push(Complex32::new(
                (2.0 * PI * 3.0 * t).sin(),
                (2.0 * PI * 5.0 * t).cos(),
            ));
        }
        let freq = forward_scaled(&input);
        let mut recon = vec![Complex32::new(0.0, 0.0); n];
        fft.ifft(&freq, &mut recon).expect("ifft must succeed");
        for (a, b) in input.iter().zip(recon.iter()) {
            assert!(
                (a.re - b.re).abs() < 2e-5,
                "re mismatch: {} vs {}",
                a.re,
                b.re
            );
            assert!(
                (a.im - b.im).abs() < 2e-5,
                "im mismatch: {} vs {}",
                a.im,
                b.im
            );
        }
    }
}
