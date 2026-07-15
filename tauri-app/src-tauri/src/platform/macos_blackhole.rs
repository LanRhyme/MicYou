use serde::{Deserialize, Serialize};

use std::sync::Mutex;

pub struct BlackHolePlatform {
    original_input_device: Mutex<Option<AudioDevice>>,
}

impl BlackHolePlatform {
    pub fn new() -> Self {
        Self {
            original_input_device: Mutex::new(None),
        }
    }
}

#[derive(Debug, Clone, Serialize)]
pub struct BlackHoleStatus {
    pub installed: bool,
    pub switch_audio_source: bool,
    pub device_name: Option<String>,
}

#[derive(Debug, Clone, Serialize)]
pub struct BlackHoleResult {
    pub success: bool,
    pub message: Option<String>,
}

#[derive(Debug, Clone, Deserialize)]
struct AudioDevice {
    #[serde(default)]
    id: String,
    #[serde(default)]
    name: String,
}

#[cfg(target_os = "macos")]
fn is_blackhole_name(name: &str) -> bool {
    let lower = name.to_lowercase();
    lower.contains("blackhole")
}

/// Check if BlackHole is installed by scanning cpal output devices
#[cfg(target_os = "macos")]
pub fn is_installed() -> bool {
    use cpal::traits::{DeviceTrait, HostTrait};

    let host = cpal::default_host();
    if let Ok(devices) = host.output_devices() {
        for dev in devices {
            if let Ok(name) = dev.name() {
                if is_blackhole_name(&name) {
                    return true;
                }
            }
        }
    }
    false
}

#[cfg(not(target_os = "macos"))]
pub fn is_installed() -> bool {
    false
}

/// Get the name of the first BlackHole device found
#[cfg(target_os = "macos")]
fn find_blackhole_device_name() -> Option<String> {
    use cpal::traits::{DeviceTrait, HostTrait};

    let host = cpal::default_host();
    if let Ok(devices) = host.output_devices() {
        for dev in devices {
            if let Ok(name) = dev.name() {
                if is_blackhole_name(&name) {
                    return Some(name);
                }
            }
        }
    }
    None
}

#[cfg(not(target_os = "macos"))]
fn find_blackhole_device_name() -> Option<String> {
    None
}

/// Get the path to SwitchAudioSource, checking Homebrew paths on macOS
#[cfg(target_os = "macos")]
fn get_switch_audio_source_cmd() -> std::path::PathBuf {
    let paths = [
        "/opt/homebrew/bin/SwitchAudioSource",
        "/usr/local/bin/SwitchAudioSource",
    ];
    for path in &paths {
        if std::path::Path::new(path).exists() {
            return std::path::PathBuf::from(path);
        }
    }
    std::path::PathBuf::from("SwitchAudioSource")
}

/// Check if SwitchAudioSource CLI tool is installed
#[cfg(target_os = "macos")]
pub async fn is_switch_audio_source_installed() -> bool {
    let cmd = get_switch_audio_source_cmd();
    if cmd.is_absolute() {
        cmd.exists()
    } else {
        tokio::process::Command::new("which")
            .arg("SwitchAudioSource")
            .output()
            .await
            .map(|o| o.status.success())
            .unwrap_or(false)
    }
}

#[cfg(not(target_os = "macos"))]
pub async fn is_switch_audio_source_installed() -> bool {
    false
}

#[cfg(not(target_os = "macos"))]
async fn get_current_input_device() -> Option<AudioDevice> {
    None
}

/// Get the current default input device via SwitchAudioSource
#[cfg(target_os = "macos")]
async fn get_current_input_device() -> Option<AudioDevice> {
    let cmd = get_switch_audio_source_cmd();
    let output = tokio::process::Command::new(&cmd)
        .args(["-c", "-t", "input", "-f", "json"])
        .output()
        .await
        .ok()?;

    if !output.status.success() {
        return None;
    }

    let json_str = String::from_utf8_lossy(&output.stdout);
    serde_json::from_str::<AudioDevice>(json_str.trim()).ok()
}

#[cfg(not(target_os = "macos"))]
async fn set_default_input_device(_device_id: &str) -> bool {
    false
}

/// Set the default input device via SwitchAudioSource
#[cfg(target_os = "macos")]
async fn set_default_input_device(device_id: &str) -> bool {
    let cmd = get_switch_audio_source_cmd();
    tokio::process::Command::new(&cmd)
        .args(["-t", "input", "-i", device_id])
        .output()
        .await
        .map(|o| o.status.success())
        .unwrap_or(false)
}

#[cfg(not(target_os = "macos"))]
async fn find_blackhole_input_device() -> Option<AudioDevice> {
    None
}

