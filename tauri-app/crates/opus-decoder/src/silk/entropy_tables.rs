//! SILK entropy decoding tables used by Phase 3a LBRR payload consumption.

/// Number of SILK rate levels.
pub(super) const N_RATE_LEVELS: usize = 10;
/// Maximum pulse count symbol before LSB extension (`SILK_MAX_PULSES`).
pub(super) const SILK_MAX_PULSES: i32 = 16;
/// Shell frame length in samples.
pub(super) const SHELL_CODEC_FRAME_LENGTH: usize = 16;
/// Maximum NLSF quant amplitude used by entropy decode.
pub(super) const NLSF_QUANT_MAX_AMPLITUDE: i32 = 4;

/// VAD-aware signal type + quant-offset iCDF.
pub(super) const TYPE_OFFSET_VAD_ICDF: [u8; 4] = [232, 158, 10, 0];
/// No-VAD signal type + quant-offset iCDF.
pub(super) const TYPE_OFFSET_NO_VAD_ICDF: [u8; 2] = [230, 0];

/// NLSF interpolation-factor iCDF.
pub(super) const NLSF_INTERP_FACTOR_ICDF: [u8; 5] = [243, 221, 192, 181, 0];
/// NLSF extension iCDF used for tails.
pub(super) const NLSF_EXT_ICDF: [u8; 7] = [100, 40, 16, 7, 3, 1, 0];

/// Uniform entropy tables used by SILK side information decode.
pub(super) const UNIFORM4_ICDF: [u8; 4] = [192, 128, 64, 0];
pub(super) const UNIFORM6_ICDF: [u8; 6] = [213, 171, 128, 85, 43, 0];
pub(super) const UNIFORM8_ICDF: [u8; 8] = [224, 192, 160, 128, 96, 64, 32, 0];

/// LSB-pulse extension iCDF.
pub(super) const LSB_ICDF: [u8; 2] = [120, 0];

/// Gain coding tables.
pub(super) const GAIN_ICDF: [[u8; 8]; 3] = [
    [224, 112, 44, 15, 3, 2, 1, 0],
    [254, 237, 192, 132, 70, 23, 4, 0],
    [255, 252, 226, 155, 61, 11, 2, 0],
];
pub(super) const DELTA_GAIN_ICDF: [u8; 41] = [
    250, 245, 234, 203, 71, 50, 42, 38, 35, 33, 31, 29, 28, 27, 26, 25, 24, 23, 22, 21, 20, 19, 18,
    17, 16, 15, 14, 13, 12, 11, 10, 9, 8, 7, 6, 5, 4, 3, 2, 1, 0,
];

/// Pitch decoding tables.
pub(super) const PITCH_LAG_ICDF: [u8; 32] = [
    253, 250, 244, 233, 212, 182, 150, 131, 120, 110, 98, 85, 72, 60, 49, 40, 32, 25, 19, 15, 13,
    11, 9, 8, 7, 6, 5, 4, 3, 2, 1, 0,
];
pub(super) const PITCH_DELTA_ICDF: [u8; 21] = [
    210, 208, 206, 203, 199, 193, 183, 168, 142, 104, 74, 52, 37, 27, 20, 14, 10, 6, 4, 2, 0,
];
pub(super) const PITCH_CONTOUR_ICDF: [u8; 34] = [
    223, 201, 183, 167, 152, 138, 124, 111, 98, 88, 79, 70, 62, 56, 50, 44, 39, 35, 31, 27, 24, 21,
    18, 16, 14, 12, 10, 8, 6, 4, 3, 2, 1, 0,
];
pub(super) const PITCH_CONTOUR_NB_ICDF: [u8; 11] = [188, 176, 155, 138, 119, 97, 67, 43, 26, 10, 0];
pub(super) const PITCH_CONTOUR_10MS_ICDF: [u8; 12] =
    [165, 119, 80, 61, 47, 35, 27, 20, 14, 9, 4, 0];
pub(super) const PITCH_CONTOUR_10MS_NB_ICDF: [u8; 3] = [113, 63, 0];

