/// Compute RMS of an f32 slice.
pub fn compute_rms(data: &[f32]) -> f32 {
    if data.is_empty() {
        return 0.0;
    }
    let sum: f32 = data.iter().map(|s| s * s).sum();
    (sum / data.len() as f32).sqrt()
}

/// Soft clip — smooth polynomial knee to avoid harsh hard-clipping artifacts.
/// Identity below 0.95, smooth Hermite compression to ±1.0 at ±2.0.
pub fn soft_clip(sample: f32) -> f32 {
    let a = sample.abs();
    if a <= 0.95 {
        sample
    } else if a <= 2.0 {
        let sign = sample.signum();
        let t = (a - 0.95) / 1.05; // 0..1 over [0.95, 2.0]
                                   // Hermite smoothstep: C1 continuous, f(0)=0, f(1)=1, f'(0)=f'(1)=0
        let s = t * t * (3.0 - 2.0 * t);
        sign * (0.95 + 0.05 * s)
    } else {
        sample.signum()
    }
}
