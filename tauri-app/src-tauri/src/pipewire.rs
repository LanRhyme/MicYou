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

use std::process::Command;
use std::sync::Mutex;

const SINK_NAME: &str = "MicYouVirtualSink";
const SOURCE_NAME: &str = "MicYouVirtualMic";

static STATE: Mutex<PipeWireState> = Mutex::new(PipeWireState {
    sink_node_id: None,
    source_node_id: None,
    loopback_pid: None,
    is_setup: false,
    original_default_source: None,
});

struct PipeWireState {
    sink_node_id: Option<String>,
    source_node_id: Option<String>,
    loopback_pid: Option<u32>,
    is_setup: bool,
    original_default_source: Option<String>,
}

pub fn is_available() -> bool {
    if !cfg!(target_os = "linux") {
        return false;
    }
    Command::new("pw-cli")
        .arg("--version")
        .output()
        .map(|o| o.status.success())
        .unwrap_or(false)
}

pub fn is_setup() -> bool {
    STATE.lock().map(|s| s.is_setup).unwrap_or(false)
}

pub fn virtual_sink_name() -> &'static str {
    SINK_NAME
}

pub fn setup(resource_dir: Option<&std::path::Path>) -> bool {
    if !cfg!(target_os = "linux") {
        log::warn!("[PipeWire] PipeWire virtual audio device only supports Linux");
        return false;
    }

    if !is_available() {
        log::error!("[PipeWire] PipeWire is not available");
        return false;
    }

    log::info!("[PipeWire] Setting up PipeWire virtual audio device...");

    cleanup();

    if !create_virtual_sink() {
        log::error!("[PipeWire] Failed to create virtual Sink");
        return false;
    }

    wait_for_device(500);

    if !create_loopback() {
        log::error!("[PipeWire] Failed to create loopback");
        cleanup();
        return false;
    }

    wait_for_device(500);

    if !hide_virtual_sink() {
        log::warn!("[PipeWire] Failed to hide virtual Sink (non-fatal)");
    }

    let source_id = find_node_id_by_name(SOURCE_NAME);

    save_original_default_source();

    if !set_default_source() {
        log::warn!("[PipeWire] Failed to set default source (non-fatal)");
    }

    // Set ALSA_CONFIG_PATH to redirect ALSA output to virtual sink
    setup_alsa_config(resource_dir);

    let mut state = match STATE.lock() {
        Ok(s) => s,
        Err(e) => {
            log::error!("[PipeWire] Failed to lock state: {}", e);
            return false;
        }
    };

    state.source_node_id = source_id;
    state.is_setup = true;

    log::info!("[PipeWire] Virtual audio device setup complete");
    true
}

/// Set up ALSA_CONFIG_PATH environment variable to redirect ALSA output to virtual sink.
/// This is the key mechanism that makes cpal/ALSA route audio to the PipeWire virtual sink.
fn setup_alsa_config(resource_dir: Option<&std::path::Path>) {
    // Find the bundled ALSA config file
    let alsa_config = find_alsa_config(resource_dir);
    match alsa_config {
        Some(path) => {
            log::info!("[PipeWire] Setting ALSA_CONFIG_PATH={}", path.display());
            std::env::set_var("ALSA_CONFIG_PATH", &path);
        }
        None => {
            log::warn!(
                "[PipeWire] ALSA config file not found, audio may not route to virtual sink"
            );
        }
    }
}

/// Find the bundled micyou-pipewire.conf file
fn find_alsa_config(resource_dir: Option<&std::path::Path>) -> Option<std::path::PathBuf> {
    let relative_path = "alsa/micyou-pipewire.conf";

    // Prefer the runtime resource directory resolved by Tauri (correct for both
    // development and packaged deb/appimage/app builds).
    if let Some(dir) = resource_dir {
        // Packaged layout: resources live under <resource_dir>/resources.
        let resources_path = dir.join("resources").join(relative_path);
        if resources_path.exists() {
            return Some(resources_path);
        }
        // Development layout: resources live directly under <resource_dir>.
        let res_path = dir.join(relative_path);
        if res_path.exists() {
            return Some(res_path.canonicalize().unwrap_or(res_path));
        }
    }

    // Try resources directory relative to executable
    if let Ok(exe_path) = std::env::current_exe() {
        if let Some(exe_dir) = exe_path.parent() {
            // Check in resources/ subdirectory
            let resources_path = exe_dir.join("resources").join(relative_path);
            if resources_path.exists() {
                return Some(resources_path);
            }
        }
    }

    // Try relative to current directory (for development)
    let dev_path = std::path::PathBuf::from("src-tauri/resources").join(relative_path);
    if dev_path.exists() {
        return Some(dev_path);
    }

    // Try CARGO_MANIFEST_DIR (for cargo run)
    if let Ok(manifest_dir) = std::env::var("CARGO_MANIFEST_DIR") {
        let cargo_path = std::path::PathBuf::from(manifest_dir)
            .join("resources")
            .join(relative_path);
        if cargo_path.exists() {
            return Some(cargo_path);
        }
    }

    None
}

