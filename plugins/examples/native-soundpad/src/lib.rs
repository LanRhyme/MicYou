//! MicYou native plugin example — a soundpad with a UI button panel
//!
//! 演示能力：
//!   1 ui descriptor（route=buttons）：前端读取 config.sounds 渲染按钮网格
//!   2 init 时若没有配置音效，自动生成三个正弦波 WAV（写入插件目录 sounds/）
//!     并持久化到 config.sounds（相对路径，宿主解析到插件目录）
//!   3 handle_message 处理 ui:play 动作：解析 {"id":"x"}，查表后调用
//!     host.play_sound 播放（audio.play 能力）
//!
//! 构建：`cargo build --release`，产物 target/release/libmicyou_example_native_soundpad.so
//! 安装：把 .so 与 plugin.json 放进 ~/.config/micyou/plugins/dev.micyou.example.soundpad/
//! 使用：启用插件 -> 插件页出现音效板按钮，点击即播放

#![allow(non_camel_case_types)]

use std::ffi::{c_char, c_void, CStr, CString};

const MPL_ABI_VERSION: u32 = 1;
const MPL_API_VERSION: u32 = 1;

#[repr(C)]
#[derive(PartialEq, Eq, Clone, Copy)]
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
pub enum mpl_log_level_t {
    MPL_LOG_ERROR = 0,
    MPL_LOG_WARN = 1,
    MPL_LOG_INFO = 2,
    MPL_LOG_DEBUG = 3,
    MPL_LOG_TRACE = 4,
}

/// Host callback table — fields before `ctx` are frozen;
/// `play_sound` lives after `ctx` so older plugins stay ABI-compatible
#[repr(C)]
#[derive(Clone, Copy)]
pub struct mpl_host_api_t {
    pub log: unsafe extern "C" fn(*mut c_void, mpl_log_level_t, *const c_char),
    pub get_config:
        unsafe extern "C" fn(*mut c_void, *const c_char, *mut c_char, *mut u32) -> mpl_result_t,
    pub set_config: unsafe extern "C" fn(*mut c_void, *const c_char, *const c_char) -> mpl_result_t,
    pub emit_event: unsafe extern "C" fn(*mut c_void, *const c_char, *const c_char) -> mpl_result_t,
    pub send_message:
        unsafe extern "C" fn(*mut c_void, *const c_char, *const u8, u32) -> mpl_result_t,
    pub audio_state: unsafe extern "C" fn(*mut c_void, *mut c_char, *mut u32) -> mpl_result_t,
    pub connected_devices: unsafe extern "C" fn(*mut c_void, *mut c_char, *mut u32) -> mpl_result_t,
    pub ctx: *mut c_void,
    pub play_sound: unsafe extern "C" fn(*mut c_void, *const c_char) -> mpl_result_t,
    pub plugin_dir: unsafe extern "C" fn(*mut c_void, *mut c_char, *mut u32) -> mpl_result_t,
    pub register_hotkey: unsafe extern "C" fn(*mut c_void, *const c_char, *mut u64) -> mpl_result_t,
    pub open_window: unsafe extern "C" fn(*mut c_void, *const c_char) -> mpl_result_t,
    pub fs_read: unsafe extern "C" fn(*mut c_void, *const c_char, *mut c_char, *mut u32) -> mpl_result_t,
    pub fs_write: unsafe extern "C" fn(*mut c_void, *const c_char, *const c_char) -> mpl_result_t,
    pub set_timeout: unsafe extern "C" fn(*mut c_void, u64, *const c_char, *mut u64) -> mpl_result_t,
    pub clear_timeout: unsafe extern "C" fn(*mut c_void, u64) -> mpl_result_t,
    pub http_request: unsafe extern "C" fn(*mut c_void, *const c_char, *const c_char, *const c_char, *const c_char, *mut u64) -> mpl_result_t,
    pub set_interval: unsafe extern "C" fn(*mut c_void, u64, *const c_char, *mut u64) -> mpl_result_t,
    pub clear_interval: unsafe extern "C" fn(*mut c_void, u64) -> mpl_result_t,
    pub open_url: unsafe extern "C" fn(*mut c_void, *const c_char) -> mpl_result_t,
    pub notify: unsafe extern "C" fn(*mut c_void, *const c_char, *const c_char) -> mpl_result_t,
    pub locale: unsafe extern "C" fn(*mut c_void, *mut c_char, *mut u32) -> mpl_result_t,
    pub host_info: unsafe extern "C" fn(*mut c_void, *mut c_char, *mut u32) -> mpl_result_t,
    pub clipboard_read: unsafe extern "C" fn(*mut c_void, *mut c_char, *mut u32) -> mpl_result_t,
    pub clipboard_write: unsafe extern "C" fn(*mut c_void, *const c_char) -> mpl_result_t,
    pub set_panel_icon: unsafe extern "C" fn(*mut c_void, *const c_char, *const c_char) -> mpl_result_t,
}

