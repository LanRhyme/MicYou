use std::path::PathBuf;

/// Returns the current time in milliseconds since UNIX epoch.
/// This is a safe wrapper around `SystemTime::duration_since` that
/// returns 0 if the clock is before UNIX_EPOCH (should never happen).
pub fn now_millis() -> i64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_millis() as i64)
        .unwrap_or(0)
}

/// Resolve the directory containing `ulunas.onnx` (ONNX noise suppression model).
///
/// Searches in order:
/// 1. The executable's parent directory
/// 2. `<exe_dir>/resources`
/// 3. `<CARGO_MANIFEST_DIR>/resources` (dev fallback)
pub fn find_model_dir() -> Option<PathBuf> {
    let exe_dir = std::env::current_exe()
        .ok()
        .and_then(|p| p.parent().map(|d| d.to_path_buf()));
    exe_dir.as_ref().and_then(|d| {
        if d.join("ulunas.onnx").exists() {
            return Some(d.clone());
        }
        let res = d.join("resources");
        if res.join("ulunas.onnx").exists() {
            return Some(res);
        }
        let dev = PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("resources");
        if dev.join("ulunas.onnx").exists() {
            return Some(dev);
        }
        None
    })
}
