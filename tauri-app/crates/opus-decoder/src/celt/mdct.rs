#![allow(dead_code, unused_variables, unused_assignments)]

//! CELT inverse MDCT primitives.
//!
//! Current scope is a decoder-oriented path for 48 kHz / 20 ms (`LM=3`), with
//! explicit overlap/window processing kept close to libopus flow.

use crate::celt::kiss_fft::{KissFft, flat_fft_forward};

/// Backward MDCT helper for CELT decode path.
#[derive(Debug, Clone)]
pub(crate) struct MdctBackward {
    n: usize,
    overlap: usize,
    fft: KissFft,
    trig: Vec<f32>,
}

impl MdctBackward {
    /// Create backward MDCT state for fixed frame size.
    ///
    /// Params: `n` is MDCT size (e.g. 960), `overlap` is overlap length
    /// (e.g. 120).
    /// Returns: initialized state with runtime trig tables.
    pub fn new(n: usize, overlap: usize) -> Self {
        let n2 = n / 2;
        let mut trig = Vec::with_capacity(n2);
        for j in 0..n2 {
            let phase = 2.0_f64 * core::f64::consts::PI * (j as f64 + 0.125_f64) / n as f64;
            trig.push(phase.cos() as f32);
        }
        Self {
            n,
            overlap,
            fft: KissFft::new(n / 4),
            trig,
        }
    }

    /// Return configured MDCT size.
    ///
    /// Params: none.
    /// Returns: MDCT size `N` for this instance.
    pub fn n(&self) -> usize {
        self.n
    }

    /// Return internal FFT size used by MDCT.
    ///
    /// Params: none.
    /// Returns: complex FFT size `N/4`.
    pub fn fft_len(&self) -> usize {
        self.fft.len()
    }

    /// Run `clt_mdct_backward`-style transform.
    ///
    /// Params: `input` contains `N/2` MDCT bins, `window` contains `overlap`
    /// samples, `out` has length at least `N/2 + overlap`.
    /// Returns: `Ok(())` on success, otherwise a static validation error.
    pub fn backward(
        &self,
        input: &[f32],
        window: &[f32],
        out: &mut [f32],
    ) -> Result<(), &'static str> {
        let n = self.n;
        let n2 = n >> 1;
        let n4 = n >> 2;
        let overlap = self.overlap;
        let required = n2 + overlap;
        if input.len() != n2 || out.len() < required {
            return Err("mdct backward length mismatch");
        }
        if window.len() != overlap {
            return Err("mdct backward window length mismatch");
        }

        // #region agent log
        let lm0_trace = false;
        if lm0_trace {
            let old_sum: f32 = out[..overlap.min(out.len())].iter().map(|x| x.abs()).sum();
            let first8: String = (0..8.min(out.len()))
                .map(|i| format!("{:.6}", out[i]))
                .collect::<Vec<_>>()
                .join(",");
            let line = format!(
                r#"{{"sessionId":"bea564","runId":"lm0_imdct","hypothesisId":"H2","location":"mdct.rs:backward_entry","message":"lm0_old_overlap","data":{{"n":{},"n2":{},"overlap":{},"old_sum":{},"old_first8":[{}]}},"timestamp":{}}}"#,
                n,
                n2,
                overlap,
                old_sum,
                first8,
                std::time::SystemTime::now()
                    .duration_since(std::time::UNIX_EPOCH)
                    .unwrap_or_default()
                    .as_millis()
            );
            let log_path = std::env::current_dir()
                .unwrap_or_else(|_| std::path::PathBuf::from("."))
                .join(".cursor")
                .join("debug-bea564.log");
            let _ = std::fs::OpenOptions::new()
                .create(true)
                .append(true)
                .open(&log_path)
                .and_then(|mut f| std::io::Write::write_all(&mut f, (line + "\n").as_bytes()));
        }
        // #endregion

