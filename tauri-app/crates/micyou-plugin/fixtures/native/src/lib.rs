//! Native plugin fixture — a real Rust cdylib implementing the MicYou plugin
//! ABI (see `crates/micyou-plugin/include/micyou_plugin_abi.h`).
//!
//! This fixture doubles as a reference for writing native plugins in Rust:
//! export `extern "C"` + `#[unsafe(no_mangle)]` symbols, mirror the ABI types
//! with `#[repr(C)]`, and never allocate/panic across the FFI boundary
//! (panics are caught below via `catch_unwind`).
//!
//! Behavior: scales incoming audio by a configurable gain (default 1.0) and
//! counts received events/messages so tests can verify delivery.

#![allow(non_camel_case_types)]

use std::ffi::{c_char, c_void, CStr};

// ── ABI types (mirror include/micyou_plugin_abi.h) ────────────────────────

const MPL_ABI_VERSION: u32 = 1;
const MPL_API_VERSION: u32 = 1;

#[repr(C)]
#[derive(PartialEq, Eq, Debug)]
#[allow(dead_code)]
pub enum mpl_result_t {
    MPL_OK = 0,
    MPL_ERR_NOT_IMPLEMENTED = 1,
    MPL_ERR_INVALID_ARG = 2,
    MPL_ERR_RUNTIME = 3,
    MPL_ERR_BUFFER_TOO_SMALL = 4,
    MPL_ERR_PERMISSION = 5,
}

#[repr(C)]
#[derive(Clone, Copy)]
#[allow(dead_code)]
pub enum mpl_log_level_t {
    MPL_LOG_ERROR = 0,
    MPL_LOG_WARN = 1,
    MPL_LOG_INFO = 2,
    MPL_LOG_DEBUG = 3,
    MPL_LOG_TRACE = 4,
}

#[repr(C)]
#[derive(Clone, Copy)]
pub struct mpl_host_api_t {
    log: unsafe extern "C" fn(*mut c_void, mpl_log_level_t, *const c_char),
    get_config:
        unsafe extern "C" fn(*mut c_void, *const c_char, *mut c_char, *mut u32) -> mpl_result_t,
    set_config: unsafe extern "C" fn(*mut c_void, *const c_char, *const c_char) -> mpl_result_t,
    emit_event: unsafe extern "C" fn(*mut c_void, *const c_char, *const c_char) -> mpl_result_t,
    send_message: unsafe extern "C" fn(*mut c_void, *const c_char, *const u8, u32) -> mpl_result_t,
    audio_state: unsafe extern "C" fn(*mut c_void, *mut c_char, *mut u32) -> mpl_result_t,
    connected_devices: unsafe extern "C" fn(*mut c_void, *mut c_char, *mut u32) -> mpl_result_t,
    ctx: *mut c_void,
}

#[repr(C)]
pub struct mpl_plugin_info_t {
    abi_version: u32,
    api_version: u32,
    id: *const c_char,
    version: *const c_char,
}

// The info struct is read-only after construction; raw pointer fields are
// only read on the loading thread.
unsafe impl Sync for mpl_plugin_info_t {}

// ── Plugin state ───────────────────────────────────────────────────────────

const PLUGIN_ID: &[u8] = b"test.native.minimal\0";
const PLUGIN_VERSION: &[u8] = b"1.0.0\0";

static mut HOST: Option<mpl_host_api_t> = None;
static mut EVENTS_RECEIVED: i32 = 0;
static mut MESSAGES_RECEIVED: i32 = 0;
static mut GAIN: f64 = 1.0;

/// Wrap ABI calls in catch_unwind so a panicking plugin cannot cross the FFI
/// boundary as UB — the host observes a runtime error instead.
fn guard<F: FnOnce() -> mpl_result_t + std::panic::UnwindSafe>(f: F) -> mpl_result_t {
    match std::panic::catch_unwind(f) {
        Ok(code) => code,
        Err(_) => mpl_result_t::MPL_ERR_RUNTIME,
    }
}

// ── Required entry points ──────────────────────────────────────────────────

/// Returns a pointer to a static info struct; valid for the library lifetime.
#[unsafe(no_mangle)]
pub extern "C" fn micyou_plugin_info() -> *const mpl_plugin_info_t {
    static INFO: mpl_plugin_info_t = mpl_plugin_info_t {
        abi_version: MPL_ABI_VERSION,
        api_version: MPL_API_VERSION,
        id: PLUGIN_ID.as_ptr() as *const c_char,
        version: PLUGIN_VERSION.as_ptr() as *const c_char,
    };
    &INFO
}

