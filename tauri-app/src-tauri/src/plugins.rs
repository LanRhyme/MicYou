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

//! Plugin host wiring: owns the plugin manager, the DSP node registry and the
//! cross-device message bus, shared by the audio thread (via
//! `DspProcessor::set_external_hook`), the TCP server (plugin message relay)
//! and the frontend commands (`commands/plugins.rs`).

use micyou_plugin::bus::{PluginBus, PluginMessage, PluginSyncTransport};
use micyou_plugin::host::{
    AudioStateSnapshot, DeviceSnapshot, HostApi, MessageTarget, PluginLogLevel,
};
use micyou_plugin::manifest::{PluginKind, RuntimeKind};
use micyou_plugin::{PluginError, PluginResult, PluginRuntime};
use std::collections::{HashMap, VecDeque};
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::{Arc, Mutex, RwLock};
use tauri::Manager;
use tauri_plugin_global_shortcut::{GlobalShortcutExt, ShortcutState, ShortcutWrapper};

// 引入全局热键底层库用于 CLI/TUI 模式
use global_hotkey::{GlobalHotKeyManager, GlobalHotKeyEvent, hotkey::HotKey};

// ==========================================
// 跨平台无头模式消息泵 (Headless Event Pump)
// ==========================================

#[cfg(target_os = "windows")]
mod headless_event_pump {
    use std::ffi::c_void;

    #[repr(C)]
    struct POINT { x: i32, y: i32 }
    #[allow(non_snake_case)]
    #[repr(C)]
    struct MSG {
        hwnd: *mut c_void, message: u32, wParam: usize, lParam: isize,
        time: u32, pt: POINT, lPrivate: u32,
    }

    const PM_REMOVE: u32 = 0x0001;

    #[link(name = "user32")]
    extern "system" {
        fn PeekMessageW(lpMsg: *mut MSG, hWnd: *mut c_void, wMsgFilterMin: u32, wMsgFilterMax: u32, wRemoveMsg: u32) -> i32;
        fn TranslateMessage(lpMsg: *const MSG) -> i32;
        fn DispatchMessageW(lpMsg: *const MSG) -> isize;
    }

    pub fn pump() {
        unsafe {
            let mut msg: MSG = std::mem::zeroed();
            while PeekMessageW(&mut msg, std::ptr::null_mut(), 0, 0, PM_REMOVE) != 0 {
                TranslateMessage(&msg);
                DispatchMessageW(&msg);
            }
        }
    }
}

#[cfg(target_os = "macos")]
mod headless_event_pump {
    use std::ffi::c_void;

    // 链接 macOS 原生的 CoreFoundation 框架，无需引入 cocoa/objc 等重型 crate
    #[link(name = "CoreFoundation", kind = "framework")]
    extern "C" {
        fn CFRunLoopRunInMode(
            mode: *const c_void,
            seconds: f64,
            returnAfterSourceHandled: u8,
        ) -> i32;
        
        static kCFRunLoopDefaultMode: *const c_void;
    }

    pub fn pump() {
        unsafe {
            // 运行 RunLoop 10ms 以处理底层的热键硬件事件
            CFRunLoopRunInMode(kCFRunLoopDefaultMode, 0.01, 0);
        }
    }
}

#[cfg(target_os = "linux")]
mod headless_event_pump {
    pub fn pump() {
        // Linux (X11) 下，global-hotkey 内部已经 spawn 了专门的线程去跑 XNextEvent 循环，
        // 并将事件推送到 crossbeam channel。我们这里不需要额外的系统级 pump。
    }
}

// 用于主线程与消息泵线程通信的指令
#[allow(dead_code)]
enum HeadlessCmd {
    Register(HotKey, u32, String, u64, String), // hotkey, os_id, plugin_id, internal_id, shortcut_text
    Stop,
}

/// TCP control-channel transport for cross-device plugin messages.
/// The tcp_server registers the active client's message sender here; the bus
/// pushes wire messages through it. Only one device session is active at a
/// time in MicYou's model, so a single slot suffices.
pub struct TcpPluginSyncAdapter {
    sender: Mutex<Option<tokio::sync::mpsc::Sender<micyou_protocol::micyou::MessageWrapper>>>,
}

impl TcpPluginSyncAdapter {
    pub fn new() -> Self {
        Self {
            sender: Mutex::new(None),
        }
    }

    /// Register the active client's control sender (or clear on disconnect).
    pub fn set_sender(
        &self,
        sender: Option<tokio::sync::mpsc::Sender<micyou_protocol::micyou::MessageWrapper>>,
    ) {
        if let Ok(mut slot) = self.sender.lock() {
            *slot = sender;
        }
    }

    /// Clear the sender only when it is still ours (avoids nuking a newer
    /// client's slot during a takeover race).
    pub fn clear_if(
        &self,
        tx: &tokio::sync::mpsc::Sender<micyou_protocol::micyou::MessageWrapper>,
    ) {
        if let Ok(mut slot) = self.sender.lock() {
            if slot.as_ref().is_some_and(|s| s.same_channel(tx)) {
                *slot = None;
            }
        }
    }
}

impl Default for TcpPluginSyncAdapter {
    fn default() -> Self {
        Self::new()
    }
}

impl PluginSyncTransport for TcpPluginSyncAdapter {
    fn send(&self, msg: &PluginMessage) -> micyou_plugin::PluginResult<()> {
        let slot = self
            .sender
            .lock()
            .map_err(|_| micyou_plugin::PluginError::Runtime("sync sender poisoned".into()))?;
        let Some(tx) = slot.as_ref() else {
            return Err(micyou_plugin::PluginError::MessageDelivery(
                "no device connected".into(),
            ));
        };
        let wire = micyou_plugin::sync::to_wire(msg);
        let wrapper = micyou_protocol::micyou::MessageWrapper {
            audio_packet: None,
            connect: None,
            mute: None,
            ping: None,
            pong: None,
            plugin_message: Some(wire),
        };
        tx.try_send(wrapper)
            .map_err(|e| micyou_plugin::PluginError::MessageDelivery(e.to_string()))?;
        Ok(())
    }

