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

use global_hotkey::{GlobalHotKeyManager, GlobalHotKeyEvent, hotkey::HotKey};

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
            CFRunLoopRunInMode(kCFRunLoopDefaultMode, 0.01, 0);
        }
    }
}

#[cfg(target_os = "linux")]
mod headless_event_pump {
    pub fn pump() {
    }
}

#[allow(dead_code)]
enum HeadlessCmd {
    Register(HotKey, u32, String, u64, String), 
    Stop,
}

pub struct TcpPluginSyncAdapter {
    sender: Mutex<Option<tokio::sync::mpsc::Sender<micyou_protocol::micyou::MessageWrapper>>>,
}

impl TcpPluginSyncAdapter {
    pub fn new() -> Self {
        Self {
            sender: Mutex::new(None),
        }
    }

    pub fn set_sender(
        &self,
        sender: Option<tokio::sync::mpsc::Sender<micyou_protocol::micyou::MessageWrapper>>,
    ) {
        if let Ok(mut slot) = self.sender.lock() {
            *slot = sender;
        }
    }

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

#[derive(Clone, Default)]
pub struct ControlPlaneHandlers {
    pub get_muted: Option<Arc<dyn Fn() -> micyou_plugin::PluginResult<bool> + Send + Sync>>,
    pub set_muted: Option<Arc<dyn Fn(bool) -> micyou_plugin::PluginResult<()> + Send + Sync>>,
    pub get_monitoring: Option<Arc<dyn Fn() -> micyou_plugin::PluginResult<bool> + Send + Sync>>,
    pub set_monitoring: Option<Arc<dyn Fn(bool) -> micyou_plugin::PluginResult<()> + Send + Sync>>,
    pub get_dsp_settings: Option<Arc<dyn Fn() -> micyou_plugin::PluginResult<String> + Send + Sync>>,
    pub set_dsp_settings: Option<Arc<dyn Fn(&str) -> micyou_plugin::PluginResult<()> + Send + Sync>>,
}

pub struct PluginHost {
    pub manager: Arc<Mutex<micyou_plugin::PluginManager>>,
    pub dsp_registry: Arc<micyou_plugin::PluginDspRegistry>,
    pub sync: Arc<TcpPluginSyncAdapter>,
    pub bus: Arc<PluginBus>,
    pub logs: Arc<PluginLogs>,
    pub sound: Arc<crate::sound_player::SoundPlayer>,
    pub hotkeys: Arc<HotkeyService>,
    pub window: Arc<WindowService>,
    pub panel_icons: Arc<
        std::sync::Mutex<
            std::collections::HashMap<String, std::collections::HashMap<String, String>>,
        >,
    >,
    pub control_handlers: Arc<Mutex<ControlPlaneHandlers>>,
    pub network_stats: Arc<crate::stats::NetworkStats>,
    pub audio_output: Arc<crate::audio_output::AudioOutputHandle>,
    pub active_connection: crate::tcp_server::SharedActiveConnection,
    pub active_audio_session: crate::udp_server::SharedActiveAudioSession,
    pub lifecycle: Arc<tokio::sync::Mutex<crate::server::ServerLifecycleState>>,
    #[cfg(feature = "web-server")]
    pub web_server: Arc<tokio::sync::Mutex<Option<crate::web_server::WebServer>>>,
}

pub struct HotkeyService {
    handle: Mutex<Option<tauri::AppHandle>>,
    next_id: AtomicU64,
    registered: Mutex<std::collections::HashMap<u64, String>>,
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

    pub fn init(&self, app: &tauri::AppHandle) {
        if let Ok(mut slot) = self.handle.lock() {
            *slot = Some(app.clone());
        }
    }

