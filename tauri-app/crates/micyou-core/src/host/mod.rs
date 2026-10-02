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

//! Desktop integration the core needs from whichever frontend hosts it.
//!
//! The GUI implements this on top of its windowing toolkit; CLI and TUI use
//! [`headless::HeadlessHost`], which talks to the OS directly.

#[cfg(feature = "headless-host")]
pub mod headless;

use std::sync::Arc;

/// Invoked on every press of a registered global hotkey.
pub type HotkeyCallback = Arc<dyn Fn() + Send + Sync>;

pub trait HostIntegration: Send + Sync + 'static {
    fn open_url(&self, url: &str) -> Result<(), String>;
    fn notify(&self, title: &str, body: &str) -> Result<(), String>;
    fn register_hotkey(&self, shortcut: &str, on_press: HotkeyCallback) -> Result<(), String>;
    /// Show a plugin UI panel in its own window. `title` is the window title.
    fn open_plugin_panel(&self, plugin_id: &str, panel_id: &str, title: &str)
        -> Result<(), String>;
}

pub type SharedHost = Arc<dyn HostIntegration>;