    fn is_connected(&self) -> bool {
        self.sender.lock().map(|g| g.is_some()).unwrap_or(false)
    }
}

/// Control plane callbacks wired from the host server.
#[derive(Clone, Default)]
pub struct ControlPlaneHandlers {
    pub get_muted: Option<Arc<dyn Fn() -> micyou_plugin::PluginResult<bool> + Send + Sync>>,
    pub set_muted: Option<Arc<dyn Fn(bool) -> micyou_plugin::PluginResult<()> + Send + Sync>>,
    pub get_monitoring: Option<Arc<dyn Fn() -> micyou_plugin::PluginResult<bool> + Send + Sync>>,
    pub set_monitoring: Option<Arc<dyn Fn(bool) -> micyou_plugin::PluginResult<()> + Send + Sync>>,
    pub get_dsp_settings: Option<Arc<dyn Fn() -> micyou_plugin::PluginResult<String> + Send + Sync>>,
    pub set_dsp_settings: Option<Arc<dyn Fn(&str) -> micyou_plugin::PluginResult<()> + Send + Sync>>,
}

/// Runtime plugin host. One instance per process, managed Tauri state.
pub struct PluginHost {
    /// Plugin manager (scan/load/enable). Interior-mutable so the message-bus
    /// dispatcher and the commands can share it.
    pub manager: Arc<Mutex<micyou_plugin::PluginManager>>,
    pub dsp_registry: Arc<micyou_plugin::PluginDspRegistry>,
    pub sync: Arc<TcpPluginSyncAdapter>,
    /// Local + cross-device message bus (RPC / pub-sub).
    pub bus: Arc<PluginBus>,
    /// Bounded per-plugin log buffers (read by the frontend).
    pub logs: Arc<PluginLogs>,
    /// WAV playback for the `audio.play` capability (soundpads etc).
    /// Effects are mixed into the virtual microphone output stream.
    pub sound: Arc<crate::sound_player::SoundPlayer>,
    /// Global hotkey registry (plugin shortcut capability).
    pub hotkeys: Arc<HotkeyService>,
    /// Opens plugin panels in independent windows (plugin-driven).
    pub window: Arc<WindowService>,
    /// Dynamic sidebar-panel icons set by plugins via `set_panel_icon`.
    /// Map: plugin id -> (panel id -> icon string).
    pub panel_icons: Arc<
        std::sync::Mutex<
            std::collections::HashMap<String, std::collections::HashMap<String, String>>,
        >,
    >,
    pub control_handlers: Arc<Mutex<ControlPlaneHandlers>>,
}

/// Global hotkey registration for plugins.
/// The tauri plugin must be initialized at startup; `init` stores the app
/// handle, after which plugins can register shortcuts. Pressing a hotkey
/// delivers a bus message to the owning plugin on topic `hotkey:<id>`.
pub struct HotkeyService {
    handle: Mutex<Option<tauri::AppHandle>>,
    next_id: AtomicU64,
    registered: Mutex<std::collections::HashMap<u64, String>>,
    // --- Headless (CLI/TUI) fallback fields ---
    bus: Option<Arc<PluginBus>>,
    headless_hotkeys: Arc<Mutex<std::collections::HashMap<u32, (String, u64, String)>>>,
    headless_tx: Mutex<Option<std::sync::mpsc::Sender<HeadlessCmd>>>,
}

impl HotkeyService {
    pub fn new(bus: Arc<PluginBus>) -> Arc<Self> {
        Arc::new(Self {
            handle: Mutex::new(None),
            next_id: AtomicU64::new(1),
            registered: Mutex::new(std::collections::HashMap::new()),
            bus: Some(bus),
            headless_hotkeys: Arc::new(Mutex::new(std::collections::HashMap::new())),
            headless_tx: Mutex::new(None),
        })
    }

    /// Store the app handle (called from the Tauri setup hook)
    pub fn init(&self, app: &tauri::AppHandle) {
        if let Ok(mut slot) = self.handle.lock() {
            *slot = Some(app.clone());
        }
    }

