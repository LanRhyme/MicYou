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

use crate::mode_lock::{self, RunMode};
use crate::server::ServerState;
use serde::Serialize;
use std::ffi::OsStr;
use std::path::{Path, PathBuf};
use std::process::Command;
use std::sync::atomic::{AtomicBool, Ordering};
use tauri::{AppHandle, State};

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
    /// exit, so accepting another switch event could launch a second mode.
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

#[derive(Clone, Copy)]
enum TerminalMode {
    Cli,
    Tui,
}

impl TerminalMode {
    fn label(self) -> &'static str {
        match self {
            Self::Cli => "CLI",
            Self::Tui => "TUI",
        }
    }
}

#[derive(Serialize, Debug, Clone)]
#[serde(rename_all = "camelCase")]
pub struct ModeStatus {
    /// Lock state on disk: "gui" | "cli" | "tui" | "none"
    pub mode: String,
    pub pid: Option<u32>,
    /// Whether a live process owns the lock
    pub running: bool,
}

/// Resolve the `micyou-cli` binary path:
/// 1. sibling of the current exe (dev builds share target/debug)
/// 2. parent of the current exe dir (release layouts)
/// 3. PATH
pub fn find_cli_binary() -> Option<PathBuf> {
    let exe_name = if cfg!(target_os = "windows") {
        "micyou-cli.exe"
    } else {
        "micyou-cli"
    };

    find_named_binary(exe_name)
}

fn find_exact_file(dir: &Path, file_name: &str) -> Option<PathBuf> {
    std::fs::read_dir(dir)
        .ok()?
        .filter_map(Result::ok)
        .find(|entry| entry.file_name() == OsStr::new(file_name) && entry.path().is_file())
        .map(|entry| entry.path())
}

fn find_binary_on_path(exe_name: &str) -> Option<PathBuf> {
    let path = std::env::var_os("PATH")?;
    std::env::split_paths(&path).find_map(|dir| find_exact_file(&dir, exe_name))
}

fn find_named_binary(exe_name: &str) -> Option<PathBuf> {
    if let Ok(exe) = std::env::current_exe() {
        if let Some(dir) = exe.parent() {
            if let Some(candidate) = find_exact_file(dir, exe_name) {
                return Some(candidate);
            }
            if let Some(grandparent) = dir.parent() {
                if let Some(candidate) = find_exact_file(grandparent, exe_name) {
                    return Some(candidate);
                }
            }
        }
    }

    // Resolve PATH entries ourselves so Windows cannot substitute a
    // differently-cased executable for the requested CLI filename.
    find_binary_on_path(exe_name)
}

/// Resolve the standalone `micyou-tui` binary using the same lookup order as
/// the CLI binary.
pub fn find_tui_binary() -> Option<PathBuf> {
    let exe_name = if cfg!(target_os = "windows") {
        "micyou-tui.exe"
    } else {
        "micyou-tui"
    };

    find_named_binary(exe_name)
}

/// Current lock status for the frontend.
#[tauri::command]
pub fn get_mode_status() -> ModeStatus {
    match mode_lock::read_lock() {
        Some(lock_info) => {
            let running = mode_lock::pid_alive_public(lock_info.pid);
            let mode = match lock_info.mode {
                RunMode::Gui => "gui",
                RunMode::Cli => "cli",
                RunMode::Tui => "tui",
            };
            ModeStatus {
                mode: mode.to_string(),
                pid: Some(lock_info.pid),
                running,
            }
        }
        None => ModeStatus {
            mode: "none".to_string(),
            pid: None,
            running: false,
        },
    }
}

/// Release the GUI lock before handing off to a terminal mode.
#[tauri::command]
pub fn release_gui_lock() -> Result<(), String> {
    if let Some(info) = mode_lock::read_lock() {
        if info.mode == RunMode::Gui && info.pid == std::process::id() {
            mode_lock::release();
        }
    }
    Ok(())
}

