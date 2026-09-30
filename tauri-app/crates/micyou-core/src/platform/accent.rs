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

//! Desktop accent color, read once at startup to seed the "system" theme.

use serde::Serialize;

#[derive(Serialize, Debug, Clone)]
#[serde(rename_all = "camelCase")]
pub struct SystemAccentColor {
    pub hex: String,
    pub source: String,
    pub supported: bool,
}

#[cfg_attr(not(any(windows, target_os = "macos", target_os = "linux")), allow(dead_code))]
fn accent_color(hex: impl Into<String>, source: impl Into<String>) -> SystemAccentColor {
    SystemAccentColor {
        hex: hex.into(),
        source: source.into(),
        supported: true,
    }
}

fn fallback_accent_color() -> SystemAccentColor {
    SystemAccentColor {
        hex: "#5b7cfa".to_string(),
        source: "fallback".to_string(),
        supported: false,
    }
}

#[cfg(windows)]
fn platform_accent_color() -> Option<SystemAccentColor> {
    use winreg::enums::HKEY_CURRENT_USER;
    use winreg::RegKey;

    let dwm = RegKey::predef(HKEY_CURRENT_USER)
        .open_subkey("Software\\Microsoft\\Windows\\DWM")
        .ok()?;
    let value: u32 = dwm.get_value("AccentColor").ok()?;
    Some(accent_color(abgr_to_hex(value), "windows-dwm"))
}

/// Windows stores AccentColor as AABBGGRR.
#[cfg(windows)]
fn abgr_to_hex(value: u32) -> String {
    format!(
        "#{:02x}{:02x}{:02x}",
        value & 0xff,
        (value >> 8) & 0xff,
        (value >> 16) & 0xff
    )
}

#[cfg(target_os = "macos")]
fn platform_accent_color() -> Option<SystemAccentColor> {
    let output = std::process::Command::new("defaults")
        .args(["read", "-g", "AppleAccentColor"])
        .output()
        .ok()?;
    let value = String::from_utf8_lossy(&output.stdout)
        .trim()
        .parse::<i32>()
        .ok()?;
    let hex = match value {
        0 => "#ff3b30",
        1 => "#ff9500",
        2 => "#ffcc00",
        3 => "#34c759",
        4 => "#007aff",
        5 => "#af52de",
        6 => "#ff2d55",
        _ => return None,
    };
    Some(accent_color(hex, "macos-accent"))
}

#[cfg(target_os = "linux")]
fn platform_accent_color() -> Option<SystemAccentColor> {
    let output = std::process::Command::new("gsettings")
        .args(["get", "org.gnome.desktop.interface", "accent-color"])
        .output()
        .ok()?;
    if !output.status.success() {
        return None;
    }
    let value = String::from_utf8_lossy(&output.stdout)
        .trim()
        .trim_matches('\'')
        .to_ascii_lowercase();
    let hex = match value.as_str() {
        "red" => "#f44336",
        "orange" => "#ff9800",
        "yellow" => "#fbc02d",
        "green" => "#4caf50",
        "blue" => "#2196f3",
        "purple" => "#9c27b0",
        "pink" => "#e91e63",
        "slate" => "#607d8b",
        _ => return None,
    };
    Some(accent_color(hex, "gnome-accent"))
}

#[cfg(not(any(windows, target_os = "macos", target_os = "linux")))]
fn platform_accent_color() -> Option<SystemAccentColor> {
    None
}

pub fn system_accent_color() -> SystemAccentColor {
    platform_accent_color().unwrap_or_else(fallback_accent_color)
}

#[cfg(test)]
mod tests {
    use super::fallback_accent_color;

    #[test]
    fn fallback_is_stable_and_marked_unsupported() {
        let color = fallback_accent_color();
        assert_eq!(color.hex, "#5b7cfa");
        assert_eq!(color.source, "fallback");
        assert!(!color.supported);
    }

    #[cfg(windows)]
    #[test]
    fn converts_windows_abgr_accent_color() {
        assert_eq!(super::abgr_to_hex(0xff3366cc), "#cc6633");
    }
}