    /// Register a global hotkey for a plugin; returns the handle id
    pub fn register(&self, plugin_id: &str, shortcut: &str) -> PluginResult<u64> {
        // global-hotkey only has an X11 backend on Linux; under Wayland the
        // compositor owns key handling and X11 grab keys (XGrabKey) via
        // XWayland are ignored (niri/wlroots behave this way), so a
        // registration would silently never fire. Fail loudly instead.
        let session = std::env::var("XDG_SESSION_TYPE").unwrap_or_default();
        if session == "wayland" || std::env::var("WAYLAND_DISPLAY").is_ok() {
            return Err(PluginError::Runtime(format!(
                "global hotkey unavailable on Wayland (X11-only backend); use the plugin panel buttons instead (plugin {plugin_id})"
            )));
        }
        
        let id = self.next_id.fetch_add(1, Ordering::Relaxed);
        let maybe_app = self.handle.lock().map_err(lock_err)?.clone();

        if let Some(app) = maybe_app {
            // === GUI Mode: Use Tauri Plugin ===
            let wrapper: ShortcutWrapper = shortcut
                .try_into()
                .map_err(|_| PluginError::Validation(format!("invalid hotkey: {shortcut}")))?;
            let pid = plugin_id.to_string();
            let shortcut_text = shortcut.to_string();
            let pid_closure = pid.clone();
            let id_closure = id;
            app.global_shortcut()
                .on_shortcut(wrapper, move |app, _sc, event| {
                    if event.state != ShortcutState::Pressed {
                        return;
                    }
                    let Some(state) = app.try_state::<crate::server::ServerState>() else {
                        return;
                    };
                    let msg = PluginMessage::new(
                        "host",
                        &pid_closure,
                        &format!("hotkey:{id_closure}"),
                        serde_json::json!({ "shortcut": shortcut_text })
                            .to_string()
                            .into_bytes(),
                    );
                    state.plugins.bus.handle_incoming(&msg);
                })
                .map_err(|e| PluginError::Runtime(format!("hotkey register: {e}")))?;
        } else {
            // === Headless Mode (CLI/TUI): 使用独立的消息泵线程 ===
            let mut tx_slot = self.headless_tx.lock().unwrap();
            if tx_slot.is_none() {
                let (tx, rx) = std::sync::mpsc::channel();
                *tx_slot = Some(tx.clone());
                
                let bus = self.bus.clone().unwrap();
                let hotkeys_map = self.headless_hotkeys.clone();
                
                // 启动专门的“消息泵 + 事件监听”后台线程
                std::thread::spawn(move || {
                    // 1. 在该线程内创建 Manager (这会创建隐藏窗口/注册底层 Hook)
                    let mgr = match GlobalHotKeyManager::new() {
                        Ok(m) => m,
                        Err(e) => {
                            log::error!("[plugins] headless hotkey manager init failed: {e}");
                            return;
                        }
                    };
                    
                    // 2. 核心跨平台消息循环
                    loop {
                        // A. 跨平台消息泵 (Windows: 派发 WM_HOTKEY; macOS: 跑 CFRunLoop; Linux: 空操作)
                        headless_event_pump::pump();
                        
                        // B. 处理来自外部的注册指令
                        match rx.try_recv() {
                            Ok(HeadlessCmd::Register(hotkey, os_id, pid, internal_id, shortcut_text)) => {
                                if let Err(e) = mgr.register(hotkey) {
                                    log::warn!("[plugins] headless hotkey register failed: {e}");
                                } else if let Ok(mut map) = hotkeys_map.lock() {
                                    map.insert(os_id, (pid, internal_id, shortcut_text));
                                }
                            }
                            Ok(HeadlessCmd::Stop) => break,
                            Err(_) => {} // 无指令
                        }
                        
                        // C. 检查全局事件 channel 
                        if let Ok(event) = GlobalHotKeyEvent::receiver().try_recv() {
                            if event.state() == global_hotkey::HotKeyState::Pressed {
                                let hotkey_id = event.id();
                                if let Ok(map) = hotkeys_map.lock() {
                                    if let Some((pid, internal_id, shortcut_text)) = map.get(&hotkey_id) {
                                        let msg = PluginMessage::new(
                                            "host",
                                            pid,
                                            &format!("hotkey:{internal_id}"),
                                            serde_json::json!({ "shortcut": shortcut_text }).to_string().into_bytes(),
                                        );
                                        let _ = bus.handle_incoming(&msg);
                                    }
                                }
                            }
                        }
                        
                        // 避免空转烧 CPU (Linux 下主要靠这个 sleep，Win/Mac 的 pump 也会消耗少量时间)
                        std::thread::sleep(std::time::Duration::from_millis(10));
                    }
                });
            }

            let tx = tx_slot.as_ref().unwrap().clone();
            let hotkey: HotKey = shortcut.try_into()
                .map_err(|_| PluginError::Validation(format!("invalid hotkey: {shortcut}")))?;
            
            let os_id = hotkey.id();
            // 将注册指令发送给后台线程
            tx.send(HeadlessCmd::Register(
                hotkey, 
                os_id, 
                plugin_id.to_string(), 
                id, 
                shortcut.to_string()
            )).map_err(|_| PluginError::Runtime("headless hotkey thread dead".into()))?;
        }

        self.registered.lock().map_err(lock_err)?.insert(id, plugin_id.to_string());
        Ok(id)
    }

    /// Number of registered hotkeys (for sync status / debugging)
    pub fn count(&self) -> usize {
        self.registered.lock().map(|m| m.len()).unwrap_or(0)
    }
}

/// Opens plugin panels in independent Tauri windows (Host API `open_window`)
pub struct WindowService {
    handle: Mutex<Option<tauri::AppHandle>>,
}

impl WindowService {
    pub fn new() -> Arc<Self> {
        Arc::new(Self {
            handle: Mutex::new(None),
        })
    }

    pub fn init(&self, app: &tauri::AppHandle) {
        if let Ok(mut slot) = self.handle.lock() {
            *slot = Some(app.clone());
        }
    }

    pub fn open_panel(&self, plugin_id: &str, panel_id: &str) -> PluginResult<()> {
        let app = self
            .handle
            .lock()
            .map_err(lock_err)?
            .clone()
            // Strictly fail with a clear message in CLI/TUI mode as documented
            .ok_or_else(|| PluginError::Runtime("open_window is not supported in headless (CLI/TUI) mode".into()))?;
        crate::commands::plugins::open_plugin_window_impl(&app, plugin_id, panel_id)
            .map_err(PluginError::Runtime)
    }
}

/// Default chain position for the synthetic plugin node: right after AEC,
/// so plugin processing runs on echo-cancelled audio.
pub const PLUGIN_NODE_AFTER: &str = "AEC";