/// # Safety
/// `host` must point to a valid mpl_host_api_t for the plugin lifetime.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn micyou_plugin_init(host: *const mpl_host_api_t) -> mpl_result_t {
    guard(|| {
        if host.is_null() || (*host).log as usize == 0 {
            return mpl_result_t::MPL_ERR_INVALID_ARG;
        }
        unsafe {
            HOST = Some(*host);
            ((*host).log)(
                (*host).ctx,
                mpl_log_level_t::MPL_LOG_INFO,
                b"native_minimal initialized\0".as_ptr() as *const c_char,
            );
        }
        mpl_result_t::MPL_OK
    })
}

/// # Safety
/// No-op when not initialized.
#[unsafe(no_mangle)]
#[allow(static_mut_refs)]
pub unsafe extern "C" fn micyou_plugin_deinit() {
    unsafe {
        if let Some(host) = HOST.take() {
            (host.log)(
                host.ctx,
                mpl_log_level_t::MPL_LOG_INFO,
                b"native_minimal deinitialized\0".as_ptr() as *const c_char,
            );
        }
    }
}

// ── Optional entry points ──────────────────────────────────────────────────

/// # Safety
/// `data` must point to `samples` interleaved f32 frames; `bypass` to a u32.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn micyou_plugin_process(
    data: *mut f32,
    samples: u32,
    _channels: u32,
    _queued_ms: f64,
    bypass: *mut u32,
) -> mpl_result_t {
    guard(|| {
        if data.is_null() || bypass.is_null() {
            return mpl_result_t::MPL_ERR_INVALID_ARG;
        }
        let gain = unsafe { GAIN };
        if gain <= 0.0 {
            unsafe { *bypass = 1 };
            return mpl_result_t::MPL_OK;
        }
        unsafe {
            for i in 0..samples as usize {
                *data.add(i) *= gain as f32;
            }
            *bypass = 0;
        }
        mpl_result_t::MPL_OK
    })
}

/// # Safety
/// `type_name`/`json` must be NUL-terminated strings.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn micyou_plugin_handle_event(
    type_name: *const c_char,
    _json: *const c_char,
) -> mpl_result_t {
    guard(|| {
        if type_name.is_null() {
            return mpl_result_t::MPL_ERR_INVALID_ARG;
        }
        unsafe {
            EVENTS_RECEIVED += 1;
        }
        mpl_result_t::MPL_OK
    })
}

/// # Safety
/// `source`/`topic` must be NUL-terminated; `payload` valid for `payload_len`.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn micyou_plugin_handle_message(
    _source: *const c_char,
    _topic: *const c_char,
    _payload: *const u8,
    _payload_len: u32,
) -> mpl_result_t {
    unsafe {
        MESSAGES_RECEIVED += 1;
    }
    mpl_result_t::MPL_OK
}

// ── Test helpers (not part of the ABI) ─────────────────────────────────────

/// # Safety
/// None; sets the internal gain.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn test_native_set_gain(gain: f64) {
    unsafe { GAIN = gain };
}

/// # Safety
/// None.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn test_native_events() -> i32 {
    unsafe { EVENTS_RECEIVED }
}

/// # Safety
/// None.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn test_native_messages() -> i32 {
    unsafe { MESSAGES_RECEIVED }
}

/// Exercise host callbacks: read our own config and log it (used by tests to
/// prove the host table works across the boundary).
/// # Safety
/// None.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn test_native_host_call() -> mpl_result_t {
    guard(|| {
        let host = unsafe { HOST };
        let Some(host) = host else {
            return mpl_result_t::MPL_ERR_NOT_IMPLEMENTED;
        };
        let mut buf = [0i8; 512];
        let mut size: u32 = buf.len() as u32;
        let key = b"fixture.key\0";
        let result = unsafe {
            (host.get_config)(
                host.ctx,
                key.as_ptr() as *const c_char,
                buf.as_mut_ptr(),
                &mut size,
            )
        };
        if result == mpl_result_t::MPL_OK {
            let value = unsafe { CStr::from_ptr(buf.as_ptr()) }
                .to_string_lossy()
                .to_string();
            let msg = format!("fixture config = {value}");
            unsafe {
                (host.log)(
                    host.ctx,
                    mpl_log_level_t::MPL_LOG_INFO,
                    msg.as_ptr() as *const c_char,
                );
            }
        }
        result
    })
}