/// Open a terminal window running the CLI server. Platform-specific:
/// - Linux: probe kitty / alacritty / gnome-terminal / konsole / xterm
/// - macOS: Terminal.app via osascript (iTerm2 fallback)
/// - Windows: cmd start (Windows Terminal preferred)
pub fn open_cli_terminal() -> Result<(), String> {
    let binary = find_cli_binary()
        .ok_or_else(|| "micyou CLI binary not found - install it or add it to PATH".to_string())?;

    #[cfg(target_os = "linux")]
    {
        let candidates: &[(&str, &[&str])] = &[
            (
                "kitty",
                &["--", binary.to_str().unwrap_or("micyou-cli"), "serve"],
            ),
            (
                "alacritty",
                &["-e", binary.to_str().unwrap_or("micyou-cli"), "serve"],
            ),
            (
                "gnome-terminal",
                &["--", binary.to_str().unwrap_or("micyou-cli"), "serve"],
            ),
            (
                "konsole",
                &["-e", binary.to_str().unwrap_or("micyou-cli"), "serve"],
            ),
            (
                "xterm",
                &["-e", binary.to_str().unwrap_or("micyou-cli"), "serve"],
            ),
        ];
        for (term, args) in candidates {
            if Command::new(term).args(*args).spawn().is_ok() {
                return Ok(());
            }
        }
        Err(
            "no supported terminal emulator found (kitty/alacritty/gnome-terminal/konsole/xterm)"
                .into(),
        )
    }
    #[cfg(target_os = "macos")]
    {
        let script = format!(
            "tell application \"Terminal\" to do script \"{} serve\"",
            binary.to_string_lossy()
        );
        let status = Command::new("osascript")
            .args(["-e", &script])
            .spawn()
            .map_err(|e| e.to_string())?;
        let _ = status;
        Ok(())
    }
    #[cfg(target_os = "windows")]
    {
        let bin = binary.to_string_lossy().to_string();
        // Launch wt.exe directly so a missing App Execution Alias produces a
        // real spawn error. `cmd /c start wt ...` itself succeeds even when
        // `wt` does not exist, which made the old fallback unreachable.
        if let Some(wt) = find_binary_on_path("wt.exe") {
            if Command::new(wt)
                .args(["-d", ".", "cmd", "/k", &bin, "serve"])
                .spawn()
                .is_ok()
            {
                return Ok(());
            }
        }
        Command::new("cmd")
            .args(["/c", "start", "", "cmd", "/k", &bin, "serve"])
            .spawn()
            .map_err(|e| e.to_string())?;
        Ok(())
    }
    #[cfg(not(any(target_os = "linux", target_os = "macos", target_os = "windows")))]
    {
        Err("unsupported platform".into())
    }
}

/// Open a terminal window running the standalone TUI.
pub fn open_tui_terminal() -> Result<(), String> {
    let binary = find_tui_binary()
        .ok_or_else(|| "micyou-tui binary not found - install it or add it to PATH".to_string())?;

    #[cfg(target_os = "linux")]
    {
        let candidates: &[(&str, &[&str])] = &[
            ("kitty", &["--", binary.to_str().unwrap_or("micyou-tui")]),
            (
                "alacritty",
                &["-e", binary.to_str().unwrap_or("micyou-tui")],
            ),
            (
                "gnome-terminal",
                &["--", binary.to_str().unwrap_or("micyou-tui")],
            ),
            ("konsole", &["-e", binary.to_str().unwrap_or("micyou-tui")]),
            ("xterm", &["-e", binary.to_str().unwrap_or("micyou-tui")]),
        ];
        for (terminal, args) in candidates {
            if Command::new(terminal).args(*args).spawn().is_ok() {
                return Ok(());
            }
        }
        Err(
            "no supported terminal emulator found (kitty/alacritty/gnome-terminal/konsole/xterm)"
                .into(),
        )
    }
    #[cfg(target_os = "macos")]
    {
        let script = format!(
            "tell application \"Terminal\" to do script \"{}\"",
            binary.to_string_lossy()
        );
        Command::new("osascript")
            .args(["-e", &script])
            .spawn()
            .map_err(|e| e.to_string())?;
        Ok(())
    }
    #[cfg(target_os = "windows")]
    {
        let bin = binary.to_string_lossy().to_string();
        if let Some(wt) = find_binary_on_path("wt.exe") {
            if Command::new(wt)
                .args(["-d", ".", "cmd", "/k", &bin])
                .spawn()
                .is_ok()
            {
                return Ok(());
            }
        }
        Command::new("cmd")
            .args(["/c", "start", "", "cmd", "/k", &bin])
            .spawn()
            .map_err(|e| e.to_string())?;
        Ok(())
    }
    #[cfg(not(any(target_os = "linux", target_os = "macos", target_os = "windows")))]
    {
        Err("unsupported platform".into())
    }
}