impl PluginHost {
    pub fn new(output: Arc<crate::audio_output::AudioOutputHandle>) -> Self {
        let config = crate::app_config::config_dir();
        let manager = Arc::new(Mutex::new(micyou_plugin::PluginManager::new(
            config.join("plugins"),
            config.join("plugin-state.json"),
        )));
        let dsp_registry = Arc::new(micyou_plugin::PluginDspRegistry::new());
        let sync = Arc::new(TcpPluginSyncAdapter::new());

        // Route incoming/request messages to local plugin instances.
        let manager_dispatch = manager.clone();
        let dispatcher: Arc<
            dyn Fn(&PluginMessage) -> micyou_plugin::PluginResult<()> + Send + Sync,
        > = Arc::new(move |msg: &PluginMessage| {
            let targets: Vec<String> = {
                let manager = manager_dispatch
                    .lock()
                    .map_err(|_| micyou_plugin::PluginError::Runtime("manager poisoned".into()))?;
                if msg.target.is_empty() {
                    manager.loaded_ids()
                } else {
                    vec![msg.target.clone()]
                }
            };
            for id in targets {
                let handle = {
                    let manager = manager_dispatch.lock().map_err(|_| {
                        micyou_plugin::PluginError::Runtime("manager poisoned".into())
                    })?;
                    match manager.instance_handle(&id)? {
                        Some(h) => h,
                        None => continue,
                    }
                };
                let Ok(mut instance) = handle.try_lock() else {
                    log::warn!("[plugins] skip message for busy instance {id}");
                    continue;
                };
                instance.handle_message(&msg.source, &msg.topic, &msg.payload)?;
            }
            Ok(())
        });

        let bus = Arc::new(PluginBus::new(sync.clone(), dispatcher));
        let logs = Arc::new(PluginLogs::new());
        let sound = crate::sound_player::SoundPlayer::new(output);
        // Pass bus to HotkeyService for headless fallback routing
        let hotkeys = HotkeyService::new(bus.clone());
        let window = WindowService::new();

        Self {
            manager,
            dsp_registry,
            sync,
            bus,
            logs,
            sound,
            hotkeys,
            window,
            panel_icons: Arc::new(std::sync::Mutex::new(std::collections::HashMap::new())),
            control_handlers: Arc::new(Mutex::new(ControlPlaneHandlers::default())),
        }
    }

    /// Register control plane callbacks from the host server.
    pub fn set_control_handlers(&self, handlers: ControlPlaneHandlers) {
        if let Ok(mut slot) = self.control_handlers.lock() {
            *slot = handlers;
        }
    }

    /// Register a callback to set the host mute state (backwards compatibility).
    pub fn set_mute_handler(
        &self,
        handler: Arc<dyn Fn(bool) -> micyou_plugin::PluginResult<()> + Send + Sync>,
    ) {
        if let Ok(mut slot) = self.control_handlers.lock() {
            slot.set_muted = Some(handler);
        }
    }

    /// Scan plugins directory and enable all plugins marked enabled in state.
    pub fn load_saved_plugins(&self) {
        let report = self
            .manager
            .lock()
            .map(|mut m| m.scan())
            .unwrap_or_else(|_| Ok(micyou_plugin::ScanReport::default()));
        match report {
            Ok(report) => {
                for entry in report.discovered {
                    if entry.state.is_enabled() {
                        if let Err(e) = self.enable_plugin(&entry.manifest.id) {
                            log::warn!(
                                "[plugins] failed to start {}: {e}",
                                entry.manifest.id
                            );
                        }
                    }
                }
            }
            Err(e) => log::warn!("[plugins] scan failed: {e}"),
        }
    }

