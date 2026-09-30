/*
 * MicYou — Turns your Android device into a high-quality PC microphone.
 * Copyright (C) 2026 LanRhyme <https://github.com/MicYou-Dev/MicYou>
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

use crate::host::SharedHost;
use micyou_plugin::bus::{PluginBus, PluginMessage};
use micyou_plugin::{PluginError, PluginResult};
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::Arc;

/// Global hotkeys requested by plugins. A press is delivered to the plugin
/// as a `hotkey:<id>` bus message.
pub struct HotkeyService {
    host: SharedHost,
    bus: Arc<PluginBus>,
    next_id: AtomicU64,
}

impl HotkeyService {
    pub fn new(host: SharedHost, bus: Arc<PluginBus>) -> Self {
        Self {
            host,
            bus,
            next_id: AtomicU64::new(1),
        }
    }

    pub fn register(&self, plugin_id: &str, shortcut: &str) -> PluginResult<u64> {
        #[cfg(target_os = "linux")]
        if std::env::var("XDG_SESSION_TYPE").is_ok_and(|s| s == "wayland")
            || std::env::var_os("WAYLAND_DISPLAY").is_some()
        {
            return Err(PluginError::Runtime(format!(
                "global hotkey unavailable on Wayland (X11-only backend); use the plugin panel buttons instead (plugin {plugin_id})"
            )));
        }

        let id = self.next_id.fetch_add(1, Ordering::Relaxed);
        let bus = self.bus.clone();
        let plugin_id_owned = plugin_id.to_string();
        let payload = serde_json::json!({ "shortcut": shortcut }).to_string();
        self.host
            .register_hotkey(
                shortcut,
                Arc::new(move || {
                    let msg = PluginMessage::new(
                        "host",
                        &plugin_id_owned,
                        &format!("hotkey:{id}"),
                        payload.clone().into_bytes(),
                    );
                    bus.handle_incoming(&msg);
                }),
            )
            .map_err(PluginError::Runtime)?;
        log::info!("[plugins] {plugin_id} registered hotkey {shortcut}");
        Ok(id)
    }
}