        let trace_fft = false;
        if trace_fft {
            debug_trace!(
                "mdct input[0]={:.6} input[{}]={:.6} trig[0]={:.6} trig[{}]={:.6}",
                input[0],
                n2 - 1,
                input[n2 - 1],
                self.trig[0],
                n4,
                self.trig[n4]
            );
            debug_trace!("backward: self.n={} n4={}", self.n, n4);
            if self.n == 1920 && self.trig.len() >= 960 {
                debug_trace!(
                    "trig[0]={:.8} trig[1]={:.8} trig[479]={:.8}",
                    self.trig[0],
                    self.trig[1],
                    self.trig[479]
                );
                debug_trace!(
                    "trig[480]={:.8} trig[481]={:.8} trig[959]={:.8}",
                    self.trig[480],
                    self.trig[481],
                    self.trig[959]
                );
                debug_trace!(
                    "TRIG CHECK: [1]={:.8} [239]={:.8} [240]={:.8} [479]={:.8}",
                    self.trig[1],
                    self.trig[239],
                    self.trig[240],
                    self.trig[479]
                );
            }
        }
        // Flat interleaved buffer: [re0, im0, re1, im1, ...]
        let mut f2 = vec![0.0f32; n2];
        let t = &self.trig;
        let impulse_mode =
            input.first().copied() == Some(1.0) && input.iter().skip(1).all(|&v| v == 0.0);
        for i in 0..n4 {
            let xp1 = input[2 * i];
            let xp2 = input[n2 - 1 - 2 * i];
            // Match libopus clt_mdct_backward pre-rotation for float path.
            let yr = xp2 * t[i] + xp1 * t[n4 + i];
            let yi = xp1 * t[i] - xp2 * t[n4 + i];
            // Swap slots like libopus FFT-vs-IFFT trick.
            f2[2 * i] = yi;
            f2[2 * i + 1] = yr;
        }
        if impulse_mode && f2.len() >= 8 {
            debug_trace!(
                "IMPULSE f2[0..8]: {:.6} {:.6} {:.6} {:.6} {:.6} {:.6} {:.6} {:.6}",
                f2[0],
                f2[1],
                f2[2],
                f2[3],
                f2[4],
                f2[5],
                f2[6],
                f2[7]
            );
            if self.n == 1920 {
                debug_trace!(
                    "R PRE-FFT[0..8]: {:.6} {:.6} {:.6} {:.6} {:.6} {:.6} {:.6} {:.6}",
                    f2[0],
                    f2[1],
                    f2[2],
                    f2[3],
                    f2[4],
                    f2[5],
                    f2[6],
                    f2[7]
                );
            }
        }
        if trace_fft && self.n == 1920 && f2.len() >= 4 {
            debug_trace!(
                "f2[0..4]: {:.6} {:.6} {:.6} {:.6}",
                f2[0],
                f2[1],
                f2[2],
                f2[3]
            );
        }
        if n == 1920 && n4 >= 2 {
            let mut sum_re = 0.0f32;
            let mut sum_im = 0.0f32;
            for i in 0..n4 {
                sum_re += f2[2 * i];
                sum_im += f2[2 * i + 1];
            }
            debug_trace!(
                "MDCT1920 fft_in[0..2]: ({:.6},{:.6}) ({:.6},{:.6})",
                f2[0],
                f2[1],
                f2[2],
                f2[3]
            );
            debug_trace!("MDCT1920 fft_in sum: ({:.6},{:.6})", sum_re, sum_im);
        }

