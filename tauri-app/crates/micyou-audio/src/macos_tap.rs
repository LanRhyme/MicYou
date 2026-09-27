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

//! macOS far-end reference capture through Core Audio process taps.
//!
//! macOS has no WASAPI-style loopback: before 14.2 the only capture sources are
//! device inputs, which makes a speaker reference impossible without a virtual
//! device that MicYou itself is already writing to. `AudioHardwareCreateProcessTap`
//! (macOS 14.2+) taps what the system is playing while excluding MicYou's own
//! process, which is the platform equivalent of the Windows/Linux capture paths.
//!
//! The Core Audio functions are resolved with `dlopen`/`dlsym` instead of being
//! linked. A linked reference to `AudioHardwareCreateProcessTap` would leave an
//! unresolved symbol on older systems, and resolving it at run time doubles as
//! the capability probe that `crate::aec` reports to every frontend.

use std::ffi::c_void;
use std::os::raw::{c_char, c_int};

const CORE_AUDIO_PATH: &[u8] = b"/System/Library/Frameworks/CoreAudio.framework/CoreAudio\0";
const RTLD_NOW: c_int = 2;

/// `AudioHardwareCreateProcessTap`, the symbol that separates 14.2+ from older releases.
pub(crate) const SYMBOL_CREATE_PROCESS_TAP: &[u8] = b"AudioHardwareCreateProcessTap\0";

extern "C" {
    fn dlopen(path: *const c_char, mode: c_int) -> *mut c_void;
    fn dlsym(handle: *mut c_void, symbol: *const c_char) -> *mut c_void;
}

/// The Core Audio framework handle, opened once per process and never closed.
///
/// The handle is stored as an address because raw pointers are neither `Send`
/// nor `Sync` and therefore cannot live in a `OnceLock`.
fn framework_handle() -> Option<*mut c_void> {
    static HANDLE: std::sync::OnceLock<usize> = std::sync::OnceLock::new();
    let address = *HANDLE.get_or_init(|| unsafe {
        dlopen(CORE_AUDIO_PATH.as_ptr() as *const c_char, RTLD_NOW) as usize
    });
    if address == 0 {
        None
    } else {
        Some(address as *mut c_void)
    }
}

/// Resolves a NUL-terminated Core Audio symbol name.
///
/// Returns `None` when the framework cannot be opened or the running system does
/// not export the symbol.
pub(crate) fn resolve(symbol: &[u8]) -> Option<*mut c_void> {
    let handle = framework_handle()?;
    let resolved = unsafe { dlsym(handle, symbol.as_ptr() as *const c_char) };
    if resolved.is_null() {
        None
    } else {
        Some(resolved)
    }
}

/// Whether this system can create a Core Audio process tap.
pub(crate) fn process_tap_available() -> bool {
    static AVAILABLE: std::sync::OnceLock<bool> = std::sync::OnceLock::new();
    *AVAILABLE.get_or_init(|| resolve(SYMBOL_CREATE_PROCESS_TAP).is_some())
}

#[cfg(test)]
mod tests {
    use super::{process_tap_available, resolve, SYMBOL_CREATE_PROCESS_TAP};

    #[test]
    fn capability_probe_is_cached_and_consistent() {
        assert_eq!(process_tap_available(), process_tap_available());
    }

    #[test]
    fn missing_symbol_reports_unavailable() {
        // A symbol no Core Audio release exports must resolve to `None`
        // rather than panicking, which is what an older system looks like.
        assert!(resolve(b"MicYouDefinitelyNotACoreAudioSymbol\0").is_none());
    }

    #[test]
    fn framework_loads_and_exports_core_symbols() {
        // System frameworks live in the dyld shared cache, so their binary has no
        // on-disk path; the real check is that `dlopen`/`dlsym` resolve a symbol
        // Core Audio has always exported.
        assert!(resolve(b"AudioObjectGetPropertyData\0").is_some());
    }

    #[test]
    fn process_tap_symbol_name_is_nul_terminated() {
        let symbol = std::str::from_utf8(&SYMBOL_CREATE_PROCESS_TAP[..SYMBOL_CREATE_PROCESS_TAP.len() - 1])
            .expect("symbol must be valid UTF-8");
        assert_eq!(symbol, "AudioHardwareCreateProcessTap");
        assert_eq!(*SYMBOL_CREATE_PROCESS_TAP.last().unwrap(), 0);
    }
}