/// Find BlackHole device in the input device list
#[cfg(target_os = "macos")]
async fn find_blackhole_input_device() -> Option<AudioDevice> {
    let cmd = get_switch_audio_source_cmd();
    let output = tokio::process::Command::new(&cmd)
        .args(["-a", "-t", "input", "-f", "json"])
        .output()
        .await
        .ok()?;

    if !output.status.success() {
        return None;
    }

    let json_str = String::from_utf8_lossy(&output.stdout);
    let devices: Vec<AudioDevice> = serde_json::from_str(json_str.trim()).ok()?;
    devices.into_iter().find(|d| is_blackhole_name(&d.name))
}

impl BlackHolePlatform {
    /// Get full BlackHole status for the frontend
    pub async fn check_blackhole(&self) -> Result<BlackHoleStatus, String> {
        let installed = is_installed();
        let switch_audio = is_switch_audio_source_installed().await;
        let device_name = if installed {
            find_blackhole_device_name()
        } else {
            None
        };

        Ok(BlackHoleStatus {
            installed,
            switch_audio_source: switch_audio,
            device_name,
        })
    }

    /// Set BlackHole as the system default input device, saving the original
    pub async fn set_blackhole_as_input(&self) -> Result<BlackHoleResult, String> {
        if !is_installed() {
            return Ok(BlackHoleResult {
                success: false,
                message: Some("BlackHole is not installed".to_string()),
            });
        }

        if !is_switch_audio_source_installed().await {
            return Ok(BlackHoleResult {
                success: false,
                message: Some(
                    "SwitchAudioSource is not installed. Install with: brew install switchaudio-osx"
                        .to_string(),
                ),
            });
        }

        // Save current input device before switching, only if we haven't saved one already
        {
            let already_saved = self
                .original_input_device
                .lock()
                .map(|g| g.is_some())
                .unwrap_or(false);
            if !already_saved {
                if let Some(current) = get_current_input_device().await {
                    if let Ok(mut orig) = self.original_input_device.lock() {
                        if orig.is_none() {
                            *orig = Some(current);
                        }
                    }
                }
            }
        }

        // Find BlackHole in input devices
        let blackhole = find_blackhole_input_device().await;
        let device = match blackhole {
            Some(d) => d,
            None => {
                return Ok(BlackHoleResult {
                    success: false,
                    message: Some("BlackHole input device not found".to_string()),
                });
            }
        };

        let success = set_default_input_device(&device.id).await;
        Ok(BlackHoleResult {
            success,
            message: if success {
                Some(format!("Set default input to: {}", device.name))
            } else {
                Some("Failed to set default input device".to_string())
            },
        })
    }

    /// Restore the original input device (internal logic, callable from stop_server)
    pub async fn do_restore_input_device(&self) -> Result<(), String> {
        let original = {
            self.original_input_device
                .lock()
                .ok()
                .and_then(|guard| (*guard).clone())
        };

        let device = match original {
            Some(d) => d,
            None => return Ok(()),
        };

        if !is_switch_audio_source_installed().await {
            return Err("SwitchAudioSource is not installed".to_string());
        }

        let success = set_default_input_device(&device.id).await;
        if success {
            if let Ok(mut guard) = self.original_input_device.lock() {
                *guard = None;
            }
        }
        // On failure, keep the device in original_input_device for retry
        Ok(())
    }

    /// Restore the original input device (Tauri command)
    pub async fn restore_input_device(&self) -> Result<BlackHoleResult, String> {
        let has_saved = self
            .original_input_device
            .lock()
            .map(|g| g.is_some())
            .unwrap_or(false);
        if !has_saved {
            return Ok(BlackHoleResult {
                success: true,
                message: Some("No saved input device to restore".to_string()),
            });
        }

        match self.do_restore_input_device().await {
            Ok(()) => Ok(BlackHoleResult {
                success: true,
                message: Some("Restored input device".to_string()),
            }),
            Err(e) => Ok(BlackHoleResult {
                success: false,
                message: Some(e),
            }),
        }
    }
}

// ── Backward-compatible module-level API ────────────────────────────────

use std::sync::LazyLock;

static INSTANCE: LazyLock<BlackHolePlatform> = LazyLock::new(BlackHolePlatform::new);

pub async fn check_blackhole() -> Result<BlackHoleStatus, String> {
    INSTANCE.check_blackhole().await
}

pub async fn set_blackhole_as_input() -> Result<BlackHoleResult, String> {
    INSTANCE.set_blackhole_as_input().await
}

pub async fn restore_input_device() -> Result<BlackHoleResult, String> {
    INSTANCE.restore_input_device().await
}

pub async fn do_restore_input_device() -> Result<(), String> {
    INSTANCE.do_restore_input_device().await
}
