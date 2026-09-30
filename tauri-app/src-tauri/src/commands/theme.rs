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

use micyou_core::platform::accent::SystemAccentColor;
use micyou_core::themes::{self, InstalledTheme};
use std::path::PathBuf;
use tauri::{AppHandle, Manager};

fn themes_root(app: &AppHandle) -> Result<PathBuf, String> {
    Ok(app.path().app_data_dir().map_err(|e| e.to_string())?.join("themes"))
}

/// Read the desktop accent color once at application startup.
#[tauri::command]
pub fn get_system_accent_color() -> SystemAccentColor {
    micyou_core::platform::accent::system_accent_color()
}

#[tauri::command]
pub fn install_theme(app: AppHandle, theme_id: String, manifest_json: String, css: String) -> Result<(), String> {
    themes::install_theme(&themes_root(&app)?, &theme_id, &manifest_json, &css)
}

#[tauri::command]
pub fn list_installed_themes(app: AppHandle) -> Result<Vec<String>, String> {
    themes::list_installed_themes(&themes_root(&app)?)
}

#[tauri::command]
pub fn get_installed_theme(app: AppHandle, theme_id: String) -> Result<InstalledTheme, String> {
    themes::get_installed_theme(&themes_root(&app)?, &theme_id)
}

#[tauri::command]
pub fn remove_installed_theme(app: AppHandle, theme_id: String) -> Result<(), String> {
    themes::remove_installed_theme(&themes_root(&app)?, &theme_id)
}
