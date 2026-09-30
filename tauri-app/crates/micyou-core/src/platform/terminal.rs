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

//! Locate the sibling `micyou-cli` / `micyou-tui` binaries and launch them in
//! a terminal emulator.

use std::ffi::OsStr;
use std::path::{Path, PathBuf};
use std::process::Command;

fn find_exact_file(dir: &Path, file_name: &str) -> Option<PathBuf> {
    std::fs::read_dir(dir)
        .ok()?
        .filter_map(Result::ok)
        .find(|entry| entry.file_name() == OsStr::new(file_name) && entry.path().is_file())
        .map(|entry| entry.path())
}

/// Resolve PATH entries ourselves so Windows cannot substitute a
/// differently-cased executable for the requested filename.
fn find_binary_on_path(exe_name: &str) -> Option<PathBuf> {
    let path = std::env::var_os("PATH")?;
    std::env::split_paths(&path).find_map(|dir| find_exact_file(&dir, exe_name))
}

/// Resolve a MicYou binary: next to the current exe (dev builds share
/// target/debug), one level up (release layouts), then PATH.
pub fn find_binary(name: &str) -> Option<PathBuf> {
    let exe_name = if cfg!(target_os = "windows") {
        format!("{name}.exe")
    } else {
        name.to_string()
    };
    let exe = std::env::current_exe().ok();
    let dir = exe.as_deref().and_then(Path::parent);
    dir.and_then(|dir| find_exact_file(dir, &exe_name))
        .or_else(|| {
            dir.and_then(Path::parent)
                .and_then(|parent| find_exact_file(parent, &exe_name))
        })
        .or_else(|| find_binary_on_path(&exe_name))
}

/// Open a new terminal window running `binary args...`.
pub fn open_in_terminal(binary: &Path, args: &[&str]) -> Result<(), String> {
    #[cfg(target_os = "linux")]
    {
        let mut command_line: Vec<&OsStr> = vec![binary.as_os_str()];
        command_line.extend(args.iter().map(OsStr::new));
        // xdg-terminal-exec honours the user's configured default terminal.
        let terminals: &[(&str, &str)] = &[
            ("xdg-terminal-exec", ""),
            ("kitty", "--"),
            ("alacritty", "-e"),
            ("konsole", "-e"),
            ("gnome-terminal", "--"),
            ("xterm", "-e"),
        ];
        for (terminal, separator) in terminals {
            let mut command = Command::new(terminal);
            if !separator.is_empty() {
                command.arg(separator);
            }
            if command.args(&command_line).spawn().is_ok() {
                return Ok(());
            }
        }
        Err("no supported terminal emulator found (xdg-terminal-exec/kitty/alacritty/konsole/gnome-terminal/xterm)".into())
    }
    #[cfg(target_os = "macos")]
    {
        let mut line = format!("'{}'", binary.to_string_lossy());
        for arg in args {
            line.push(' ');
            line.push_str(arg);
        }
        let script = format!("tell application \"Terminal\" to do script \"{line}\"");
        Command::new("osascript")
            .args(["-e", &script])
            .spawn()
            .map(|_| ())
            .map_err(|e| e.to_string())
    }
    #[cfg(target_os = "windows")]
    {
        // Launch wt.exe directly so a missing App Execution Alias produces a
        // real spawn error; `cmd /c start wt ...` succeeds even without wt.
        if let Some(wt) = find_binary_on_path("wt.exe") {
            if Command::new(wt)
                .args(["-d", ".", "cmd", "/k"])
                .arg(binary)
                .args(args)
                .spawn()
                .is_ok()
            {
                return Ok(());
            }
        }
        Command::new("cmd")
            .args(["/c", "start", "", "cmd", "/k"])
            .arg(binary)
            .args(args)
            .spawn()
            .map(|_| ())
            .map_err(|e| e.to_string())
    }
    #[cfg(not(any(target_os = "linux", target_os = "macos", target_os = "windows")))]
    {
        let _ = (binary, args);
        Err("unsupported platform".into())
    }
}

#[cfg(test)]
mod tests {
    use super::find_exact_file;
    use std::fs;
    use std::time::{SystemTime, UNIX_EPOCH};

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
