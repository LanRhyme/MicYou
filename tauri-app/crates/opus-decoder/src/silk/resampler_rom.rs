//! SILK resampler ROM tables.

/// FIR interpolation order for upsampling paths.
pub(super) const RESAMPLER_ORDER_FIR_12: usize = 8;

/// High-quality 2x upsampler allpass coefficients, branch 0.
pub(super) const RESAMPLER_UP2_HQ_0: [i16; 3] = [1746, 14_986, -26_453];

/// High-quality 2x upsampler allpass coefficients, branch 1.
pub(super) const RESAMPLER_UP2_HQ_1: [i16; 3] = [6854, 25_769, -9994];

/// Fractional FIR interpolation table for the upsampling resampler.
pub(super) const RESAMPLER_FRAC_FIR_12: [[i16; RESAMPLER_ORDER_FIR_12 / 2]; 12] = [
    [189, -600, 617, 30_567],
    [117, -159, -1070, 29_704],
    [52, 221, -2392, 28_276],
    [-4, 529, -3350, 26_341],
    [-48, 758, -3956, 23_973],
    [-80, 905, -4235, 21_254],
    [-99, 972, -4222, 18_278],
    [-107, 967, -3957, 15_143],
    [-103, 896, -3487, 11_950],
    [-91, 773, -2865, 8798],
    [-71, 611, -2143, 5784],
    [-46, 425, -1375, 2996],
];