/// LTP coding tables.
pub(super) const LTP_PER_INDEX_ICDF: [u8; 3] = [179, 99, 0];
pub(super) const LTP_GAIN_ICDF_0: [u8; 8] = [71, 56, 43, 30, 21, 12, 6, 0];
pub(super) const LTP_GAIN_ICDF_1: [u8; 16] = [
    199, 165, 144, 124, 109, 96, 84, 71, 61, 51, 42, 32, 23, 15, 8, 0,
];
pub(super) const LTP_GAIN_ICDF_2: [u8; 32] = [
    241, 225, 211, 199, 187, 175, 164, 153, 142, 132, 123, 114, 105, 96, 88, 80, 72, 64, 57, 50,
    44, 38, 33, 29, 24, 20, 16, 12, 9, 5, 2, 0,
];
pub(super) const LTPSCALE_ICDF: [u8; 3] = [128, 64, 0];

/// Pulse coding tables.
pub(super) const RATE_LEVELS_ICDF: [[u8; 9]; 2] = [
    [241, 190, 178, 132, 87, 74, 41, 14, 0],
    [223, 193, 157, 140, 106, 57, 39, 18, 0],
];
pub(super) const PULSES_PER_BLOCK_ICDF: [[u8; 18]; 10] = [
    [
        125, 51, 26, 18, 15, 12, 11, 10, 9, 8, 7, 6, 5, 4, 3, 2, 1, 0,
    ],
    [
        198, 105, 45, 22, 15, 12, 11, 10, 9, 8, 7, 6, 5, 4, 3, 2, 1, 0,
    ],
    [
        213, 162, 116, 83, 59, 43, 32, 24, 18, 15, 12, 9, 7, 6, 5, 3, 2, 0,
    ],
    [
        239, 187, 116, 59, 28, 16, 11, 10, 9, 8, 7, 6, 5, 4, 3, 2, 1, 0,
    ],
    [
        250, 229, 188, 135, 86, 51, 30, 19, 13, 10, 8, 6, 5, 4, 3, 2, 1, 0,
    ],
    [
        249, 235, 213, 185, 156, 128, 103, 83, 66, 53, 42, 33, 26, 21, 17, 13, 10, 0,
    ],
    [
        254, 249, 235, 206, 164, 118, 77, 46, 27, 16, 10, 7, 5, 4, 3, 2, 1, 0,
    ],
    [
        255, 253, 249, 239, 220, 191, 156, 119, 85, 57, 37, 23, 15, 10, 6, 4, 2, 0,
    ],
    [
        255, 253, 251, 246, 237, 223, 203, 179, 152, 124, 98, 75, 55, 40, 29, 21, 15, 0,
    ],
    [
        255, 254, 253, 247, 220, 162, 106, 67, 42, 28, 18, 12, 9, 6, 4, 3, 2, 0,
    ],
];
pub(super) const SHELL_CODE_TABLE0: [u8; 152] = [
    128, 0, 214, 42, 0, 235, 128, 21, 0, 244, 184, 72, 11, 0, 248, 214, 128, 42, 7, 0, 248, 225,
    170, 80, 25, 5, 0, 251, 236, 198, 126, 54, 18, 3, 0, 250, 238, 211, 159, 82, 35, 15, 5, 0, 250,
    231, 203, 168, 128, 88, 53, 25, 6, 0, 252, 238, 216, 185, 148, 108, 71, 40, 18, 4, 0, 253, 243,
    225, 199, 166, 128, 90, 57, 31, 13, 3, 0, 254, 246, 233, 212, 183, 147, 109, 73, 44, 23, 10, 2,
    0, 255, 250, 240, 223, 198, 166, 128, 90, 58, 33, 16, 6, 1, 0, 255, 251, 244, 231, 210, 181,
    146, 110, 75, 46, 25, 12, 5, 1, 0, 255, 253, 248, 238, 221, 196, 164, 128, 92, 60, 35, 18, 8,
    3, 1, 0, 255, 253, 249, 242, 229, 208, 180, 146, 110, 76, 48, 27, 14, 7, 3, 1, 0,
];
pub(super) const SHELL_CODE_TABLE1: [u8; 152] = [
    129, 0, 207, 50, 0, 236, 129, 20, 0, 245, 185, 72, 10, 0, 249, 213, 129, 42, 6, 0, 250, 226,
    169, 87, 27, 4, 0, 251, 233, 194, 130, 62, 20, 4, 0, 250, 236, 207, 160, 99, 47, 17, 3, 0, 255,
    240, 217, 182, 131, 81, 41, 11, 1, 0, 255, 254, 233, 201, 159, 107, 61, 20, 2, 1, 0, 255, 249,
    233, 206, 170, 128, 86, 50, 23, 7, 1, 0, 255, 250, 238, 217, 186, 148, 108, 70, 39, 18, 6, 1,
    0, 255, 252, 243, 226, 200, 166, 128, 90, 56, 30, 13, 4, 1, 0, 255, 252, 245, 231, 209, 180,
    146, 110, 76, 47, 25, 11, 4, 1, 0, 255, 253, 248, 237, 219, 194, 163, 128, 93, 62, 37, 19, 8,
    3, 1, 0, 255, 254, 250, 241, 226, 205, 177, 145, 111, 79, 51, 30, 15, 6, 2, 1, 0,
];
pub(super) const SHELL_CODE_TABLE2: [u8; 152] = [
    129, 0, 203, 54, 0, 234, 129, 23, 0, 245, 184, 73, 10, 0, 250, 215, 129, 41, 5, 0, 252, 232,
    173, 86, 24, 3, 0, 253, 240, 200, 129, 56, 15, 2, 0, 253, 244, 217, 164, 94, 38, 10, 1, 0, 253,
    245, 226, 189, 132, 71, 27, 7, 1, 0, 253, 246, 231, 203, 159, 105, 56, 23, 6, 1, 0, 255, 248,
    235, 213, 179, 133, 85, 47, 19, 5, 1, 0, 255, 254, 243, 221, 194, 159, 117, 70, 37, 12, 2, 1,
    0, 255, 254, 248, 234, 208, 171, 128, 85, 48, 22, 8, 2, 1, 0, 255, 254, 250, 240, 220, 189,
    149, 107, 67, 36, 16, 6, 2, 1, 0, 255, 254, 251, 243, 227, 201, 166, 128, 90, 55, 29, 13, 5, 2,
    1, 0, 255, 254, 252, 246, 234, 213, 183, 147, 109, 73, 43, 22, 10, 4, 2, 1, 0,
];
pub(super) const SHELL_CODE_TABLE3: [u8; 152] = [
    130, 0, 200, 58, 0, 231, 130, 26, 0, 244, 184, 76, 12, 0, 249, 214, 130, 43, 6, 0, 252, 232,
    173, 87, 24, 3, 0, 253, 241, 203, 131, 56, 14, 2, 0, 254, 246, 221, 167, 94, 35, 8, 1, 0, 254,
    249, 232, 193, 130, 65, 23, 5, 1, 0, 255, 251, 239, 211, 162, 99, 45, 15, 4, 1, 0, 255, 251,
    243, 223, 186, 131, 74, 33, 11, 3, 1, 0, 255, 252, 245, 230, 202, 158, 105, 57, 24, 8, 2, 1, 0,
    255, 253, 247, 235, 214, 179, 132, 84, 44, 19, 7, 2, 1, 0, 255, 254, 250, 240, 223, 196, 159,
    112, 69, 36, 15, 6, 2, 1, 0, 255, 254, 253, 245, 231, 209, 176, 136, 93, 55, 27, 11, 3, 2, 1,
    0, 255, 254, 253, 252, 239, 221, 194, 158, 117, 76, 42, 18, 4, 3, 2, 1, 0,
];
pub(super) const SHELL_CODE_TABLE_OFFSETS: [u8; 17] = [
    0, 0, 2, 5, 9, 14, 20, 27, 35, 44, 54, 65, 77, 90, 104, 119, 135,
];
pub(super) const SIGN_ICDF: [u8; 42] = [
    254, 49, 67, 77, 82, 93, 99, 198, 11, 18, 24, 31, 36, 45, 255, 46, 66, 78, 87, 94, 104, 208,
    14, 21, 32, 42, 51, 66, 255, 94, 104, 109, 112, 115, 118, 248, 53, 69, 80, 88, 95, 102,
];

