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

//! Handing the audio server over from the GUI to a terminal frontend.

use crate::mode_lock::{self, RunMode};
use crate::platform::terminal;
use crate::server::ServerState;
use serde::{Deserialize, Serialize};
use std::sync::atomic::{AtomicBool, Ordering};

#[derive(Clone, Copy, Debug, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum TerminalMode {
    Cli,
    Tui,
}

impl TerminalMode {
    fn binary(self) -> &'static str {
        match self {
            Self::Cli => "micyou-cli",
            Self::Tui => "micyou-tui",
        }
    }

    fn args(self) -> &'static [&'static str] {
        match self {
            Self::Cli => &["serve"],
            Self::Tui => &[],
        }
    }
}

#[derive(Serialize, Debug, Clone)]
#[serde(rename_all = "camelCase")]
pub struct ModeStatus {
    /// Lock owner: "gui" | "cli" | "tui" | "none"
    pub mode: String,
    pub pid: Option<u32>,
    /// Whether a live process owns the lock
    pub running: bool,
}

pub fn mode_status() -> ModeStatus {
    match mode_lock::read_lock() {
        Some(lock) => ModeStatus {
            mode: match lock.mode {
                RunMode::Gui => "gui",
                RunMode::Cli => "cli",
                RunMode::Tui => "tui",
            }
            .to_string(),
            pid: Some(lock.pid),
            running: mode_lock::pid_alive(lock.pid),
        },
        None => ModeStatus {
            mode: "none".to_string(),
            pid: None,
            running: false,
        },
    }
}

static MODE_SWITCH_IN_PROGRESS: AtomicBool = AtomicBool::new(false);

struct ModeSwitchGuard<'a> {
    flag: &'a AtomicBool,
    reset_on_drop: bool,
}

impl<'a> ModeSwitchGuard<'a> {
    fn acquire(flag: &'a AtomicBool) -> Result<Self, String> {
        flag.compare_exchange(false, true, Ordering::AcqRel, Ordering::Acquire)
            .map_err(|_| "another mode switch is already in progress".to_string())?;
        Ok(Self {
            flag,
            reset_on_drop: true,
        })
    }

    /// Keep the gate closed after a successful handoff. The GUI is about to
    /// exit, so accepting another switch could launch a second mode.
    fn commit(mut self) {
        self.reset_on_drop = false;
    }
}

impl Drop for ModeSwitchGuard<'_> {
    fn drop(&mut self) {
        if self.reset_on_drop {
            self.flag.store(false, Ordering::Release);
        }
    }
}

/// Stop the server, release the GUI lock and open a terminal running the
/// target frontend. The caller should exit after this succeeds.
pub async fn switch_to_terminal(state: &ServerState, target: TerminalMode) -> Result<(), String> {
    // Tray and webview events can arrive close together. Reserve the handoff
    // before the first await so only one target can ever be launched.
    let switch_guard = ModeSwitchGuard::acquire(&MODE_SWITCH_IN_PROGRESS)?;

    if let Some(info) = mode_lock::read_lock() {
        if matches!(info.mode, RunMode::Cli | RunMode::Tui) && mode_lock::pid_alive(info.pid) {
            return Err(format!(
                "{:?} mode is already running (pid {}) - stop it first",
                info.mode, info.pid,
            ));
        }
    }
    let binary = terminal::find_binary(target.binary()).ok_or_else(|| {
        format!("{} binary not found - install it or add it to PATH", target.binary())
    })?;

    // Stop before handing off, otherwise the GUI's audio thread would still
    // hold the output device while the terminal frontend starts.
    if let Err(e) = crate::server::stop_server(state).await {
        log::info!(target: "mode", "server was not running before the switch: {e}");
    }
    mode_lock::release();

    log::info!(target: "mode", "switching GUI to {target:?} mode");
    if let Err(error) = terminal::open_in_terminal(&binary, target.args()) {
        // The GUI stays alive when launching fails: take the lock back so the
        // user can retry instead of leaving the app unlocked.
        if let Err(relock) = mode_lock::acquire(RunMode::Gui) {
            log::error!(target: "mode", "failed to re-acquire the GUI lock: {relock}");
        }
        return Err(error);
    }
    switch_guard.commit();
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::ModeSwitchGuard;
    use std::sync::atomic::{AtomicBool, Ordering};

    #[test]
    fn mode_switch_gate_rejects_a_second_target() {
        let flag = AtomicBool::new(false);
        let first = ModeSwitchGuard::acquire(&flag).expect("first switch should reserve the gate");
        assert!(ModeSwitchGuard::acquire(&flag).is_err());
        drop(first);
        assert!(!flag.load(Ordering::Acquire));
        assert!(ModeSwitchGuard::acquire(&flag).is_ok());
    }

    #[test]
    fn successful_mode_switch_keeps_gate_closed_until_exit() {
        let flag = AtomicBool::new(false);
        ModeSwitchGuard::acquire(&flag)
            .expect("switch should reserve the gate")
            .commit();
        assert!(flag.load(Ordering::Acquire));
        assert!(ModeSwitchGuard::acquire(&flag).is_err());
    }
}