async fn switch_to_terminal(
    app: AppHandle,
    state: State<'_, ServerState>,
    target: TerminalMode,
) -> Result<(), String> {
    // Tray and webview events can arrive close together. Reserve the handoff
    // before the first await so only one target can ever be launched.
    let switch_guard = ModeSwitchGuard::acquire(&MODE_SWITCH_IN_PROGRESS)?;

    // Make sure no CLI or TUI instance is currently running.
    if let Some(info) = mode_lock::read_lock() {
        if matches!(info.mode, RunMode::Cli | RunMode::Tui) && mode_lock::pid_alive_public(info.pid)
        {
            return Err(format!(
                "{} mode is already running (pid {}) - stop it first",
                if info.mode == RunMode::Cli {
                    "CLI"
                } else {
                    "TUI"
                },
                info.pid,
            ));
        }
    }
    // Stop the audio server BEFORE handing off to the terminal mode. Otherwise it
    // starts while the GUI's audio thread is still holding the output device
    // and playing the incoming stream, and the brief overlap sounds like the
    // microphone is being monitored / the audio routing is broken.
    let _ = crate::commands::system::stop_server(app.clone(), state).await;
    mode_lock::release();

    log::info!(target: "mode", "switching GUI to {} mode", target.label());
    let launch_result = match target {
        TerminalMode::Cli => open_cli_terminal(),
        TerminalMode::Tui => open_tui_terminal(),
    };
    if let Err(error) = launch_result {
        // The GUI is still alive when launching fails, so restore its lock and
        // allow the user to retry instead of leaving the app in an unlocked state.
        let _ = mode_lock::acquire(RunMode::Gui);
        return Err(error);
    }

    switch_guard.commit();
    Ok(())
}

/// Switch from the GUI to CLI mode: release the GUI lock and launch a terminal
/// running `micyou-cli serve`. The frontend should exit the app after this succeeds.
#[tauri::command]
pub async fn switch_to_cli(app: AppHandle, state: State<'_, ServerState>) -> Result<(), String> {
    switch_to_terminal(app, state, TerminalMode::Cli).await
}

/// Switch from the GUI to TUI mode and launch `micyou-tui` in a terminal.
#[tauri::command]
pub async fn switch_to_tui(app: AppHandle, state: State<'_, ServerState>) -> Result<(), String> {
    switch_to_terminal(app, state, TerminalMode::Tui).await
}

/// Persist the current GUI UI preferences (language, theme color) to ui.json so
/// the TUI can pick the same language and theme.
#[tauri::command]
pub fn save_ui_prefs(language: String, theme_color: String) -> Result<(), String> {
    crate::app_config::save_ui_prefs(&crate::app_config::UiPrefs {
        language,
        theme_color,
    })
}

/// Export the current GUI theme colors to theme.json for the TUI.
#[tauri::command]
pub fn save_theme_colors(
    app: AppHandle,
    primary: String,
    secondary: String,
    tertiary: String,
    surface: String,
    surface_variant: String,
    on_surface: String,
    error: String,
) -> Result<(), String> {
    let colors = crate::app_config::ThemeColors {
        primary,
        secondary,
        tertiary,
        surface,
        surface_variant,
        on_surface,
        error,
    };
    use tauri::Emitter;
    let _ = app.emit("theme-colors-changed", &colors);
    crate::app_config::save_theme_colors(&colors)
}

/// Retrieve the current exported theme colors.
#[tauri::command]
pub fn get_theme_colors() -> crate::app_config::ThemeColors {
    crate::app_config::load_theme_colors()
}

#[cfg(test)]
mod tests {
    use super::{find_exact_file, ModeSwitchGuard};
    use std::fs;
    use std::sync::atomic::{AtomicBool, Ordering};
    use std::time::{SystemTime, UNIX_EPOCH};

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

    #[test]
    fn binary_lookup_requires_an_exact_case_match() {
        let suffix = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .expect("system clock should be after the Unix epoch")
            .as_nanos();
        let dir = std::env::temp_dir().join(format!(
            "micyou-mode-exact-name-{}-{suffix}",
            std::process::id()
        ));
        fs::create_dir_all(&dir).expect("temporary test directory should be created");
        fs::write(dir.join("MicYou-CLI.exe"), b"wrong case")
            .expect("case-variant CLI fixture should be written");

        assert_eq!(find_exact_file(&dir, "micyou-cli.exe"), None);

        let cli = dir.join("micyou-cli.exe");
        fs::write(&cli, b"cli").expect("CLI fixture should be written");
        assert_eq!(find_exact_file(&dir, "micyou-cli.exe"), Some(cli));

        fs::remove_dir_all(dir).expect("temporary test directory should be removed");
    }
}