/// Minimal NLSF entropy codebook fields needed by decode_indices.
#[derive(Debug, Clone, Copy)]
pub(super) struct NlsfEntropyCodebook {
    /// NLSF order.
    pub order: usize,
    /// Number of stage-1 vectors.
    pub n_vectors: usize,
    /// Stage-1 iCDF table.
    pub cb1_icdf: &'static [u8],
    /// Packed selector map (`ec_sel`) for per-dimension stage-2 tables.
    pub ec_sel: &'static [u8],
    /// Stage-2 iCDF table bank (`ec_iCDF`).
    pub ec_icdf: &'static [u8],
}

/// NB/MB codebook stage-1 iCDF.
pub(super) const NLSF_CB1_ICDF_NB_MB: [u8; 64] = [
    212, 178, 148, 129, 108, 96, 85, 82, 79, 77, 61, 59, 57, 56, 51, 49, 48, 45, 42, 41, 40, 38,
    36, 34, 31, 30, 21, 12, 10, 3, 1, 0, 255, 245, 244, 236, 233, 225, 217, 203, 190, 176, 175,
    161, 149, 136, 125, 114, 102, 91, 81, 71, 60, 52, 43, 35, 28, 20, 19, 18, 12, 11, 5, 0,
];
/// NB/MB codebook selector map.
pub(super) const NLSF_CB2_SELECT_NB_MB: [u8; 160] = [
    16, 0, 0, 0, 0, 99, 66, 36, 36, 34, 36, 34, 34, 34, 34, 83, 69, 36, 52, 34, 116, 102, 70, 68,
    68, 176, 102, 68, 68, 34, 65, 85, 68, 84, 36, 116, 141, 152, 139, 170, 132, 187, 184, 216, 137,
    132, 249, 168, 185, 139, 104, 102, 100, 68, 68, 178, 218, 185, 185, 170, 244, 216, 187, 187,
    170, 244, 187, 187, 219, 138, 103, 155, 184, 185, 137, 116, 183, 155, 152, 136, 132, 217, 184,
    184, 170, 164, 217, 171, 155, 139, 244, 169, 184, 185, 170, 164, 216, 223, 218, 138, 214, 143,
    188, 218, 168, 244, 141, 136, 155, 170, 168, 138, 220, 219, 139, 164, 219, 202, 216, 137, 168,
    186, 246, 185, 139, 116, 185, 219, 185, 138, 100, 100, 134, 100, 102, 34, 68, 68, 100, 68, 168,
    203, 221, 218, 168, 167, 154, 136, 104, 70, 164, 246, 171, 137, 139, 137, 155, 218, 219, 139,
];
/// NB/MB codebook stage-2 iCDF table bank.
pub(super) const NLSF_CB2_ICDF_NB_MB: [u8; 72] = [
    255, 254, 253, 238, 14, 3, 2, 1, 0, 255, 254, 252, 218, 35, 3, 2, 1, 0, 255, 254, 250, 208, 59,
    4, 2, 1, 0, 255, 254, 246, 194, 71, 10, 2, 1, 0, 255, 252, 236, 183, 82, 8, 2, 1, 0, 255, 252,
    235, 180, 90, 17, 2, 1, 0, 255, 248, 224, 171, 97, 30, 4, 1, 0, 255, 254, 236, 173, 95, 37, 7,
    1, 0,
];