#[repr(C)]
pub struct mpl_plugin_info_t {
    pub abi_version: u32,
    pub api_version: u32,
    pub id: *const c_char,
    pub version: *const c_char,
}

unsafe impl Sync for mpl_plugin_info_t {}

const PLUGIN_ID: &[u8] = b"dev.micyou.example.soundpad\0";
const PLUGIN_VERSION: &[u8] = b"1.0.0\0";
const CONFIG_KEY: &[u8] = b"sounds\0";

static mut HOST: Option<mpl_host_api_t> = None;

/// (id, label, relative file path)
static mut SOUNDS: Vec<(String, String, String)> = Vec::new();

fn guard<F: FnOnce() -> mpl_result_t + std::panic::UnwindSafe>(f: F) -> mpl_result_t {
    std::panic::catch_unwind(f).unwrap_or(mpl_result_t::MPL_ERR_RUNTIME)
}

unsafe fn host() -> mpl_host_api_t {
    unsafe { HOST.expect("init must run before host calls") }
}

unsafe fn log_info(msg: &str) {
    unsafe {
        let c = CString::new(msg).expect("nul-free log");
        let h = host();
        ((h.log)(h.ctx, mpl_log_level_t::MPL_LOG_INFO, c.as_ptr()));
    }
}

/// Call host get_config(key), returning the string value (empty on miss)
unsafe fn host_get_config(key: &[u8]) -> String {
    unsafe {
        let h = host();
        let mut buf = [0i8; 8192];
        let mut size: u32 = buf.len() as u32;
        let code = (h.get_config)(
            h.ctx,
            key.as_ptr() as *const c_char,
            buf.as_mut_ptr(),
            &mut size,
        );
        if code == mpl_result_t::MPL_OK && size > 0 {
            CStr::from_ptr(buf.as_ptr()).to_string_lossy().to_string()
        } else {
            String::new()
        }
    }
}

/// Call host set_config(key, json)
unsafe fn host_set_config(key: &[u8], json: &str) -> bool {
    unsafe {
        let h = host();
        let value = CString::new(json).expect("nul-free json");
        let code = (h.set_config)(h.ctx, key.as_ptr() as *const c_char, value.as_ptr());
        code == mpl_result_t::MPL_OK
    }
}

/// Call host plugin_dir(), returning the absolute plugin directory
unsafe fn host_plugin_dir() -> String {
    unsafe {
        let h = host();
        let mut buf = [0i8; 4096];
        let mut size: u32 = buf.len() as u32;
        let code = (h.plugin_dir)(h.ctx, buf.as_mut_ptr(), &mut size);
        if code == mpl_result_t::MPL_OK && size > 0 {
            CStr::from_ptr(buf.as_ptr()).to_string_lossy().to_string()
        } else {
            String::new()
        }
    }
}

// ── 必需入口 ───────────────────────────────────────────────────────────────

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

/// 初始化：保存 host、加载（或生成）音效配置
/// # Safety
/// `host` 必须指向有效的 mpl_host_api_t，且生命周期长于插件
#[unsafe(no_mangle)]
pub unsafe extern "C" fn micyou_plugin_init(host: *const mpl_host_api_t) -> mpl_result_t {
    guard(|| {
        if host.is_null() || (*host).log as usize == 0 {
            return mpl_result_t::MPL_ERR_INVALID_ARG;
        }
        unsafe {
            HOST = Some(*host);
            match load_or_generate_sounds() {
                Ok(()) => log_info("soundpad initialized"),
                Err(e) => {
                    let msg = format!("soundpad init failed: {e}");
                    log_info(&msg);
                }
            }
            // 侧边栏面板图标（set_panel_icon 无能力要求）
            if ((*host).set_panel_icon as usize) != 0 {
                let pid = std::ffi::CString::new("console").expect("nul-free");
                let icon = std::ffi::CString::new("🎛").expect("nul-free");
                ((*host).set_panel_icon)((*host).ctx, pid.as_ptr(), icon.as_ptr());
            }
            // 注册全局快捷键：Ctrl+Shift+S 播放第一个音效
            let mut hotkey_id: u64 = 0;
            let sc = std::ffi::CString::new("ctrl+shift+s").expect("nul-free");
            let code = ((*host).register_hotkey)((*host).ctx, sc.as_ptr(), &mut hotkey_id);
            if code == mpl_result_t::MPL_OK && hotkey_id != 0 {
                let msg = format!("soundpad: hotkey ctrl+shift+s registered (id {hotkey_id})");
                log_info(&msg);
            } else {
                log_info("soundpad: hotkey registration skipped (host unavailable)");
            }
        }
        mpl_result_t::MPL_OK
    })
}