    /// Deliver a UI-triggered action to a plugin instance as a bus message on
    /// topic `ui:<action>` with the given payload (soundpad buttons etc).
    /// The plugin receives it through its `handle_message` entry.
    pub fn trigger(&self, plugin_id: &str, action: &str, payload: &[u8]) -> PluginResult<()> {
        let bytes = if payload.is_empty() {
            format!(r#"{{"action":"{action}"}}"#).into_bytes()
        } else {
            payload.to_vec()
        };
        let msg = PluginMessage::new("ui", plugin_id, &format!("ui:{action}"), bytes);
        self.bus.handle_incoming(&msg);
        Ok(())
    }

    /// Load + start one plugin: instantiate the runtime, init it, register the
    /// instance and (for DSP plugins) its processing node.
    /// Verify every declared plugin dependency is installed, enabled and
    /// version-satisfied. Returns the first unmet dependency as an error.
    pub fn check_dependencies(&self, manifest: &micyou_plugin::PluginManifest) -> PluginResult<()> {
        for dep in &manifest.dependencies {
            if dep.optional {
                continue;
            }
            let manager = self.manager.lock().map_err(lock_err)?;
            let Some(entry) = manager.entry(&dep.id)? else {
                return Err(PluginError::Runtime(format!(
                    "dependency {} is not installed (required by {})",
                    dep.id, manifest.id
                )));
            };
            if !entry.state.is_enabled() {
                return Err(PluginError::Runtime(format!(
                    "dependency {} is disabled (enable it first, required by {})",
                    dep.id, manifest.id
                )));
            }
            if !dep.version.is_empty() {
                let req = semver::VersionReq::parse(&dep.version)
                    .map_err(|e| PluginError::Runtime(format!("invalid version req: {e}")))?;
                let installed = semver::Version::parse(&entry.manifest.version)
                    .map_err(|e| PluginError::Runtime(format!("dep version parse: {e}")))?;
                if !req.matches(&installed) {
                    return Err(PluginError::Runtime(format!(
                        "dependency {} version {} does not satisfy {} (required by {})",
                        dep.id, entry.manifest.version, dep.version, manifest.id
                    )));
                }
            }
        }
        Ok(())
    }

    pub fn enable_plugin(&self, id: &str) -> PluginResult<()> {
        let entry = {
            let manager = self.manager.lock().map_err(lock_err)?;
            if manager.is_loaded(id) {
                return Ok(()); // already running
            }
            manager
                .entry(id)?
                .ok_or_else(|| PluginError::UnknownPlugin(id.to_string()))?
        };
        self.check_dependencies(&entry.manifest)?;

        let host_api: Arc<dyn HostApi> = PluginHostApi::new(
            self.bus.clone(),
            self.manager.clone(),
            self.logs.clone(),
            self.sound.clone(),
            self.hotkeys.clone(),
            self.window.clone(),
            self.panel_icons.clone(),
            self.control_handlers.clone(),
            id.to_string(),
            entry.dir.clone(),
        );
        let mut instance = match entry.manifest.runtime {
            RuntimeKind::Native => micyou_plugin::native::load_native_instance(
                entry.manifest.clone(),
                &entry.dir,
                host_api.clone(),
            )?,
            RuntimeKind::Wasm => micyou_plugin::wasm::load_wasm_instance(
                entry.manifest.clone(),
                &entry.dir,
                host_api.clone(),
            )?,
        };
        instance.init(&*host_api)?;

        let dsp_handle = {
            let mut manager = self.manager.lock().map_err(lock_err)?;
            manager.set_enabled(id, true)?;
            manager.register_instance(instance)?;
            manager.instance_handle(id)?
        };

        if entry.manifest.kind == PluginKind::Dsp {
            let dsp = entry.manifest.dsp.clone().unwrap_or_default();
            let handle = dsp_handle.ok_or_else(|| PluginError::NotLoaded(id.to_string()))?;
            self.dsp_registry.register(micyou_plugin::DspNode {
                plugin_id: id.to_string(),
                first: dsp.first,
                insert_after: dsp.insert_after.clone(),
                instance: handle,
            })?;
        }
        log::info!("[plugins] enabled {id}");
        Ok(())
    }

    /// Stop + unload a plugin (deinit, remove DSP node, persist disabled).
    pub fn disable_plugin(&self, id: &str) -> PluginResult<()> {
        self.dsp_registry.unregister(id)?;
        let mut manager = self.manager.lock().map_err(lock_err)?;
        manager.unregister_instance(id)?;
        manager.set_enabled(id, false)?;
        log::info!("[plugins] disabled {id}");
        Ok(())
    }

    /// Deliver a host lifecycle event (device connected/disconnected, ...) to
    /// every loaded plugin. Short-locks the manager only to collect instance
    /// handles, then try_locks each instance so a busy plugin (e.g. the audio
    /// thread) is skipped instead of blocking.
    pub fn broadcast_event(&self, event: &micyou_plugin::PluginEvent) {
        let handles = {
            let Ok(manager) = self.manager.lock() else {
                return;
            };
            manager
                .loaded_ids()
                .into_iter()
                .filter_map(|id| manager.instance_handle(&id).ok().flatten())
                .collect::<Vec<_>>()
        };
        for handle in handles {
            if let Ok(mut inst) = handle.try_lock() {
                let _ = inst.handle_event(event);
            }
        }
    }

    /// Uninstall: disable, remove from registry and delete the directory.
    pub fn uninstall_plugin(&self, id: &str) -> PluginResult<()> {
        self.dsp_registry.unregister(id)?;
        let mut manager = self.manager.lock().map_err(lock_err)?;
        manager.uninstall(id)?;
        log::info!("[plugins] uninstalled {id}");
        Ok(())
    }

    /// Ensure the synthetic `"Plugins"` node exists in the processing chain
    /// (right after AEC) when at least one DSP plugin is registered. This is
    /// an in-memory settings change; the user can reorder it in the GUI like
    /// any other chain node.
    pub fn ensure_plugin_chain_node(
        &self,
        dsp_settings: &Arc<RwLock<micyou_audio::dsp::AudioDspSettings>>,
    ) {
        if !self.dsp_registry.is_active() {
            return;
        }
        if let Ok(mut settings) = dsp_settings.write() {
            let chain = &mut settings.processing_chain;
            if chain
                .iter()
                .any(|n| n == micyou_audio::dsp::PLUGIN_CHAIN_NODE)
            {
                return;
            }
            match chain.iter().position(|n| n == PLUGIN_NODE_AFTER) {
                Some(idx) => {
                    chain.insert(idx + 1, micyou_audio::dsp::PLUGIN_CHAIN_NODE.to_string());
                }
                None => chain.push(micyou_audio::dsp::PLUGIN_CHAIN_NODE.to_string()),
            }
        }
    }

    /// Build the external DSP hook for `DspProcessor`. Cheap no-op when no
    /// DSP plugin is registered (see `PluginDspBridge::hook`).
    pub fn dsp_hook(&self) -> Option<micyou_audio::dsp::ExternalDspHook> {
        let bridge = micyou_plugin::PluginDspBridge::new(self.dsp_registry.clone());
        Some(bridge.hook())
    }
}

fn lock_err<T>(_: std::sync::PoisonError<T>) -> PluginError {
    PluginError::Runtime("plugin host lock poisoned".into())
}

// ── Per-plugin log buffers ─────────────────────────────────────────────────

/// Bounded ring of log lines per plugin, readable by the frontend.
pub struct PluginLogs {
    buffers: Mutex<HashMap<String, VecDeque<String>>>,
    cap: usize,
}

impl Default for PluginLogs {
    fn default() -> Self {
        Self::new()
    }
}

impl PluginLogs {
    pub fn new() -> Self {
        Self {
            buffers: Mutex::new(HashMap::new()),
            cap: 500,
        }
    }

    pub fn push(&self, plugin_id: &str, level: PluginLogLevel, message: &str) {
        let line = format!("[{}] {message}", level_label(level));
        if let Ok(mut buffers) = self.buffers.lock() {
            let queue = buffers.entry(plugin_id.to_string()).or_default();
            if queue.len() >= self.cap {
                queue.pop_front();
            }
            queue.push_back(line);
        }
    }