pub fn cleanup() {
    log::info!("[PipeWire] Cleaning up virtual audio device...");

    let (had_original, loopback_pid, sink_id, _source_id) = {
        let mut state = match STATE.lock() {
            Ok(s) => s,
            Err(_) => return,
        };
        let had = state.original_default_source.is_some();
        let pid = state.loopback_pid;
        let sink = state.sink_node_id.clone();
        let source = state.source_node_id.clone();
        state.is_setup = false;
        state.loopback_pid = None;
        state.sink_node_id = None;
        state.source_node_id = None;
        (had, pid, sink, source)
    };

    if had_original {
        restore_original_default_source();
        std::thread::sleep(std::time::Duration::from_millis(200));
    }

    if let Some(pid) = loopback_pid {
        stop_loopback(pid);
    }

    destroy_node_by_name(SOURCE_NAME, "Virtual Source");

    if let Some(id) = sink_id {
        destroy_node(&id, "Virtual Sink");
    } else {
        destroy_node_by_name(SINK_NAME, "Virtual Sink");
    }

    log::info!("[PipeWire] Virtual audio device cleanup complete");
}

fn create_virtual_sink() -> bool {
    log::debug!("[PipeWire] Creating virtual Sink: {}", SINK_NAME);

    match Command::new("pw-cli")
        .args([
            "create-node",
            "adapter",
            "factory.name=support.null-audio-sink",
            &format!("node.name={}", SINK_NAME),
            "media.class=Audio/Sink",
            "object.linger=true",
            "audio.position=[FL FR]",
            "monitor.mode=disabled",
        ])
        .output()
    {
        Ok(output) => {
            let stdout = String::from_utf8_lossy(&output.stdout);
            let stderr = String::from_utf8_lossy(&output.stderr);
            let combined = format!("{}{}", stdout, stderr);
            let exit_ok = output.status.success();

            if exit_ok || combined.contains("created") || combined.contains("bound") {
                let id = extract_numeric_id(&combined);
                if let Ok(mut state) = STATE.lock() {
                    state.sink_node_id = id.clone();
                }
                log::info!(
                    "[PipeWire] Virtual Sink created successfully (id: {:?})",
                    id
                );
                true
            } else {
                log::error!("[PipeWire] Failed to create virtual Sink: {}", combined);
                false
            }
        }
        Err(e) => {
            log::error!("[PipeWire] Error creating virtual Sink: {}", e);
            false
        }
    }
}

fn create_loopback() -> bool {
    log::debug!(
        "[PipeWire] Creating loopback: {} -> {}",
        SINK_NAME,
        SOURCE_NAME
    );

    let capture_props = format!(
        "{{\"node.target\": \"{}\", \"media.class\": \"Stream/Input/Audio\", \"stream.capture.sink\": true}}",
        SINK_NAME
    );
    let playback_props = format!(
        "{{\"node.name\": \"{}\", \"node.description\": \"{}\", \"media.class\": \"Audio/Source\"}}",
        SOURCE_NAME,
        SOURCE_NAME
    );

    match Command::new("pw-loopback")
        .arg(format!("--capture-props={}", capture_props))
        .arg(format!("--playback-props={}", playback_props))
        .spawn()
    {
        Ok(mut child) => {
            let pid = child.id();

            std::thread::sleep(std::time::Duration::from_millis(100));

            let is_alive = child.try_wait().ok().flatten().is_none();

            if is_alive {
                if let Ok(mut state) = STATE.lock() {
                    state.loopback_pid = Some(pid);
                }
                log::info!("[PipeWire] Loopback created successfully (pid: {})", pid);
                true
            } else {
                match child.wait() {
                    Ok(status) if status.success() => {
                        log::info!("[PipeWire] Loopback exited normally");
                        true
                    }
                    Ok(status) => {
                        log::error!("[PipeWire] Loopback exited with status: {}", status);
                        false
                    }
                    Err(e) => {
                        log::error!("[PipeWire] Failed to get loopback exit status: {}", e);
                        false
                    }
                }
            }
        }
        Err(e) => {
            log::error!("[PipeWire] Error creating loopback: {}", e);
            false
        }
    }
}

