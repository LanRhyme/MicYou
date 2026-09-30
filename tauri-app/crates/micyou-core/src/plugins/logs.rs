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


use micyou_plugin::host::PluginLogLevel;
use std::collections::{HashMap, VecDeque};
use std::sync::Mutex;

/// Most recent log lines of every plugin, shown on its details page.
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