    pub fn lines(&self, plugin_id: &str) -> Vec<String> {
        self.buffers
            .lock()
            .map(|b| {
                b.get(plugin_id)
                    .map(|q| q.iter().cloned().collect())
                    .unwrap_or_default()
            })
            .unwrap_or_default()
    }

    pub fn clear(&self, plugin_id: &str) {
        if let Ok(mut buffers) = self.buffers.lock() {
            buffers.remove(plugin_id);
        }
    }
}

fn level_label(level: PluginLogLevel) -> &'static str {
    match level {
        PluginLogLevel::Error => "ERROR",
        PluginLogLevel::Warn => "WARN",
        PluginLogLevel::Info => "INFO",
        PluginLogLevel::Debug => "DEBUG",
        PluginLogLevel::Trace => "TRACE",
    }
}

// ── Real HostApi for plugin instances ──────────────────────────────────────

/// HostApi implementation backed by the plugin manager, the bus and the log
/// buffers. One instance per plugin; capabilities come from the manifest.
pub struct PluginHostApi {
    bus: Arc<PluginBus>,
    manager: Arc<Mutex<micyou_plugin::PluginManager>>,
    logs: Arc<PluginLogs>,
    sound: Arc<crate::sound_player::SoundPlayer>,
    hotkeys: Arc<HotkeyService>,
    window: Arc<WindowService>,
    plugin_id: String,
    dir: std::path::PathBuf,
    panel_icons: Arc<
        std::sync::Mutex<
            std::collections::HashMap<String, std::collections::HashMap<String, String>>,
        >,
    >,
    control_handlers: Arc<Mutex<ControlPlaneHandlers>>,
    timer_next: std::sync::atomic::AtomicU64,
    timers: std::sync::Mutex<
        std::collections::HashMap<u64, std::sync::Arc<std::sync::atomic::AtomicBool>>,
    >,
    http_next: std::sync::atomic::AtomicU64,
}

impl PluginHostApi {
    pub fn new(
        bus: Arc<PluginBus>,
        manager: Arc<Mutex<micyou_plugin::PluginManager>>,
        logs: Arc<PluginLogs>,
        sound: Arc<crate::sound_player::SoundPlayer>,
        hotkeys: Arc<HotkeyService>,
        window: Arc<WindowService>,
        panel_icons: Arc<
            std::sync::Mutex<
                std::collections::HashMap<String, std::collections::HashMap<String, String>>,
            >,
        >,
        control_handlers: Arc<Mutex<ControlPlaneHandlers>>,
        plugin_id: String,
        dir: std::path::PathBuf,
    ) -> Arc<Self> {
        Arc::new(Self {
            bus,
            manager,
            logs,
            sound,
            hotkeys,
            window,
            panel_icons,
            control_handlers,
            plugin_id,
            dir,
            timer_next: std::sync::atomic::AtomicU64::new(1),
            timers: std::sync::Mutex::new(std::collections::HashMap::new()),
            http_next: std::sync::atomic::AtomicU64::new(1),
        })
    }
}

impl HostApi for PluginHostApi {
    fn log(&self, level: PluginLogLevel, message: &str) {
        self.logs.push(&self.plugin_id, level, message);
        log::info!(target: "plugin", "[{}] {}", self.plugin_id, message);
    }

    fn get_config(&self, key: &str) -> Option<serde_json::Value> {
        let manager = self.manager.lock().ok()?;
        manager
            .plugin_config(&self.plugin_id)
            .ok()?
            .get(key)
            .cloned()
    }

    fn set_config(&self, key: &str, value: serde_json::Value) -> PluginResult<()> {
        let manager = self.manager.lock().map_err(lock_err)?;
        manager.set_plugin_config(&self.plugin_id, key, value)
    }

    fn emit_event(&self, topic: &str, payload: serde_json::Value) -> PluginResult<()> {
        let bytes = serde_json::to_vec(&payload)
            .map_err(|e| PluginError::Runtime(format!("event serialization: {e}")))?;
        self.bus.publish(topic, bytes)
    }

    fn send_message(&self, target: MessageTarget, payload: Vec<u8>) -> PluginResult<()> {
        match target {
            MessageTarget::Local { plugin_id } => {
                let msg = PluginMessage::new(&self.plugin_id, &plugin_id, &plugin_id, payload);
                self.bus.handle_incoming(&msg);
                Ok(())
            }
            MessageTarget::Remote { plugin_id } => {
                let msg = PluginMessage::new(&self.plugin_id, &plugin_id, &plugin_id, payload);
                self.bus.transport().send(&msg)
            }
            MessageTarget::Broadcast => {
                let msg = PluginMessage::new(&self.plugin_id, "", "broadcast", payload);
                self.bus.handle_incoming(&msg);
                if self.bus.transport().is_connected() {
                    self.bus.transport().send(&msg)?;
                }
                Ok(())
            }
        }
    }

    fn audio_state(&self) -> AudioStateSnapshot {
        // Real-time audio state is wired by the app through the bus topics;
        // the snapshot defaults are safe for plugins that only read config.
        AudioStateSnapshot::default()
    }

    fn plugin_dir(&self) -> String {
        self.manager
            .lock()
            .ok()
            .and_then(|m| m.entry(&self.plugin_id).ok().flatten())
            .map(|e| e.dir.display().to_string())
            .unwrap_or_default()
    }

    fn register_hotkey(&self, shortcut: &str) -> PluginResult<u64> {
        self.hotkeys.register(&self.plugin_id, shortcut)
    }

    fn open_window(&self, panel_id: &str) -> PluginResult<()> {
        self.window.open_panel(&self.plugin_id, panel_id)
    }

