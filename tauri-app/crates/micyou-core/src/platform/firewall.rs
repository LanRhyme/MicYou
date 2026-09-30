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

/// Ask Windows (UAC prompt) to allow inbound TCP/UDP for this executable.
/// Other platforms have no per-app firewall prompt to satisfy.
#[cfg(windows)]
pub fn allow_inbound() -> Result<(), String> {
    let exe_path = std::env::current_exe().map_err(|e| e.to_string())?;
    let exe = exe_path.to_string_lossy();
    let script = format!(
        "netsh advfirewall firewall add rule name=\"MicYou App (TCP-In)\" dir=in action=allow program=\"{exe}\" protocol=TCP enable=yes; netsh advfirewall firewall add rule name=\"MicYou App (UDP-In)\" dir=in action=allow program=\"{exe}\" protocol=UDP enable=yes"
    );
    let status = std::process::Command::new("powershell")
        .args([
            "-Command",
            &format!("Start-Process cmd -ArgumentList '/c {script}' -Verb RunAs -WindowStyle Hidden"),
        ])
        .status()
        .map_err(|e| e.to_string())?;
    if !status.success() {
        return Err("Failed to execute firewall rule addition".to_string());
    }
    log::info!(target: "system", "Requested firewall permission on Windows");
    Ok(())
}

#[cfg(not(windows))]
pub fn allow_inbound() -> Result<(), String> {
    Ok(())
}