fn hide_virtual_sink() -> bool {
    log::debug!("[PipeWire] Hiding virtual Sink: {}", SINK_NAME);

    match Command::new("pw-cli")
        .args([
            "set-param",
            SINK_NAME,
            "Props",
            "{media.role=Communication device.intended-roles=Communication}",
        ])
        .output()
    {
        Ok(output) => {
            if output.status.success() {
                log::info!("[PipeWire] Virtual Sink hidden");
                true
            } else {
                let stderr = String::from_utf8_lossy(&output.stderr);
                log::warn!("[PipeWire] Failed to hide virtual Sink: {}", stderr);
                false
            }
        }
        Err(e) => {
            log::error!("[PipeWire] Error hiding virtual Sink: {}", e);
            false
        }
    }
}

fn set_default_source() -> bool {
    log::debug!("[PipeWire] Setting default source: {}", SOURCE_NAME);

    // Try wpctl first (preferred for PipeWire)
    let node_id = STATE.lock().ok().and_then(|s| s.source_node_id.clone());

    if let Some(ref id) = node_id {
        match Command::new("wpctl").args(["set-default", id]).output() {
            Ok(output) if output.status.success() => {
                log::info!("[PipeWire] Default source set via wpctl (id: {})", id);
                return true;
            }
            Ok(output) => {
                let stderr = String::from_utf8_lossy(&output.stderr);
                log::warn!("[PipeWire] wpctl set-default failed: {}", stderr);
            }
            Err(e) => {
                log::warn!("[PipeWire] wpctl set-default error: {}", e);
            }
        }
    }

    // Fallback to pactl
    match Command::new("pactl")
        .args(["set-default-source", SOURCE_NAME])
        .output()
    {
        Ok(output) if output.status.success() => {
            log::info!("[PipeWire] Default source set using pactl");
            true
        }
        Ok(output) => {
            let stderr = String::from_utf8_lossy(&output.stderr);
            log::warn!("[PipeWire] pactl failed to set default source: {}", stderr);
            false
        }
        Err(e) => {
            log::error!("[PipeWire] Error setting default source with pactl: {}", e);
            false
        }
    }
}

fn save_original_default_source() {
    match Command::new("pactl").arg("get-default-source").output() {
        Ok(output) if output.status.success() => {
            let source = String::from_utf8_lossy(&output.stdout).trim().to_string();
            if !source.is_empty() && source != SOURCE_NAME {
                if let Ok(mut state) = STATE.lock() {
                    state.original_default_source = Some(source.clone());
                }
                log::debug!("[PipeWire] Saved original default source: {}", source);
            }
        }
        _ => {
            log::warn!("[PipeWire] Failed to save original default source");
        }
    }
}

fn restore_original_default_source() {
    let original = STATE
        .lock()
        .ok()
        .and_then(|s| s.original_default_source.clone());
    let original = match original {
        Some(o) => o,
        None => return,
    };

    match Command::new("pactl")
        .args(["set-default-source", &original])
        .output()
    {
        Ok(output) if output.status.success() => {
            log::info!("[PipeWire] Restored original default source: {}", original);
        }
        Ok(output) => {
            let stderr = String::from_utf8_lossy(&output.stderr);
            log::warn!(
                "[PipeWire] Failed to restore original default source: {}",
                stderr
            );
        }
        Err(e) => {
            log::warn!("[PipeWire] Error restoring original default source: {}", e);
        }
    }

    if let Ok(mut state) = STATE.lock() {
        state.original_default_source = None;
    }
}

fn find_node_id_by_name(name: &str) -> Option<String> {
    match Command::new("pw-cli").arg("list-objects").output() {
        Ok(output) => {
            let stdout = String::from_utf8_lossy(&output.stdout);
            let mut current_id: Option<String> = None;
            for line in stdout.lines() {
                if let Some(caps) = regex_match_id(line) {
                    current_id = Some(caps);
                } else if current_id.is_some()
                    && line.contains("node.name")
                    && line.contains(&format!("\"{}\"", name))
                {
                    return current_id;
                }
            }
            None
        }
        Err(e) => {
            log::warn!("[PipeWire] Error finding node ID for '{}': {}", name, e);
            None
        }
    }
}

