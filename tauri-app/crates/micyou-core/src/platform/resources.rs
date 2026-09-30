/*
 * MicYou — Turns your Android device into a high-quality PC microphone.
 * Copyright (C) 2026 LanRhyme <https://github.com/LanRhyme/MicYou>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version, with the MicYou Plugin Exception.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 */

//! Lookup of files bundled with the desktop app (ONNX models, ONNX Runtime,
//! ALSA config) across dev trees, installed packages and AppImages.

use std::path::{Path, PathBuf};

/// The GUI crate owns `resources/` and `libs/`; `cargo run` falls back to it.
const DEV_APP_DIR: &str = concat!(env!("CARGO_MANIFEST_DIR"), "/../../src-tauri");

/// Platform-specific ONNX Runtime shared library filename.
pub const fn ort_runtime_filename() -> &'static str {
    if cfg!(target_os = "windows") {
        "onnxruntime.dll"
    } else if cfg!(target_os = "macos") {
        "libonnxruntime.dylib"
    } else {
        "libonnxruntime.so"
    }
}

fn exe_dir() -> Option<PathBuf> {
    std::env::current_exe()
        .ok()
        .and_then(|path| path.parent().map(Path::to_path_buf))
}

/// Find the ONNX Runtime shared library bundled alongside the application.
pub fn find_ort_runtime(resource_root: Option<&Path>) -> Option<PathBuf> {
    let filename = ort_runtime_filename();
    let mut candidates: Vec<PathBuf> = Vec::new();

    // Production bundles copy the library into resources/.
    if let Some(root) = resource_root {
        candidates.push(root.join(filename));
        if let Some(parent) = root.parent() {
            candidates.push(parent.join("libs").join(filename));
            candidates.push(parent.join(filename));
        }
    }

    if let Some(exe_dir) = exe_dir() {
        candidates.push(exe_dir.join(filename));
        candidates.push(exe_dir.join("resources").join(filename));
        candidates.push(exe_dir.join("libs").join(filename));
        if let Some(prefix) = exe_dir.parent() {
            let install = prefix.join("lib").join("micyou");
            candidates.push(install.join("libs").join(filename));
            candidates.push(install.join("resources").join(filename));
        }
    }

    candidates.push(Path::new(DEV_APP_DIR).join("libs").join(filename));
    candidates.into_iter().find(|p| p.exists())
}

/// Locate the directory containing MicYou's bundled runtime resources. Linux
/// packages use /usr/bin + /usr/lib/micyou/resources, AppImage exposes the
/// same tree below its mount point. `hint` is the frontend's own idea of the
/// resource directory, if it has one.
pub fn find_resource_dir(hint: Option<&Path>) -> Option<PathBuf> {
    const MARKERS: [&str; 2] = ["purevox6.onnx", "aec7_ep0185.onnx"];

    let mut candidates = Vec::new();
    if let Some(dir) = hint {
        candidates.push(dir.to_path_buf());
        candidates.push(dir.join("resources"));
    }
    if let Some(exe_dir) = exe_dir() {
        candidates.push(exe_dir.join("resources"));
        if let Some(prefix) = exe_dir.parent() {
            candidates.push(prefix.join("lib").join("micyou").join("resources"));
        }
        candidates.push(exe_dir);
    }
    candidates.push(Path::new(DEV_APP_DIR).join("resources"));

    candidates
        .into_iter()
        .find(|directory| MARKERS.iter().any(|model| directory.join(model).exists()))
}