        let mut f2_out = vec![0.0f32; n2];
        flat_fft_forward(&f2, &mut f2_out, n4);
        if impulse_mode && f2_out.len() >= 8 {
            debug_trace!(
                "IMPULSE f2_out[0..8]: {:.6} {:.6} {:.6} {:.6} {:.6} {:.6} {:.6} {:.6}",
                f2_out[0],
                f2_out[1],
                f2_out[2],
                f2_out[3],
                f2_out[4],
                f2_out[5],
                f2_out[6],
                f2_out[7]
            );
        }
        if trace_fft && self.n == 1920 && f2_out.len() >= 4 {
            debug_trace!(
                "f2_out[0..4]: {:.6} {:.6} {:.6} {:.6}",
                f2_out[0],
                f2_out[1],
                f2_out[2],
                f2_out[3]
            );
            debug_trace!("f2_out[0..2]: {:.6} {:.6}", f2_out[0], f2_out[1]);
            debug_trace!("f2_out[958..960]: {:.6} {:.6}", f2_out[958], f2_out[959]);
            debug_trace!(
                "post i=0: re={:.6} im={:.6} t0={:.6} t480={:.6}",
                f2_out[0],
                f2_out[959],
                self.trig[0],
                self.trig[480]
            );
            let sum_re: f64 = (0..n4).map(|j| f2[2 * j] as f64).sum();
            let sum_im: f64 = (0..n4).map(|j| f2[2 * j + 1] as f64).sum();
            debug_trace!(
                "VERIFY FFT: input_sum=({:.6},{:.6}) output[0]=({:.6},{:.6})",
                sum_re,
                sum_im,
                f2_out[0],
                f2_out[1]
            );
            debug_trace!(
                "f2_out[last2]: ({:.6},{:.6})",
                f2_out[n2 - 2],
                f2_out[n2 - 1]
            );
        }
        if n == 1920 && n4 >= 2 {
            debug_trace!(
                "MDCT1920 fft_out[0..2]: ({:.6},{:.6}) ({:.6},{:.6})",
                f2_out[0],
                f2_out[1],
                f2_out[2],
                f2_out[3]
            );
        }

