use crate::mode_lock::{self, RunMode};
use crate::server::ServerState;
use serde::Serialize;
use std::process::Command;
use tauri::{AppHandle, State};

#[derive(Serialize, Debug, Clone)]
#[serde(rename_all = "camelCase")]
pub struct ModeStatus {
    /// Lock state on disk: "gui" | "cli" | "tui" | "none"
    pub mode: String,
    pub pid: Option<u32>,
    /// Whether a live process owns the lock
    pub running: bool,
}

/// Resolve the `micyou` CLI binary path:
/// 1. sibling of the current exe (dev builds share target/debug)
/// 2. parent of the current exe dir (release layouts)
/// 3. PATH
pub fn find_cli_binary() -> Option<std::path::PathBuf> {
    let exe_name = if cfg!(target_os = "windows") {
        "micyou.exe"
    } else {
        "micyou"
    };

    if let Ok(exe) = std::env::current_exe() {
        if let Some(dir) = exe.parent() {
            let candidate = dir.join(exe_name);
            if candidate.exists() {
                return Some(candidate);
            }
            if let Some(grandparent) = dir.parent() {
                let candidate = grandparent.join(exe_name);
                if candidate.exists() {
                    return Some(candidate);
                }
            }
        }
    }

    // Fall back to PATH lookup
    if let Ok(output) = Command::new(exe_name).arg("--version").output() {
        if output.status.success() {
            return Some(std::path::PathBuf::from(exe_name));
        }
    }
    None
}

/// Resolve the standalone `micyou-tui` binary using the same lookup order as
/// the CLI binary.
pub fn find_tui_binary() -> Option<std::path::PathBuf> {
    let exe_name = if cfg!(target_os = "windows") {
        "micyou-tui.exe"
    } else {
        "micyou-tui"
    };

    if let Ok(exe) = std::env::current_exe() {
        if let Some(dir) = exe.parent() {
            let candidate = dir.join(exe_name);
            if candidate.exists() {
                return Some(candidate);
            }
            if let Some(grandparent) = dir.parent() {
                let candidate = grandparent.join(exe_name);
                if candidate.exists() {
                    return Some(candidate);
                }
            }
        }
    }

    if let Ok(output) = Command::new(exe_name).arg("--version").output() {
        if output.status.success() {
            return Some(std::path::PathBuf::from(exe_name));
        }
    }
    None
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
                &["--", binary.to_str().unwrap_or("micyou"), "serve"],
            ),
            (
                "alacritty",
                &["-e", binary.to_str().unwrap_or("micyou"), "serve"],
            ),
            (
                "gnome-terminal",
                &["--", binary.to_str().unwrap_or("micyou"), "serve"],
            ),
            (
                "konsole",
                &["-e", binary.to_str().unwrap_or("micyou"), "serve"],
            ),
            (
                "xterm",
                &["-e", binary.to_str().unwrap_or("micyou"), "serve"],
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
        // Prefer Windows Terminal if available, otherwise plain cmd start
        Command::new("cmd")
            .args([
                "/c", "start", "", "wt", "-d", ".", "cmd", "/k", &bin, "serve",
            ])
            .spawn()
            .or_else(|_| {
                Command::new("cmd")
                    .args(["/c", "start", "", &bin, "serve"])
                    .spawn()
            })
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
        Command::new("cmd")
            .args(["/c", "start", "", "wt", "-d", ".", "cmd", "/k", &bin])
            .spawn()
            .or_else(|_| Command::new("cmd").args(["/c", "start", "", &bin]).spawn())
            .map_err(|e| e.to_string())?;
        Ok(())
    }
    #[cfg(not(any(target_os = "linux", target_os = "macos", target_os = "windows")))]
    {
        Err("unsupported platform".into())
    }
}

/// Switch from the GUI to CLI mode: release the GUI lock and launch a terminal
/// running `micyou serve`. The frontend should exit the app after this succeeds.
#[tauri::command]
pub async fn switch_to_cli(app: AppHandle, state: State<'_, ServerState>) -> Result<(), String> {
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
    // Stop the audio server BEFORE handing off to the CLI. Otherwise the CLI
    // starts while the GUI's audio thread is still holding the output device
    // and playing the incoming stream, and the brief overlap sounds like the
    // microphone is being monitored / the audio routing is broken.
    let _ = crate::commands::system::stop_server(app.clone(), state).await;
    mode_lock::release();
    open_cli_terminal()
}

/// Switch from the GUI to TUI mode and launch `micyou-tui` in a terminal.
#[tauri::command]
pub async fn switch_to_tui(app: AppHandle, state: State<'_, ServerState>) -> Result<(), String> {
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
    let _ = crate::commands::system::stop_server(app.clone(), state).await;
    mode_lock::release();
    open_tui_terminal()
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
    primary: String,
    secondary: String,
    tertiary: String,
    surface: String,
    surface_variant: String,
    on_surface: String,
    error: String,
) -> Result<(), String> {
    crate::app_config::save_theme_colors(&crate::app_config::ThemeColors {
        primary,
        secondary,
        tertiary,
        surface,
        surface_variant,
        on_surface,
        error,
    })
}