fn regex_match_id(line: &str) -> Option<String> {
    let trimmed = line.trim_start();
    if trimmed.starts_with("id ") || trimmed.starts_with("id\t") {
        let rest = &trimmed[3..].trim_start();
        let end = rest
            .find(|c: char| !c.is_ascii_digit())
            .unwrap_or(rest.len());
        if end > 0 {
            return Some(rest[..end].to_string());
        }
    }
    None
}

fn extract_numeric_id(text: &str) -> Option<String> {
    for word in text.split_whitespace() {
        if word.chars().all(|c| c.is_ascii_digit()) && !word.is_empty() {
            return Some(word.to_string());
        }
    }
    None
}

fn stop_loopback(pid: u32) {
    use libc::{kill, SIGTERM};
    unsafe {
        kill(pid as i32, SIGTERM);
    }
    log::debug!("[PipeWire] Loopback process terminated (pid: {})", pid);
}

fn destroy_node(node_id: &str, description: &str) {
    match Command::new("pw-cli").args(["destroy", node_id]).output() {
        Ok(output) => {
            if output.status.success() {
                log::debug!("[PipeWire] {} destroyed (id: {})", description, node_id);
            } else {
                let stderr = String::from_utf8_lossy(&output.stderr);
                log::warn!("[PipeWire] Failed to destroy {}: {}", description, stderr);
            }
        }
        Err(e) => {
            log::error!("[PipeWire] Error destroying {}: {}", description, e);
        }
    }
}

fn destroy_node_by_name(node_name: &str, description: &str) {
    if let Some(id) = find_node_id_by_name(node_name) {
        destroy_node(&id, description);
    } else {
        log::debug!(
            "[PipeWire] {} node ID not found for destruction (name: {})",
            description,
            node_name
        );
    }
}

/// Wait for a PipeWire device to appear, polling at intervals.
fn wait_for_device(max_wait_ms: u64) {
    let check_interval_ms = 50u64;
    let mut waited = 0u64;
    while waited < max_wait_ms {
        if device_exists() {
            return;
        }
        std::thread::sleep(std::time::Duration::from_millis(check_interval_ms));
        waited += check_interval_ms;
    }
}

pub fn device_exists() -> bool {
    match Command::new("pw-cli").arg("list-objects").output() {
        Ok(output) => {
            let stdout = String::from_utf8_lossy(&output.stdout);
            stdout.contains(SINK_NAME) && stdout.contains(SOURCE_NAME)
        }
        Err(_) => false,
    }
}

/// Detects Linux distribution and returns `(distro_key, install_command)`.
pub fn detect_install_info() -> (String, String) {
    if !cfg!(target_os = "linux") {
        return ("other".to_string(), String::new());
    }

    // 1. Check /etc/os-release or /usr/lib/os-release
    if let Some(info) = detect_from_os_release() {
        return info;
    }

    // 2. Fallback: check available package manager binaries on PATH or common dirs
    if path_exists_or_on_path("pacman") {
        (
            "arch".to_string(),
            "sudo pacman -S pipewire pipewire-pulse".to_string(),
        )
    } else if path_exists_or_on_path("apt") || path_exists_or_on_path("apt-get") {
        (
            "debian".to_string(),
            "sudo apt install pipewire pipewire-pulse".to_string(),
        )
    } else if path_exists_or_on_path("dnf") {
        (
            "fedora".to_string(),
            "sudo dnf install pipewire pipewire-pulseaudio".to_string(),
        )
    } else if path_exists_or_on_path("zypper") {
        (
            "opensuse".to_string(),
            "sudo zypper install pipewire pipewire-pulseaudio".to_string(),
        )
    } else if path_exists_or_on_path("apk") {
        (
            "alpine".to_string(),
            "sudo apk add pipewire pipewire-pulse".to_string(),
        )
    } else if path_exists_or_on_path("xbps-install") {
        (
            "void".to_string(),
            "sudo xbps-install -S pipewire".to_string(),
        )
    } else if path_exists_or_on_path("emerge") {
        (
            "gentoo".to_string(),
            "sudo emerge --ask media-video/pipewire".to_string(),
        )
    } else {
        (
            "debian".to_string(),
            "sudo apt install pipewire pipewire-pulse".to_string(),
        )
    }
}

pub fn detect_install_command() -> String {
    detect_install_info().1
}

fn path_exists_or_on_path(bin: &str) -> bool {
    if std::path::Path::new(&format!("/usr/bin/{}", bin)).exists()
        || std::path::Path::new(&format!("/bin/{}", bin)).exists()
    {
        return true;
    }
    if let Ok(path_var) = std::env::var("PATH") {
        for dir in path_var.split(':') {
            if std::path::Path::new(dir).join(bin).exists() {
                return true;
            }
        }
    }
    false
}

