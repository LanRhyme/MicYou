//! SILK pitch lag decoding helpers.

use super::tables::{
    CB_LAGS_STAGE2_10MS_NB, CB_LAGS_STAGE2_NB, CB_LAGS_STAGE3_10MS_WB, CB_LAGS_STAGE3_WB,
    MAX_NB_SUBFR, PITCH_EST_MAX_LAG_MS, PITCH_EST_MIN_LAG_MS,
};

/// Decode absolute pitch lags for each active subframe.
///
/// Params: absolute `lag_index`, `contour_index`, internal `fs_khz`, and active `nb_subfr`.
/// Returns: decoded pitch lag per subframe in samples.
pub(super) fn decode_pitch_lags(
    lag_index: i32,
    contour_index: i32,
    fs_khz: i32,
    nb_subfr: usize,
) -> [i32; MAX_NB_SUBFR] {
    let min_lag = PITCH_EST_MIN_LAG_MS * fs_khz;
    let max_lag = PITCH_EST_MAX_LAG_MS * fs_khz;
    let lag = min_lag + lag_index;
    let mut pitch_lags = [0i32; MAX_NB_SUBFR];

    for (k, pitch_lag) in pitch_lags.iter_mut().enumerate().take(nb_subfr) {
        let contour = contour_value(fs_khz, nb_subfr, k, contour_index as usize) as i32;
        *pitch_lag = (lag + contour).clamp(min_lag, max_lag);
    }

    pitch_lags
}

/// Return one contour entry from the SILK pitch codebook.
///
/// Params: internal `fs_khz`, active `nb_subfr`, `subframe` row, and `contour_index` column.
/// Returns: contour delta in samples.
fn contour_value(fs_khz: i32, nb_subfr: usize, subframe: usize, contour_index: usize) -> i8 {
    match (fs_khz, nb_subfr) {
        (8, 4) => CB_LAGS_STAGE2_NB[subframe][contour_index],
        (8, 2) => CB_LAGS_STAGE2_10MS_NB[subframe][contour_index],
        (_, 4) => CB_LAGS_STAGE3_WB[subframe][contour_index],
        (_, 2) => CB_LAGS_STAGE3_10MS_WB[subframe][contour_index],
        _ => panic!("unsupported pitch shape fs_khz={fs_khz} nb_subfr={nb_subfr}"),
    }
}