        let ov2 = overlap >> 1;
        out[ov2..ov2 + n2].copy_from_slice(&f2_out[..n2]);
        // #region agent log
        if lm0_trace {
            let after_sum: f32 = out[ov2..ov2 + n2.min(out.len().saturating_sub(ov2))]
                .iter()
                .map(|x| x.abs())
                .sum();
            let after_first8: String = (ov2..(ov2 + 8).min(out.len()))
                .map(|i| format!("{:.6}", out[i]))
                .collect::<Vec<_>>()
                .join(",");
            let line = format!(
                r#"{{"sessionId":"bea564","runId":"lm0_imdct","hypothesisId":"H3","location":"mdct.rs:after_copy","message":"after_copy_from_slice","data":{{"ov2":{},"after_sum":{},"out_60_68":[{}]}},"timestamp":{}}}"#,
                ov2,
                after_sum,
                after_first8,
                std::time::SystemTime::now()
                    .duration_since(std::time::UNIX_EPOCH)
                    .unwrap_or_default()
                    .as_millis()
            );
            let log_path = std::env::current_dir()
                .unwrap_or_else(|_| std::path::PathBuf::from("."))
                .join(".cursor")
                .join("debug-bea564.log");
            let _ = std::fs::OpenOptions::new()
                .create(true)
                .append(true)
                .open(&log_path)
                .and_then(|mut f| std::io::Write::write_all(&mut f, (line + "\n").as_bytes()));
        }
        // #endregion
        let mut p0 = ov2;
        let mut p1 = ov2 + n2 - 2;
        for i in 0..((n4 + 1) >> 1) {
            if trace_fft && self.n == 1920 && i == 0 {
                debug_trace!("[RUST] POST i=0: p0={} p1={}", p0, p1);
                debug_trace!(
                    "[RUST]   read: out[{}]={:.6} out[{}]={:.6}",
                    p0,
                    out[p0],
                    p0 + 1,
                    out[p0 + 1]
                );
                debug_trace!(
                    "[RUST]   read: out[{}]={:.6} out[{}]={:.6}",
                    p1,
                    out[p1],
                    p1 + 1,
                    out[p1 + 1]
                );
                debug_trace!(
                    "[RUST]   trig: t[0]={:.8} t[480]={:.8} t[479]={:.8} t[959]={:.8}",
                    self.trig[0],
                    self.trig[480],
                    self.trig[479],
                    self.trig[959]
                );
            }
            // We swap real/imag in reads because we use FFT instead of IFFT.
            let re = out[p0 + 1];
            let im = out[p0];
            let t0 = t[i];
            let t1 = t[n4 + i];
            let yr = re * t0 + im * t1;
            let yi = re * t1 - im * t0;

            // Read yp1 from the original buffer state, like libopus.
            let re2 = out[p1 + 1];
            let im2 = out[p1];
            out[p0] = yr;
            out[p1 + 1] = yi;

            let t2 = t[n4 - i - 1];
            let t3 = t[n2 - i - 1];
            let yr2 = re2 * t2 + im2 * t3;
            let yi2 = re2 * t3 - im2 * t2;
            out[p1] = yr2;
            out[p0 + 1] = yi2;
            if trace_fft && self.n == 1920 && i == 0 {
                debug_trace!(
                    "[RUST]   write: out[60]={:.6} out[1019]={:.6} out[1018]={:.6} out[61]={:.6}",
                    out[60],
                    out[1019],
                    out[1018],
                    out[61]
                );
            }

            p0 += 2;
            p1 -= 2;
        }
        if trace_fft && self.n == 1920 && out.len() >= 64 {
            debug_trace!(
                "post-rot out[60..64]: {:.6} {:.6} {:.6} {:.6}",
                out[60],
                out[61],
                out[62],
                out[63]
            );
        }
        // #region agent log
        if lm0_trace {
            let post_rot_left: f32 = out[..ov2.min(out.len())].iter().map(|x| x.abs()).sum();
            let post_rot_right: f32 = out[ov2..overlap.min(out.len())]
                .iter()
                .map(|x| x.abs())
                .sum();
            let right_first8: String = (ov2..(ov2 + 8).min(out.len()))
                .map(|i| format!("{:.6}", out[i]))
                .collect::<Vec<_>>()
                .join(",");
            let line = format!(
                r#"{{"sessionId":"bea564","runId":"lm0_imdct","hypothesisId":"H7","location":"mdct.rs:after_post_rot","message":"before_window","data":{{"post_rot_left_sum":{},"post_rot_right_sum":{},"right_first8":[{}]}},"timestamp":{}}}"#,
                post_rot_left,
                post_rot_right,
                right_first8,
                std::time::SystemTime::now()
                    .duration_since(std::time::UNIX_EPOCH)
                    .unwrap_or_default()
                    .as_millis()
            );
            let log_path = std::env::current_dir()
                .unwrap_or_else(|_| std::path::PathBuf::from("."))
                .join(".cursor")
                .join("debug-bea564.log");
            let _ = std::fs::OpenOptions::new()
                .create(true)
                .append(true)
                .open(&log_path)
                .and_then(|mut f| std::io::Write::write_all(&mut f, (line + "\n").as_bytes()));
        }
        // #endregion