fn detect_from_os_release() -> Option<(String, String)> {
    let content = std::fs::read_to_string("/etc/os-release")
        .or_else(|_| std::fs::read_to_string("/usr/lib/os-release"))
        .ok()?;
    parse_os_release_info(&content)
}

pub fn parse_os_release_info(content: &str) -> Option<(String, String)> {
    let mut id = String::new();
    let mut id_like = String::new();

    for line in content.lines() {
        let line = line.trim();
        if let Some(val) = line.strip_prefix("ID=") {
            id = val.trim_matches(|c| c == '"' || c == '\'').to_lowercase();
        } else if let Some(val) = line.strip_prefix("ID_LIKE=") {
            id_like = val.trim_matches(|c| c == '"' || c == '\'').to_lowercase();
        }
    }

    let is_match = |tokens: &[&str]| -> bool {
        tokens
            .iter()
            .any(|&tok| id == tok || id_like.split_whitespace().any(|l| l == tok))
    };

    if is_match(&[
        "arch",
        "cachyos",
        "manjaro",
        "endeavouros",
        "artix",
        "garuda",
        "parabola",
    ]) {
        Some((
            "arch".to_string(),
            "sudo pacman -S pipewire pipewire-pulse".to_string(),
        ))
    } else if is_match(&[
        "debian",
        "ubuntu",
        "linuxmint",
        "mint",
        "pop",
        "elementary",
        "zorin",
        "kali",
        "deepin",
        "raspbian",
        "devuan",
    ]) {
        Some((
            "debian".to_string(),
            "sudo apt install pipewire pipewire-pulse".to_string(),
        ))
    } else if is_match(&[
        "fedora", "rhel", "centos", "rocky", "alma", "nobara", "redhat",
    ]) {
        Some((
            "fedora".to_string(),
            "sudo dnf install pipewire pipewire-pulseaudio".to_string(),
        ))
    } else if is_match(&["suse", "opensuse", "opensuse-tumbleweed", "opensuse-leap"]) {
        Some((
            "opensuse".to_string(),
            "sudo zypper install pipewire pipewire-pulseaudio".to_string(),
        ))
    } else if is_match(&["alpine"]) {
        Some((
            "alpine".to_string(),
            "sudo apk add pipewire pipewire-pulse".to_string(),
        ))
    } else if is_match(&["void"]) {
        Some((
            "void".to_string(),
            "sudo xbps-install -S pipewire".to_string(),
        ))
    } else if is_match(&["gentoo"]) {
        Some((
            "gentoo".to_string(),
            "sudo emerge --ask media-video/pipewire".to_string(),
        ))
    } else {
        None
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_parse_os_release_arch() {
        let content = "NAME=\"Arch Linux\"\nID=arch\n";
        let (distro, cmd) = parse_os_release_info(content).unwrap();
        assert_eq!(distro, "arch");
        assert_eq!(cmd, "sudo pacman -S pipewire pipewire-pulse");
    }

    #[test]
    fn test_parse_os_release_cachyos() {
        let content = "NAME=\"CachyOS\"\nID=cachyos\nID_LIKE=arch\n";
        let (distro, cmd) = parse_os_release_info(content).unwrap();
        assert_eq!(distro, "arch");
        assert_eq!(cmd, "sudo pacman -S pipewire pipewire-pulse");
    }

    #[test]
    fn test_parse_os_release_ubuntu() {
        let content = "NAME=\"Ubuntu\"\nID=ubuntu\nID_LIKE=debian\n";
        let (distro, cmd) = parse_os_release_info(content).unwrap();
        assert_eq!(distro, "debian");
        assert_eq!(cmd, "sudo apt install pipewire pipewire-pulse");
    }

    #[test]
    fn test_parse_os_release_fedora() {
        let content = "NAME=\"Fedora Linux\"\nID=fedora\n";
        let (distro, cmd) = parse_os_release_info(content).unwrap();
        assert_eq!(distro, "fedora");
        assert_eq!(cmd, "sudo dnf install pipewire pipewire-pulseaudio");
    }

    #[test]
    fn test_parse_os_release_opensuse() {
        let content = "NAME=\"openSUSE Tumbleweed\"\nID=\"opensuse-tumbleweed\"\nID_LIKE=\"opensuse suse\"\n";
        let (distro, cmd) = parse_os_release_info(content).unwrap();
        assert_eq!(distro, "opensuse");
        assert_eq!(cmd, "sudo zypper install pipewire pipewire-pulseaudio");
    }
}