/// WB codebook stage-1 iCDF.
pub(super) const NLSF_CB1_ICDF_WB: [u8; 64] = [
    225, 204, 201, 184, 183, 175, 158, 154, 153, 135, 119, 115, 113, 110, 109, 99, 98, 95, 79, 68,
    52, 50, 48, 45, 43, 32, 31, 27, 18, 10, 3, 0, 255, 251, 235, 230, 212, 201, 196, 182, 167, 166,
    163, 151, 138, 124, 110, 104, 90, 78, 76, 70, 69, 57, 45, 34, 24, 21, 11, 6, 5, 4, 3, 0,
];
/// WB codebook selector map.
pub(super) const NLSF_CB2_SELECT_WB: [u8; 256] = [
    0, 0, 0, 0, 0, 0, 0, 1, 100, 102, 102, 68, 68, 36, 34, 96, 164, 107, 158, 185, 180, 185, 139,
    102, 64, 66, 36, 34, 34, 0, 1, 32, 208, 139, 141, 191, 152, 185, 155, 104, 96, 171, 104, 166,
    102, 102, 102, 132, 1, 0, 0, 0, 0, 16, 16, 0, 80, 109, 78, 107, 185, 139, 103, 101, 208, 212,
    141, 139, 173, 153, 123, 103, 36, 0, 0, 0, 0, 0, 0, 1, 48, 0, 0, 0, 0, 0, 0, 32, 68, 135, 123,
    119, 119, 103, 69, 98, 68, 103, 120, 118, 118, 102, 71, 98, 134, 136, 157, 184, 182, 153, 139,
    134, 208, 168, 248, 75, 189, 143, 121, 107, 32, 49, 34, 34, 34, 0, 17, 2, 210, 235, 139, 123,
    185, 137, 105, 134, 98, 135, 104, 182, 100, 183, 171, 134, 100, 70, 68, 70, 66, 66, 34, 131,
    64, 166, 102, 68, 36, 2, 1, 0, 134, 166, 102, 68, 34, 34, 66, 132, 212, 246, 158, 139, 107,
    107, 87, 102, 100, 219, 125, 122, 137, 118, 103, 132, 114, 135, 137, 105, 171, 106, 50, 34,
    164, 214, 141, 143, 185, 151, 121, 103, 192, 34, 0, 0, 0, 0, 0, 1, 208, 109, 74, 187, 134, 249,
    159, 137, 102, 110, 154, 118, 87, 101, 119, 101, 0, 2, 0, 36, 36, 66, 68, 35, 96, 164, 102,
    100, 36, 0, 2, 33, 167, 138, 174, 102, 100, 84, 2, 2, 100, 107, 120, 119, 36, 197, 24, 0,
];
/// WB codebook stage-2 iCDF table bank.
pub(super) const NLSF_CB2_ICDF_WB: [u8; 72] = [
    255, 254, 253, 244, 12, 3, 2, 1, 0, 255, 254, 252, 224, 38, 3, 2, 1, 0, 255, 254, 251, 209, 57,
    4, 2, 1, 0, 255, 254, 244, 195, 69, 4, 2, 1, 0, 255, 251, 232, 184, 84, 7, 2, 1, 0, 255, 254,
    240, 186, 86, 14, 2, 1, 0, 255, 254, 239, 178, 91, 30, 5, 1, 0, 255, 248, 227, 177, 100, 19, 2,
    1, 0,
];