    fn play_sound(&self, path: &str) -> PluginResult<()> {
        let full = if std::path::Path::new(path).is_absolute() {
            path.to_string()
        } else {
            self.dir.join(path).display().to_string()
        };
        self.sound.play_wav(&full)
    }

    fn fs_read(&self, path: &str) -> PluginResult<String> {
        let full = micyou_plugin::sandbox_path(&self.dir, path)?;
        std::fs::read_to_string(&full).map_err(PluginError::from)
    }

    fn fs_write(&self, path: &str, content: &str) -> PluginResult<()> {
        let full = micyou_plugin::sandbox_path(&self.dir, path)?;
        if let Some(parent) = full.parent() {
            std::fs::create_dir_all(parent)
                .map_err(|e| PluginError::Runtime(format!("fs_write mkdir: {e}")))?;
        }
        std::fs::write(&full, content).map_err(PluginError::from)
    }

    fn set_timeout(&self, ms: u64, payload: &str) -> PluginResult<u64> {
        use std::sync::atomic::{AtomicBool, Ordering};
        let id = self.timer_next.fetch_add(1, Ordering::Relaxed);
        let cancel = Arc::new(AtomicBool::new(false));
        self.timers
            .lock()
            .map_err(lock_err)?
            .insert(id, cancel.clone());
        let bus = self.bus.clone();
        let pid = self.plugin_id.clone();
        let payload = payload.to_string();
        std::thread::spawn(move || {
            std::thread::sleep(std::time::Duration::from_millis(ms));
            if cancel.load(Ordering::Relaxed) {
                return;
            }
            let msg = PluginMessage::new(
                "host",
                &pid,
                "timer:expired",
                serde_json::json!({ "timer": id, "payload": payload })
                    .to_string()
                    .into_bytes(),
            );
            bus.handle_incoming(&msg);
        });
        Ok(id)
    }

    fn clear_timeout(&self, id: u64) -> PluginResult<()> {
        if let Some(cancel) = self.timers.lock().map_err(lock_err)?.remove(&id) {
            cancel.store(true, std::sync::atomic::Ordering::Relaxed);
        }
        Ok(())
    }

    fn http_request(
        &self,
        method: &str,
        url: &str,
        headers_json: &str,
        body: &str,
    ) -> PluginResult<u64> {
        use std::sync::atomic::Ordering;
        let id = self.http_next.fetch_add(1, Ordering::Relaxed);
        let bus = self.bus.clone();
        let pid = self.plugin_id.clone();
        let method = method.to_string();
        let url = url.to_string();
        let headers_json = headers_json.to_string();
        let body = body.to_string();
        std::thread::spawn(move || {
            let result = (|| -> Result<(u16, String), String> {
                let client = reqwest::blocking::Client::builder()
                    .timeout(std::time::Duration::from_secs(10))
                    .build()
                    .map_err(|e| e.to_string())?;
                let m =
                    reqwest::Method::from_bytes(method.as_bytes()).map_err(|e| e.to_string())?;
                let mut req = client.request(m, &url);
                if let Ok(headers) = serde_json::from_str::<
                    serde_json::Map<String, serde_json::Value>,
                >(&headers_json)
                {
                    for (k, v) in headers {
                        if let Some(vs) = v.as_str() {
                            req = req.header(&k, vs);
                        }
                    }
                }
                if !body.is_empty() {
                    req = req.body(body);
                }
                let resp = req.send().map_err(|e| e.to_string())?;
                let status = resp.status().as_u16();
                let text = resp.text().map_err(|e| e.to_string())?;
                Ok((status, text))
            })();
            let payload = match result {
                Ok((status, text)) => serde_json::json!({
                    "request": id, "ok": true, "status": status, "body": text, "error": null
                }),
                Err(e) => serde_json::json!({
                    "request": id, "ok": false, "status": 0, "body": "", "error": e
                }),
            };
            let msg = PluginMessage::new(
                "host",
                &pid,
                "http:response",
                payload.to_string().into_bytes(),
            );
            bus.handle_incoming(&msg);
        });
        Ok(id)
    }

    fn set_interval(&self, ms: u64, payload: &str) -> PluginResult<u64> {
        use std::sync::atomic::{AtomicBool, Ordering};
        let id = self.timer_next.fetch_add(1, Ordering::Relaxed);
        let cancel = Arc::new(AtomicBool::new(false));
        self.timers
            .lock()
            .map_err(lock_err)?
            .insert(id, cancel.clone());
        let bus = self.bus.clone();
        let pid = self.plugin_id.clone();
        let payload = payload.to_string();
        std::thread::spawn(move || loop {
            std::thread::sleep(std::time::Duration::from_millis(ms.max(1)));
            if cancel.load(Ordering::Relaxed) {
                return;
            }
            let msg = PluginMessage::new(
                "host",
                &pid,
                "interval:tick",
                serde_json::json!({ "interval": id, "payload": payload })
                    .to_string()
                    .into_bytes(),
            );
            bus.handle_incoming(&msg);
        });
        Ok(id)
    }

    fn clear_interval(&self, id: u64) -> PluginResult<()> {
        self.clear_timeout(id)
    }

    fn open_url(&self, url: &str) -> PluginResult<()> {
        let maybe_app = self
            .window
            .handle
            .lock()
            .map_err(lock_err)?
            .clone();

        if let Some(app) = maybe_app {
            // GUI Mode: Use Tauri Plugin
            use tauri_plugin_opener::OpenerExt;
            app.opener()
                .open_url(url, None::<&str>)
                .map_err(|e| PluginError::Runtime(format!("open_url: {e}")))?;
        } else {
            // Headless Mode (CLI/TUI): Use open crate
            ::open::that(url)
                .map_err(|e| PluginError::Runtime(format!("open_url (headless): {e}")))?;
        }
        Ok(())
    }

