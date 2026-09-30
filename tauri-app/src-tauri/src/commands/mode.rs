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

use micyou_core::config::{self, ThemeColors, UiPrefs};
use micyou_core::mode_lock::{self, RunMode};
use micyou_core::modes::{ModeStatus, TerminalMode};
use micyou_core::server::ServerState;
use tauri::State;

#[tauri::command]
pub fn get_mode_status() -> ModeStatus {
    micyou_core::modes::mode_status()
}

/// Release the GUI lock before handing off to a terminal mode.
#[tauri::command]
pub fn release_gui_lock() {
    if mode_lock::read_lock().is_some_and(|info| info.mode == RunMode::Gui && info.pid == std::process::id()) {
        mode_lock::release();
    }
}

/// Stop the server and launch `micyou-cli serve` in a terminal. The frontend
/// exits the app after this succeeds.
#[tauri::command]
pub async fn switch_to_cli(state: State<'_, ServerState>) -> Result<(), String> {
    micyou_core::modes::switch_to_terminal(&state, TerminalMode::Cli).await
}

/// Stop the server and launch `micyou-tui` in a terminal.
#[tauri::command]
pub async fn switch_to_tui(state: State<'_, ServerState>) -> Result<(), String> {
    micyou_core::modes::switch_to_terminal(&state, TerminalMode::Tui).await
}

/// Persist the GUI language and theme color to ui.json for the TUI.
#[tauri::command]
pub fn save_ui_prefs(language: String, theme_color: String) -> Result<(), String> {
    config::save_ui_prefs(&UiPrefs {
        language,
        theme_color,
    })
}

/// Export the current GUI theme colors to theme.json for the TUI.
#[tauri::command]
#[allow(clippy::too_many_arguments)]
pub fn save_theme_colors(
    primary: String,
    secondary: String,
    tertiary: String,
    surface: String,
    surface_variant: String,
    on_surface: String,
    error: String,
) -> Result<(), String> {
    config::save_theme_colors(&ThemeColors {
        primary,
        secondary,
        tertiary,
        surface,
        surface_variant,
        on_surface,
        error,
    })
}

#[tauri::command]
pub fn get_theme_colors() -> ThemeColors {
    config::load_theme_colors()
}
