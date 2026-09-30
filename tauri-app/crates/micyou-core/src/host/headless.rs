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

//! Host integration for terminal frontends (CLI/TUI), which have no window
//! toolkit event loop of their own.

use super::{HostIntegration, HotkeyCallback};
use global_hotkey::hotkey::HotKey;
use global_hotkey::{GlobalHotKeyEvent, GlobalHotKeyManager, HotKeyState};
use std::collections::HashMap;
use std::sync::mpsc::{self, Sender};
use std::sync::Mutex;

/// `global-hotkey` delivers events through the platform message loop, so the
/// thread that owns the manager has to pump it.
#[cfg(target_os = "windows")]
mod event_pump {
    use std::ffi::c_void;

    #[repr(C)]
    struct Point {
        x: i32,
        y: i32,
    }

    #[repr(C)]
    struct Msg {
        hwnd: *mut c_void,
        message: u32,
        w_param: usize,
        l_param: isize,
        time: u32,
        pt: Point,
        l_private: u32,
    }

    const PM_REMOVE: u32 = 0x0001;

    #[link(name = "user32")]
    extern "system" {
        fn PeekMessageW(msg: *mut Msg, hwnd: *mut c_void, min: u32, max: u32, remove: u32) -> i32;
        fn TranslateMessage(msg: *const Msg) -> i32;
        fn DispatchMessageW(msg: *const Msg) -> isize;
    }

    pub fn pump() {
        unsafe {
            let mut msg: Msg = std::mem::zeroed();
            while PeekMessageW(&mut msg, std::ptr::null_mut(), 0, 0, PM_REMOVE) != 0 {
                TranslateMessage(&msg);
                DispatchMessageW(&msg);
            }
        }
    }
}

#[cfg(target_os = "macos")]
mod event_pump {
    use std::ffi::c_void;

    #[link(name = "CoreFoundation", kind = "framework")]
    extern "C" {
        fn CFRunLoopRunInMode(mode: *const c_void, seconds: f64, return_after: u8) -> i32;
        static kCFRunLoopDefaultMode: *const c_void;
    }

    pub fn pump() {
        unsafe {
            CFRunLoopRunInMode(kCFRunLoopDefaultMode, 0.01, 0);
        }
    }
}

#[cfg(not(any(target_os = "windows", target_os = "macos")))]
mod event_pump {
    pub fn pump() {}
}

type Registration = (HotKey, HotkeyCallback, Sender<Result<(), String>>);

#[derive(Default)]
pub struct HeadlessHost {
    hotkey_thread: Mutex<Option<Sender<Registration>>>,
}

impl HeadlessHost {
    pub fn new() -> Self {
        Self::default()
    }

    fn hotkey_sender(&self) -> Result<Sender<Registration>, String> {
        let mut slot = self
            .hotkey_thread
            .lock()
            .map_err(|_| "hotkey thread lock poisoned".to_string())?;
        if let Some(tx) = slot.as_ref() {
            return Ok(tx.clone());
        }

        let (tx, rx) = mpsc::channel::<Registration>();
        let (init_tx, init_rx) = mpsc::channel::<Result<(), String>>();
        std::thread::Builder::new()
            .name("micyou-hotkeys".into())
            .spawn(move || {
                let manager = match GlobalHotKeyManager::new() {
                    Ok(manager) => {
                        let _ = init_tx.send(Ok(()));
                        manager
                    }
                    Err(e) => {
                        let _ = init_tx.send(Err(format!("hotkey manager init failed: {e}")));
                        return;
                    }
                };
                let mut callbacks: HashMap<u32, HotkeyCallback> = HashMap::new();
                loop {
                    event_pump::pump();
                    match rx.try_recv() {
                        Ok((hotkey, callback, reply)) => {
                            let result = manager
                                .register(hotkey)
                                .map_err(|e| format!("hotkey register failed: {e}"));
                            if result.is_ok() {
                                callbacks.insert(hotkey.id(), callback);
                            }
                            let _ = reply.send(result);
                        }
                        Err(mpsc::TryRecvError::Disconnected) => return,
                        Err(mpsc::TryRecvError::Empty) => {}
                    }
                    while let Ok(event) = GlobalHotKeyEvent::receiver().try_recv() {
                        if event.state() == HotKeyState::Pressed {
                            if let Some(callback) = callbacks.get(&event.id()) {
                                callback();
                            }
                        }
                    }
                    std::thread::sleep(std::time::Duration::from_millis(10));
                }
            })
            .map_err(|e| format!("spawn hotkey thread: {e}"))?;
        init_rx
            .recv()
            .map_err(|_| "hotkey thread exited during startup".to_string())??;
        *slot = Some(tx.clone());
        Ok(tx)
    }
}

impl HostIntegration for HeadlessHost {
    fn open_url(&self, url: &str) -> Result<(), String> {
        open::that(url).map_err(|e| format!("open_url: {e}"))
    }

    fn notify(&self, title: &str, body: &str) -> Result<(), String> {
        notify_rust::Notification::new()
            .summary(title)
            .body(body)
            .show()
            .map(|_| ())
            .map_err(|e| format!("notify: {e}"))
    }

    fn register_hotkey(&self, shortcut: &str, on_press: HotkeyCallback) -> Result<(), String> {
        let hotkey: HotKey = shortcut
            .try_into()
            .map_err(|_| format!("invalid hotkey: {shortcut}"))?;
        let (reply_tx, reply_rx) = mpsc::channel();
        self.hotkey_sender()?
            .send((hotkey, on_press, reply_tx))
            .map_err(|_| "hotkey thread exited".to_string())?;
        reply_rx
            .recv()
            .map_err(|_| "hotkey thread exited".to_string())?
    }

    fn open_plugin_panel(&self, plugin_id: &str, _panel_id: &str, _title: &str) -> Result<(), String> {
        Err(format!(
            "plugin {plugin_id} requested a panel window, which terminal frontends cannot show"
        ))
    }
}