const NLSF_CODEBOOK_NB_MB: NlsfEntropyCodebook = NlsfEntropyCodebook {
    order: 10,
    n_vectors: 32,
    cb1_icdf: &NLSF_CB1_ICDF_NB_MB,
    ec_sel: &NLSF_CB2_SELECT_NB_MB,
    ec_icdf: &NLSF_CB2_ICDF_NB_MB,
};

const NLSF_CODEBOOK_WB: NlsfEntropyCodebook = NlsfEntropyCodebook {
    order: 16,
    n_vectors: 32,
    cb1_icdf: &NLSF_CB1_ICDF_WB,
    ec_sel: &NLSF_CB2_SELECT_WB,
    ec_icdf: &NLSF_CB2_ICDF_WB,
};

/// Pick NLSF entropy codebook for internal SILK rate.
///
/// Params: `fs_khz` internal SILK sample rate (8/12/16).
/// Returns: NB/MB codebook for 8/12 and WB codebook for 16 kHz.
pub(super) fn nlsf_codebook(fs_khz: u32) -> &'static NlsfEntropyCodebook {
    if fs_khz == 16 {
        &NLSF_CODEBOOK_WB
    } else {
        &NLSF_CODEBOOK_NB_MB
    }
}

/// Pick pitch low-bits iCDF for given internal SILK rate.
///
/// Params: `fs_khz` internal SILK sample rate (8/12/16).
/// Returns: corresponding uniform iCDF table.
pub(super) fn pitch_lag_low_bits_icdf(fs_khz: u32) -> &'static [u8] {
    match fs_khz {
        16 => &UNIFORM8_ICDF,
        12 => &UNIFORM6_ICDF,
        _ => &UNIFORM4_ICDF,
    }
}

/// Pick pitch contour iCDF by bandwidth and subframe count.
///
/// Params: `fs_khz` internal SILK sample rate and `nb_subfr` (2 or 4).
/// Returns: contour iCDF table used by decode_indices.
pub(super) fn pitch_contour_icdf(fs_khz: u32, nb_subfr: usize) -> &'static [u8] {
    if fs_khz == 8 {
        if nb_subfr == 4 {
            &PITCH_CONTOUR_NB_ICDF
        } else {
            &PITCH_CONTOUR_10MS_NB_ICDF
        }
    } else if nb_subfr == 4 {
        &PITCH_CONTOUR_ICDF
    } else {
        &PITCH_CONTOUR_10MS_ICDF
    }
}

/// Pick LTP gain iCDF table by PER index.
///
/// Params: `per_index` decoded LTP periodicity class.
/// Returns: iCDF table for that class, or `None` when out of range.
pub(super) fn ltp_gain_icdf(per_index: usize) -> Option<&'static [u8]> {
    match per_index {
        0 => Some(&LTP_GAIN_ICDF_0),
        1 => Some(&LTP_GAIN_ICDF_1),
        2 => Some(&LTP_GAIN_ICDF_2),
        _ => None,
    }
}