/// 从配置加载音效；为空时生成三个示例音效并持久化
unsafe fn load_or_generate_sounds() -> Result<(), String> {
    unsafe {
        let raw = host_get_config(CONFIG_KEY);
        let parsed: serde_json::Value =
            serde_json::from_str(&raw).unwrap_or(serde_json::Value::Null);
        let mut sounds: Vec<(String, String, String)> = Vec::new();
        if let Some(arr) = parsed.as_array() {
            for item in arr {
                if let (Some(id), Some(label), Some(file)) = (
                    item.get("id").and_then(|v| v.as_str()),
                    item.get("label").and_then(|v| v.as_str()),
                    item.get("file").and_then(|v| v.as_str()),
                ) {
                    sounds.push((id.to_string(), label.to_string(), file.to_string()));
                }
            }
        }
        if !sounds.is_empty() {
            SOUNDS = sounds;
            return Ok(());
        }
        // 生成示例音效：三个正弦波（440/660/880 Hz，0.35s）
        let demo: Vec<(String, String, u32)> = vec![
            ("beep".into(), "Beep".into(), 440),
            ("ding".into(), "Ding".into(), 660),
            ("chime".into(), "Chime".into(), 880),
        ];
        let dir = host_plugin_dir();
        if dir.is_empty() {
            return Err("plugin_dir unavailable".into());
        }
        for (id, label, freq) in &demo {
            let abs = format!("{dir}/sounds/{id}.wav");
            write_sine_wav(&abs, *freq, 0.35, 0.5)?;
            // config 存相对路径，宿主播放时解析到插件目录
            sounds.push((id.clone(), label.clone(), format!("sounds/{id}.wav")));
        }
        let json = serde_json::to_string(
        &sounds
            .iter()
            .map(|(id, label, file)| {
                serde_json::json!({ "id": id, "label": label, "file": file })
            })
            .collect::<Vec<_>>(),
    )
    .map_err(|e| e.to_string())?;
        host_set_config(CONFIG_KEY, &json);
        SOUNDS = sounds;
        Ok(())
    }
}

/// 生成 16-bit PCM 单声道正弦波 WAV（写入绝对路径）
fn write_sine_wav(rel: &str, freq: u32, seconds: f64, amp: f32) -> Result<(), String> {
    let sample_rate = 44100u32;
    let n = (sample_rate as f64 * seconds) as usize;
    let mut pcm = Vec::with_capacity(n * 2);
    for i in 0..n {
        let t = i as f64 / sample_rate as f64;
        let v = (t * freq as f64 * std::f64::consts::TAU).sin() as f32 * amp;
        let s = (v.clamp(-1.0, 1.0) * 32767.0) as i16;
        pcm.extend_from_slice(&s.to_le_bytes());
    }
    let data_len = pcm.len() as u32;
    let riff_len = 36 + data_len;
    let mut wav = Vec::with_capacity(riff_len as usize + 8);
    wav.extend_from_slice(b"RIFF");
    wav.extend_from_slice(&riff_len.to_le_bytes());
    wav.extend_from_slice(b"WAVE");
    wav.extend_from_slice(b"fmt ");
    wav.extend_from_slice(&16u32.to_le_bytes()); // fmt chunk size
    wav.extend_from_slice(&1u16.to_le_bytes()); // PCM
    wav.extend_from_slice(&1u16.to_le_bytes()); // mono
    wav.extend_from_slice(&sample_rate.to_le_bytes());
    wav.extend_from_slice(&(sample_rate * 2).to_le_bytes()); // byte rate
    wav.extend_from_slice(&2u16.to_le_bytes()); // block align
    wav.extend_from_slice(&16u16.to_le_bytes()); // bits per sample
    wav.extend_from_slice(b"data");
    wav.extend_from_slice(&data_len.to_le_bytes());
    wav.extend_from_slice(&pcm);
    // 相对路径 -> 插件目录（宿主负责解析）
    let dir = std::path::Path::new(rel);
    if let Some(parent) = dir.parent() {
        std::fs::create_dir_all(parent).map_err(|e| e.to_string())?;
    }
    std::fs::write(rel, &wav).map_err(|e| e.to_string())
}

/// 反初始化
/// # Safety
/// 无额外要求
#[unsafe(no_mangle)]
#[allow(static_mut_refs)]
pub unsafe extern "C" fn micyou_plugin_deinit() {
    unsafe {
        HOST = None;
        SOUNDS.clear();
    }
}