        for i in 0..(overlap / 2) {
            let x1 = out[overlap - 1 - i];
            let x2 = out[i];
            out[i] = x2 * window[overlap - 1 - i] - x1 * window[i];
            out[overlap - 1 - i] = x2 * window[i] + x1 * window[overlap - 1 - i];
            // #region agent log
            if lm0_trace && (i == 0 || i == overlap / 2 - 1) {
                let line = format!(
                    r#"{{"sessionId":"bea564","runId":"lm0_imdct","hypothesisId":"H4","location":"mdct.rs:window_loop","message":"window_iter","data":{{"i":{},"x1":{},"x2":{},"out_i":{},"out_119_i":{}}},"timestamp":{}}}"#,
                    i,
                    x1,
                    x2,
                    out[i],
                    out[overlap - 1 - i],
                    std::time::SystemTime::now()
                        .duration_since(std::time::UNIX_EPOCH)
                        .unwrap_or_default()
                        .as_millis()
                );
                let log_path = std::env::current_dir()
                    .unwrap_or_else(|_| std::path::PathBuf::from("."))
                    .join(".cursor")
                    .join("debug-bea564.log");
                let _ = std::fs::OpenOptions::new()
                    .create(true)
                    .append(true)
                    .open(&log_path)
                    .and_then(|mut f| std::io::Write::write_all(&mut f, (line + "\n").as_bytes()));
            }
            // #endregion
        }
        // #region agent log
        if lm0_trace {
            let frame_sum: f32 = out[..overlap.min(out.len())].iter().map(|x| x.abs()).sum();
            let frame_first8: String = (0..8.min(out.len()))
                .map(|i| format!("{:.6}", out[i]))
                .collect::<Vec<_>>()
                .join(",");
            let line = format!(
                r#"{{"sessionId":"bea564","runId":"lm0_imdct","hypothesisId":"H5","location":"mdct.rs:backward_exit","message":"lm0_after_window","data":{{"frame_sum":{},"frame_first8":[{}]}},"timestamp":{}}}"#,
                frame_sum,
                frame_first8,
                std::time::SystemTime::now()
                    .duration_since(std::time::UNIX_EPOCH)
                    .unwrap_or_default()
                    .as_millis()
            );
            let log_path = std::env::current_dir()
                .unwrap_or_else(|_| std::path::PathBuf::from("."))
                .join(".cursor")
                .join("debug-bea564.log");
            let _ = std::fs::OpenOptions::new()
                .create(true)
                .append(true)
                .open(&log_path)
                .and_then(|mut f| std::io::Write::write_all(&mut f, (line + "\n").as_bytes()));
        }
        // #endregion
        Ok(())
    }

    /// Run direct inverse MDCT that returns raw IMDCT output.
    ///
    /// Params: `input` contains `N/2` MDCT bins, `out` has length `N`.
    /// Returns: `Ok(())` on success, otherwise a static validation error.
    pub fn backward_direct(
        &self,
        input: &[f32],
        _window: &[f32],
        out: &mut [f32],
    ) -> Result<(), &'static str> {
        let n = self.n;
        let n2 = n / 2;
        if input.len() != n2 || out.len() != n {
            return Err("mdct backward length mismatch");
        }

        for (i, out_i) in out.iter_mut().enumerate() {
            let mut sum = 0.0f64;
            for (k, &xk) in input.iter().enumerate() {
                let phase = core::f64::consts::PI / (n2 as f64)
                    * (i as f64 + 0.5 + n2 as f64 / 2.0)
                    * (k as f64 + 0.5);
                sum += xk as f64 * phase.cos();
            }
            *out_i = sum as f32;
        }
        if self.n == 1920 {
            let rms = (out.iter().map(|x| x * x).sum::<f32>() / out.len() as f32).sqrt();
            debug_trace!(
                "raw imdct[0..4]: {:.6} {:.6} {:.6} {:.6}",
                out[0],
                out[1],
                out[2],
                out[3]
            );
            debug_trace!(
                "raw imdct[958..962]: {:.6} {:.6} {:.6} {:.6}",
                out[958],
                out[959],
                out[960],
                out[961]
            );
            debug_trace!("MDCT1920 raw rms: {:.6}", rms);
        }

        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::MdctBackward;

    #[test]
    fn mdct_backward_validates_lengths() {
        let mdct = MdctBackward::new(960, 120);
        let input = vec![0.0f32; 480];
        let window = vec![1.0f32; 120];
        let mut out = vec![0.0f32; 960];
        assert!(mdct.backward(&input, &window, &mut out).is_ok());
        assert!(mdct.backward(&input[..479], &window, &mut out).is_err());
        assert!(mdct.backward(&input, &window[..119], &mut out).is_err());
    }
}