    pub fn register(&self, plugin_id: &str, shortcut: &str) -> PluginResult<u64> {
        let session = std::env::var("XDG_SESSION_TYPE").unwrap_or_default();
        if session == "wayland" || std::env::var("WAYLAND_DISPLAY").is_ok() {
            return Err(PluginError::Runtime(format!(
                "global hotkey unavailable on Wayland (X11-only backend); use the plugin panel buttons instead (plugin {plugin_id})"
            )));
        }
        
        let id = self.next_id.fetch_add(1, Ordering::Relaxed);
        let maybe_app = self.handle.lock().map_err(lock_err)?.clone();

        if let Some(app) = maybe_app {
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
            let mut tx_slot = self.headless_tx.lock().unwrap();
            if tx_slot.is_none() {
                let (tx, rx) = std::sync::mpsc::channel();
                *tx_slot = Some(tx.clone());
                
                let bus = self.bus.clone().unwrap();
                let hotkeys_map = self.headless_hotkeys.clone();
                
                std::thread::spawn(move || {
                    let mgr = match GlobalHotKeyManager::new() {
                        Ok(m) => m,
                        Err(e) => {
                            log::error!("[plugins] headless hotkey manager init failed: {e}");
                            return;
                        }
                    };
                    
                    loop {
                        headless_event_pump::pump();
                        
                        match rx.try_recv() {
                            Ok(HeadlessCmd::Register(hotkey, os_id, pid, internal_id, shortcut_text)) => {
                                if let Err(e) = mgr.register(hotkey) {
                                    log::warn!("[plugins] headless hotkey register failed: {e}");
                                } else if let Ok(mut map) = hotkeys_map.lock() {
                                    map.insert(os_id, (pid, internal_id, shortcut_text));
                                }
                            }
                            Ok(HeadlessCmd::Stop) => break,
                            Err(_) => {} 
                        }
                        
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
                        
                        std::thread::sleep(std::time::Duration::from_millis(10));
                    }
                });
            }

            let tx = tx_slot.as_ref().unwrap().clone();
            let hotkey: HotKey = shortcut.try_into()
                .map_err(|_| PluginError::Validation(format!("invalid hotkey: {shortcut}")))?;
            
            let os_id = hotkey.id();
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

    pub fn count(&self) -> usize {
        self.registered.lock().map(|m| m.len()).unwrap_or(0)
    }
}

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
            .ok_or_else(|| PluginError::Runtime("open_window is not supported in headless (CLI/TUI) mode".into()))?;
        crate::commands::plugins::open_plugin_window_impl(&app, plugin_id, panel_id)
            .map_err(PluginError::Runtime)
    }
}

pub const PLUGIN_NODE_AFTER: &str = "AEC";

impl PluginHost {
    pub fn new(
        output: Arc<crate::audio_output::AudioOutputHandle>,
        network_stats: Arc<crate::stats::NetworkStats>,
        active_connection: crate::tcp_server::SharedActiveConnection,
        active_audio_session: crate::udp_server::SharedActiveAudioSession,
        lifecycle: Arc<tokio::sync::Mutex<crate::server::ServerLifecycleState>>,
        #[cfg(feature = "web-server")] web_server: Arc<tokio::sync::Mutex<Option<crate::web_server::WebServer>>>,
    ) -> Self {
        let config = crate::app_config::config_dir();
        let manager = Arc::new(Mutex::new(micyou_plugin::PluginManager::new(
            config.join("plugins"),
            config.join("plugin-state.json"),
        )));
        let dsp_registry = Arc::new(micyou_plugin::PluginDspRegistry::new());
        let sync = Arc::new(TcpPluginSyncAdapter::new());

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
        let sound = crate::sound_player::SoundPlayer::new(output.clone());
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
            network_stats,
            audio_output: output,
            active_connection,
            active_audio_session,
            lifecycle,
            #[cfg(feature = "web-server")]
            web_server,
        }
    }

    pub fn set_control_handlers(&self, handlers: ControlPlaneHandlers) {
        if let Ok(mut slot) = self.control_handlers.lock() {
            *slot = handlers;
        }
    }

    pub fn set_mute_handler(
        &self,
        handler: Arc<dyn Fn(bool) -> micyou_plugin::PluginResult<()> + Send + Sync>,
    ) {
        if let Ok(mut slot) = self.control_handlers.lock() {
            slot.set_muted = Some(handler);
        }
    }

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
                return Ok(()); 
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
            self.network_stats.clone(),
            self.audio_output.clone(),
            self.active_connection.clone(),
            self.active_audio_session.clone(),
            self.lifecycle.clone(),
            #[cfg(feature = "web-server")] self.web_server.clone(),
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

    pub fn disable_plugin(&self, id: &str) -> PluginResult<()> {
        self.dsp_registry.unregister(id)?;
        let mut manager = self.manager.lock().map_err(lock_err)?;
        manager.unregister_instance(id)?;
        manager.set_enabled(id, false)?;
        log::info!("[plugins] disabled {id}");
        Ok(())
    }

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

    pub fn uninstall_plugin(&self, id: &str) -> PluginResult<()> {
        self.dsp_registry.unregister(id)?;
        let mut manager = self.manager.lock().map_err(lock_err)?;
        manager.uninstall(id)?;
        log::info!("[plugins] uninstalled {id}");
        Ok(())
    }

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

    pub fn dsp_hook(&self) -> Option<micyou_audio::dsp::ExternalDspHook> {
        let bridge = micyou_plugin::PluginDspBridge::new(self.dsp_registry.clone());
        Some(bridge.hook())
    }
}