    fn notify(&self, title: &str, body: &str) -> PluginResult<()> {
        let maybe_app = self
            .window
            .handle
            .lock()
            .map_err(lock_err)?
            .clone();

        if let Some(app) = maybe_app {
            // GUI Mode: Use Tauri Plugin
            use tauri_plugin_notification::NotificationExt;
            app.notification()
                .builder()
                .title(title)
                .body(body)
                .show()
                .map_err(|e| PluginError::Runtime(format!("notify: {e}")))?;
        } else {
            // Headless Mode (CLI/TUI): Use notify-rust
            ::notify_rust::Notification::new()
                .summary(title)
                .body(body)
                .show()
                .map_err(|e| PluginError::Runtime(format!("notify (headless): {e}")))?;
        }
        Ok(())
    }

    fn locale(&self) -> String {
        crate::app_config::load_ui_prefs().language
    }

    fn host_info(&self) -> String {
        serde_json::json!({
            "name": "micyou",
            "version": env!("CARGO_PKG_VERSION"),
            "apiVersion": micyou_plugin::manifest::HOST_API_VERSION,
        })
        .to_string()
    }

    fn clipboard_read(&self) -> PluginResult<String> {
        let mut cb = arboard::Clipboard::new()
            .map_err(|e| PluginError::Runtime(format!("clipboard: {e}")))?;
        cb.get_text()
            .map_err(|e| PluginError::Runtime(format!("clipboard read: {e}")))
    }

    fn clipboard_write(&self, text: &str) -> PluginResult<()> {
        let mut cb = arboard::Clipboard::new()
            .map_err(|e| PluginError::Runtime(format!("clipboard: {e}")))?;
        cb.set_text(text.to_string())
            .map_err(|e| PluginError::Runtime(format!("clipboard write: {e}")))
    }

    fn set_panel_icon(&self, panel_id: &str, icon: &str) -> PluginResult<()> {
        if let Ok(mut map) = self.panel_icons.lock() {
            map.entry(self.plugin_id.clone())
                .or_default()
                .insert(panel_id.to_string(), icon.to_string());
        }
        Ok(())
    }

    fn get_muted(&self) -> PluginResult<bool> {
        let handlers = self.control_handlers.lock().map_err(lock_err)?;
        if let Some(handler) = handlers.get_muted.as_ref() {
            handler()
        } else {
            Err(PluginError::Runtime("get_muted handler not registered".into()))
        }
    }

    fn set_muted(&self, muted: bool) -> PluginResult<()> {
        let handlers = self.control_handlers.lock().map_err(lock_err)?;
        if let Some(handler) = handlers.set_muted.as_ref() {
            handler(muted)
        } else {
            Err(PluginError::Runtime("set_muted handler not registered".into()))
        }
    }

    fn get_monitoring(&self) -> PluginResult<bool> {
        let handlers = self.control_handlers.lock().map_err(lock_err)?;
        if let Some(handler) = handlers.get_monitoring.as_ref() {
            handler()
        } else {
            Err(PluginError::Runtime("get_monitoring handler not registered".into()))
        }
    }

    fn set_monitoring(&self, enabled: bool) -> PluginResult<()> {
        let handlers = self.control_handlers.lock().map_err(lock_err)?;
        if let Some(handler) = handlers.set_monitoring.as_ref() {
            handler(enabled)
        } else {
            Err(PluginError::Runtime("set_monitoring handler not registered".into()))
        }
    }

    fn get_dsp_settings(&self) -> PluginResult<String> {
        let handlers = self.control_handlers.lock().map_err(lock_err)?;
        if let Some(handler) = handlers.get_dsp_settings.as_ref() {
            handler()
        } else {
            Err(PluginError::Runtime("get_dsp_settings handler not registered".into()))
        }
    }

    fn set_dsp_settings(&self, settings_json: &str) -> PluginResult<()> {
        let handlers = self.control_handlers.lock().map_err(lock_err)?;
        if let Some(handler) = handlers.set_dsp_settings.as_ref() {
            handler(settings_json)
        } else {
            Err(PluginError::Runtime("set_dsp_settings handler not registered".into()))
        }
    }

    fn connected_devices(&self) -> Vec<DeviceSnapshot> {
        if self.bus.transport().is_connected() {
            vec![DeviceSnapshot {
                mode: "wifi".to_string(),
                label: "connected device".to_string(),
                audio_active: true,
            }]
        } else {
            Vec::new()
        }
    }
}

#[cfg(test)]
mod tests_e2e {
    use super::*;

    /// 端到端 API 回归测试：真实 PluginHost 链路 enable → trigger →
    /// bus → dispatcher → handle_message → host 回调（native 路径）。
    /// 环境需已安装 dev.micyou.example.soundpad。
    #[test]
    fn soundpad_trigger_end_to_end() {
        let output = crate::audio_output::AudioOutputHandle::spawn();
        let host = PluginHost::new(output);
        let id = "dev.micyou.example.soundpad";
        {
            let mut manager = host.manager.lock().unwrap();
            manager.scan().expect("scan plugins");
        }
        {
            let manager = host.manager.lock().unwrap();
            if manager.entry(id).unwrap().is_none() {
                eprintln!("[test] soundpad not installed, skipping");
                return;
            }
        }
        host.enable_plugin(id).expect("enable soundpad");
        host.trigger(id, "play", b"").expect("trigger play");
        std::thread::sleep(std::time::Duration::from_millis(200));
        let logs = host.logs.lines(id);
        let joined = logs.join("\n");
        assert!(
            joined.contains("play") || joined.contains("playing") || joined.contains("sound"),
            "soundpad must log playing; logs={joined:?}"
        );
        eprintln!("[test] SOUNDPAD E2E OK: trigger -> handle_message(topic ui:play) -> play_sound");
    }
}