// ── 可选入口 ───────────────────────────────────────────────────────────────

/// 事件通知（本示例不处理）
#[unsafe(no_mangle)]
pub extern "C" fn micyou_plugin_handle_event(
    _type_name: *const c_char,
    _json: *const c_char,
) -> mpl_result_t {
    mpl_result_t::MPL_OK
}

/// 跨端消息 / UI 动作：
/// - topic `ui:play`：payload `{"id":"x"}` 播放对应音效
/// - topic `ui:log`：payload `{"message":"..."}` 记录面板日志
/// - topic `hotkey:<id>`：全局快捷键触发，播放第一个音效
/// # Safety
/// `topic` 必须为 NUL 结尾字符串，`payload` 必须指向 payload_len 字节
#[unsafe(no_mangle)]
#[allow(static_mut_refs)]
pub unsafe extern "C" fn micyou_plugin_handle_message(
    _source: *const c_char,
    topic: *const c_char,
    payload: *const u8,
    payload_len: u32,
) -> mpl_result_t {
    guard(|| {
        let Ok(topic_str) = (unsafe { CStr::from_ptr(topic) }).to_str() else {
            return mpl_result_t::MPL_ERR_INVALID_ARG;
        };
        // 全局快捷键：播放第一个音效
        if topic_str.starts_with("hotkey:") {
            let sounds = unsafe { &SOUNDS };
            let Some((id, _, _)) = sounds.first() else {
                return mpl_result_t::MPL_OK;
            };
            return play_sound_by_id(id);
        }
        if !topic_str.starts_with("ui:") {
            return mpl_result_t::MPL_OK; // 其他主题，忽略
        }
        let action = &topic_str[3..];
        if action == "log" {
            let msg = body_string(payload, payload_len).unwrap_or_default();
            unsafe { log_info(&format!("soundpad panel: {msg}")) };
            return mpl_result_t::MPL_OK;
        }
        if action == "open_window" {
            // 插件自主开窗：面板按钮 → ui:open_window → 宿主打开独立窗口
            let panel = unsafe { body_string(payload, payload_len) }
                .and_then(|p| serde_json::from_str::<serde_json::Value>(&p).ok())
                .and_then(|v| v.get("panel").and_then(|p| p.as_str()).map(String::from))
                .unwrap_or_else(|| "console".to_string());
            if let Ok(c) = CString::new(panel) {
                unsafe {
                    let h = host();
                    let _ = (h.open_window)(h.ctx, c.as_ptr());
                }
            }
            return mpl_result_t::MPL_OK;
        }
        if action != "play" {
            return mpl_result_t::MPL_OK;
        }
        let Some(id) = parsed_id(payload, payload_len) else {
            return mpl_result_t::MPL_ERR_INVALID_ARG;
        };
        play_sound_by_id(&id)
    })
}

/// 解析 payload 中的 `{"id":"x"}`，返回 id
unsafe fn parsed_id(payload: *const u8, payload_len: u32) -> Option<String> {
    if payload.is_null() || payload_len == 0 {
        return None;
    }
    let body = unsafe { std::slice::from_raw_parts(payload, payload_len as usize) };
    let parsed: serde_json::Value = serde_json::from_slice(body).ok()?;
    parsed.get("id").and_then(|v| v.as_str()).map(String::from)
}

/// payload 原样转为字符串（面板日志等）
unsafe fn body_string(payload: *const u8, payload_len: u32) -> Option<String> {
    if payload.is_null() || payload_len == 0 {
        return None;
    }
    let body = unsafe { std::slice::from_raw_parts(payload, payload_len as usize) };
    String::from_utf8(body.to_vec()).ok()
}

/// 按 id 查表并调用 host.play_sound
#[allow(static_mut_refs)]
fn play_sound_by_id(id: &str) -> mpl_result_t {
    let sounds = unsafe { &SOUNDS };
    let Some((_, _, file)) = sounds.iter().find(|(sid, _, _)| sid == id) else {
        let msg = format!("soundpad: unknown sound {id}");
        unsafe { log_info(&msg) };
        return mpl_result_t::MPL_ERR_INVALID_ARG;
    };
    let c = match CString::new(file.clone()) {
        Ok(c) => c,
        Err(_) => return mpl_result_t::MPL_ERR_INVALID_ARG,
    };
    unsafe {
        let h = host();
        let code = (h.play_sound)(h.ctx, c.as_ptr());
        if code != mpl_result_t::MPL_OK {
            let msg = format!("soundpad: play failed for {id}");
            log_info(&msg);
            return code;
        }
    }
    let msg = format!("soundpad: playing {id}");
    unsafe { log_info(&msg) };
    mpl_result_t::MPL_OK
}