fn lock_err<T>(_: std::sync::PoisonError<T>) -> PluginError {
    PluginError::Runtime("plugin host lock poisoned".into())
}

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
    network_stats: Arc<crate::stats::NetworkStats>,
    audio_output: Arc<crate::audio_output::AudioOutputHandle>,
    active_connection: crate::tcp_server::SharedActiveConnection,
    active_audio_session: crate::udp_server::SharedActiveAudioSession,
    lifecycle: Arc<tokio::sync::Mutex<crate::server::ServerLifecycleState>>,
    #[cfg(feature = "web-server")]
    web_server: Arc<tokio::sync::Mutex<Option<crate::web_server::WebServer>>>,
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
        network_stats: Arc<crate::stats::NetworkStats>,
        audio_output: Arc<crate::audio_output::AudioOutputHandle>,
        active_connection: crate::tcp_server::SharedActiveConnection,
        active_audio_session: crate::udp_server::SharedActiveAudioSession,
        lifecycle: Arc<tokio::sync::Mutex<crate::server::ServerLifecycleState>>,
        #[cfg(feature = "web-server")] web_server: Arc<tokio::sync::Mutex<Option<crate::web_server::WebServer>>>,
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
            network_stats,
            audio_output,
            active_connection,
            active_audio_session,
            lifecycle,
            #[cfg(feature = "web-server")]
            web_server,
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
        let is_server_running = match self.lifecycle.try_lock() {
            Ok(lifecycle) => matches!(lifecycle.phase(), crate::server::ServerLifecyclePhase::Running),
            Err(_) => false,
        };
        let is_connected = {
            let conn_active = match self.active_connection.try_lock() {
                Ok(lock) => lock.is_some(),
                Err(_) => false,
            };
            let audio_active = self.active_audio_session.read()
                .map(|s| !matches!(*s, crate::udp_server::ActiveAudioSession::Inactive))
                .unwrap_or(false);
            let mut connected = conn_active || audio_active;
            #[cfg(feature = "web-server")]
            if !connected {
                if let Ok(web_lock) = self.web_server.try_lock() {
                    connected = web_lock.as_ref().is_some_and(|w| w.client_count() > 0);
                }
            }
            connected
        };
        let streaming = is_server_running && is_connected;

        let sample_rate = self.network_stats.sample_rate.load(std::sync::atomic::Ordering::Relaxed);
        let channels = self.network_stats.channels.load(std::sync::atomic::Ordering::Relaxed);
        let muted = self.network_stats.is_muted();
        let input_level = f32::from_bits(self.network_stats.input_level_bits.load(std::sync::atomic::Ordering::Relaxed));
        let processed_level = f32::from_bits(self.network_stats.processed_level_bits.load(std::sync::atomic::Ordering::Relaxed));

        let queued_samples = self.audio_output.queued_samples();
        let queued_ms = if channels > 0 {
            (queued_samples as f64 / channels as f64) / 48.0
        } else {
            0.0
        };

        AudioStateSnapshot {
            streaming,
            sample_rate,
            channels,
            input_level,
            processed_level,
            queued_ms,
            muted,
        }
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
            use tauri_plugin_opener::OpenerExt;
            app.opener()
                .open_url(url, None::<&str>)
                .map_err(|e| PluginError::Runtime(format!("open_url: {e}")))?;
        } else {
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
            use tauri_plugin_notification::NotificationExt;
            app.notification()
                .builder()
                .title(title)
                .body(body)
                .show()
                .map_err(|e| PluginError::Runtime(format!("notify: {e}")))?;
        } else {
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
        let mut devices = Vec::new();
        let has_tcp_control = self.bus.transport().is_connected();
        
        if has_tcp_control {
            let session_info = self.active_audio_session.read().ok().map(|s| *s);
            
            let (mode, label, audio_active) = match session_info {
                Some(crate::udp_server::ActiveAudioSession::Bound { peer_ip, .. }) |
                Some(crate::udp_server::ActiveAudioSession::UnboundLegacy { peer_ip, .. }) => {
                    let ip_str = peer_ip.to_string();
                    let inferred_mode = if ip_str.starts_with("127.0.0.1") || ip_str.starts_with("::1") {
                        "usb"
                    } else {
                        "wifi"
                    };
                    (inferred_mode.to_string(), ip_str, true)
                },
                Some(crate::udp_server::ActiveAudioSession::Inactive) | None => {
                    ("wifi".to_string(), "pending...".to_string(), false)
                }
            };

            devices.push(DeviceSnapshot {
                mode,
                label,
                audio_active,
            });
        }

        #[cfg(feature = "web-server")]
        {
            if let Ok(web_lock) = self.web_server.try_lock() {
                if let Some(w) = web_lock.as_ref() {
                    let count = w.client_count();
                    for _ in 0..count {
                        devices.push(DeviceSnapshot {
                            mode: "web".to_string(),
                            label: "web client".to_string(),
                            audio_active: true, 
                        });
                    }
                }
            }
        }

        devices
    }
}

#[cfg(test)]
mod tests_e2e {
    use super::*;

    #[test]
    fn soundpad_trigger_end_to_end() {
        let output = crate::audio_output::AudioOutputHandle::spawn();
        let network_stats = Arc::new(crate::stats::NetworkStats::default());
        let active_connection = Arc::new(tokio::sync::Mutex::new(None));
        let active_audio_session = Arc::new(std::sync::RwLock::new(crate::udp_server::ActiveAudioSession::Inactive));
        let lifecycle = Arc::new(tokio::sync::Mutex::new(crate::server::ServerLifecycleState::default()));
        #[cfg(feature = "web-server")]
        let web_server = Arc::new(tokio::sync::Mutex::new(None));
        
        let host = PluginHost::new(
            output,
            network_stats,
            active_connection,
            active_audio_session,
            lifecycle,
            #[cfg(feature = "web-server")] web_server,
        );
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
