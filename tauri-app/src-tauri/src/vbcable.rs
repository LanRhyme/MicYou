use serde::Serialize;
#[cfg(feature = "vbcable")]
use std::path::PathBuf;
#[cfg(feature = "vbcable")]
use std::sync::atomic::{AtomicBool, Ordering};

const CABLE_OUTPUT_NAME: &str = "CABLE Output";
const CABLE_INPUT_NAME: &str = "CABLE Input";

#[derive(Debug, Clone, Serialize)]
pub struct VBCableResult {
    pub success: bool,
    pub error_type: Option<String>,
    pub message: Option<String>,
}

/// Check if VB-CABLE is installed by scanning audio devices + registry verification
#[cfg(target_os = "windows")]
pub fn is_installed() -> bool {
    use cpal::traits::{DeviceTrait, HostTrait};

    let host = cpal::default_host();

    // Phase 1: mixer detection via cpal
    let mixer_detected = if let Ok(mut devices) = host.output_devices() {
        devices.any(|dev| {
            dev.name()
                .map(|name| {
                    name.to_lowercase().contains("cable output")
                        || name.to_lowercase().contains("cable input")
                })
                .unwrap_or(false)
        })
    } else {
        false
    };

    if !mixer_detected {
        return false;
    }

    // Phase 2: registry verification
    let registry_paths = [
        "SYSTEM\\CurrentControlSet\\Services\\VB-Cable",
        "SOFTWARE\\VB-Audio\\Cable",
        "SOFTWARE\\VB-Audio\\VB-Cable",
    ];

    registry_paths.iter().any(|path| {
        use winreg::enums::HKEY_LOCAL_MACHINE;
        use winreg::RegKey;
        RegKey::predef(HKEY_LOCAL_MACHINE)
            .open_subkey(path)
            .is_ok()
    })
}

#[cfg(not(target_os = "windows"))]
pub fn is_installed() -> bool {
    false
}

#[cfg(feature = "vbcable")]
static IS_INSTALLING: AtomicBool = AtomicBool::new(false);

#[cfg(feature = "vbcable")]
const INSTALLER_URL: &str = "https://download.vb-audio.com/Download_CABLE/VBCABLE_Driver_Pack45.zip";
#[cfg(feature = "vbcable")]
const INSTALLER_NAME: &str = "VBCABLE_Setup_x64.exe";
#[cfg(feature = "vbcable")]
const INSTALLER_DIR: &str = "VBCABLE_Driver_Pack45";

#[cfg(feature = "vbcable")]
fn temp_dir() -> PathBuf {
    std::env::temp_dir().join("micyou_vbcable")
}

#[cfg(feature = "vbcable")]
fn get_installer_path() -> Option<PathBuf> {
    let base = std::env::current_dir().ok()?;
    let paths = [
        base.join(INSTALLER_DIR).join(INSTALLER_NAME),
        base.join(INSTALLER_NAME),
    ];
    paths.iter().find(|p| p.exists()).cloned()
}

#[cfg(feature = "vbcable")]
async fn download_installer(app: &tauri::AppHandle) -> Result<PathBuf, String> {
    use tauri::Emitter;

    let dir = temp_dir();
    tokio::fs::create_dir_all(&dir).await.map_err(|e| format!("create temp dir: {e}"))?;

    let zip_path = dir.join("vbcable_pack.zip");

    app.emit("vbcable-install-progress", "Downloading installer...")
        .ok();

    let bytes = reqwest::get(INSTALLER_URL)
        .await
        .map_err(|e| format!("download failed: {e}"))?
        .bytes()
        .await
        .map_err(|e| format!("read response: {e}"))?;

    tokio::fs::write(&zip_path, &bytes)
        .await
        .map_err(|e| format!("write zip: {e}"))?;

    app.emit("vbcable-install-progress", "Extracting installer...")
        .ok();

    let extract_dir = dir.join(INSTALLER_DIR);
    tokio::fs::create_dir_all(&extract_dir)
        .await
        .map_err(|e| format!("create extract dir: {e}"))?;

    let zip_path_clone = zip_path.clone();
    let extract_dir_clone = extract_dir.clone();
    tokio::task::spawn_blocking(move || {
        let file = std::fs::File::open(&zip_path_clone)
            .map_err(|e| format!("open zip: {e}"))?;
        let mut archive = zip::ZipArchive::new(file)
            .map_err(|e| format!("read zip: {e}"))?;
        archive
            .extract(&extract_dir_clone)
            .map_err(|e| format!("extract zip: {e}"))
    })
    .await
    .map_err(|e| format!("spawn blocking: {e}"))?
    .map_err(|e| e)?;

    // Clean up zip
    tokio::fs::remove_file(&zip_path).await.ok();

    // Find the installer exe
    let installer = extract_dir.join(INSTALLER_NAME);
    if installer.exists() {
        Ok(installer)
    } else {
        Err(format!("{INSTALLER_NAME} not found in extracted archive"))
    }
}

#[cfg(feature = "vbcable")]
async fn run_installer(installer_path: &PathBuf) -> Result<(), String> {
    let path_str = installer_path.to_string_lossy().to_string();
    let cmd = format!(
        "Start-Process -FilePath '{}' -ArgumentList '-i','-h' -Verb RunAs -Wait",
        path_str.replace('\'', "''")
    );

    let output = tokio::process::Command::new("powershell")
        .args(["-Command", &cmd])
        .output()
        .await
        .map_err(|e| format!("run powershell: {e}"))?;

    if !output.status.success() {
        let stderr = String::from_utf8_lossy(&output.stderr);
        if stderr.contains("elevation") || stderr.contains("administrator") {
            return Err("uac_denied".to_string());
        }
        return Err(format!("installer exit code: {}", output.status));
    }

    Ok(())
}

#[cfg(feature = "vbcable")]
async fn wait_for_device(max_secs: u64) -> bool {
    let mut waited = 0u64;
    while waited < max_secs {
        tokio::time::sleep(std::time::Duration::from_secs(5)).await;
        waited += 5;
        if is_installed() {
            return true;
        }
    }
    false
}

#[cfg(feature = "vbcable")]
fn cleanup_temp_files() {
    let dir = temp_dir();
    let _ = std::fs::remove_dir_all(&dir);
}

#[cfg(feature = "vbcable")]
async fn configure_devices(app: &tauri::AppHandle) -> Result<(), String> {
    use tauri::Emitter;
    app.emit("vbcable-install-progress", "Configuring devices...")
        .ok();
    let scripts = vec![
        "Get-PnpDevice -FriendlyName '*CABLE Output*' | Where-Object { $_.Status -eq 'OK' } | ForEach-Object { Write-Host \"Found: $($_.FriendlyName)\" }",
    ];
    for script in scripts {
        let _ = tokio::process::Command::new("powershell")
            .args(["-Command", script])
            .output()
            .await;
    }
    Ok(())
}

#[cfg(feature = "vbcable")]
pub async fn install(app: tauri::AppHandle) -> VBCableResult {
    use tauri::Emitter;

    if !IS_INSTALLING.compare_and_swap(false, true, Ordering::SeqCst) {
        return VBCableResult {
            success: false,
            error_type: Some("already_installing".to_string()),
            message: Some("Installation already in progress".to_string()),
        };
    }

    let result = install_inner(&app).await;
    IS_INSTALLING.store(false, Ordering::SeqCst);

    match &result {
        VBCableResult { success: true, .. } => {
            app.emit("vbcable-install-progress", "Installation complete").ok();
        }
        VBCableResult { error_type: Some(et), .. } => {
            app.emit("vbcable-install-progress", format!("Failed: {et}")).ok();
        }
        _ => {}
    }

    cleanup_temp_files();
    result
}

#[cfg(feature = "vbcable")]
async fn install_inner(app: &tauri::AppHandle) -> VBCableResult {
    use tauri::Emitter;

    if is_installed() {
        app.emit("vbcable-install-progress", "Configuring devices...").ok();
        if let Err(e) = configure_devices(app).await {
            return VBCableResult {
                success: true,
                error_type: Some("config_failed".to_string()),
                message: Some(format!("Installed but configuration failed: {e}")),
            };
        }
        return VBCableResult {
            success: true,
            error_type: None,
            message: Some("Already installed".to_string()),
        };
    }

    let installer_path = match download_installer(app).await {
        Ok(p) => p,
        Err(e) => {
            return VBCableResult {
                success: false,
                error_type: Some("download_failed".to_string()),
                message: Some(e),
            };
        }
    };

    app.emit("vbcable-install-progress", "Installing (requires admin approval)...").ok();

    if let Err(e) = run_installer(&installer_path).await {
        let error_type = if e == "uac_denied" { "uac_denied" } else { "install_failed" };
        return VBCableResult {
            success: false,
            error_type: Some(error_type.to_string()),
            message: Some(e),
        };
    }

    app.emit("vbcable-install-progress", "Waiting for device initialization...").ok();

    if !wait_for_device(30).await {
        return VBCableResult {
            success: false,
            error_type: Some("timeout".to_string()),
            message: Some("Device not detected after 30 seconds".to_string()),
        };
    }

    if let Err(e) = configure_devices(app).await {
        return VBCableResult {
            success: true,
            error_type: Some("config_failed".to_string()),
            message: Some(format!("Installed but configuration failed: {e}")),
        };
    }

    VBCableResult {
        success: true,
        error_type: None,
        message: Some("Installation and configuration complete".to_string()),
    }
}

#[tauri::command]
pub async fn check_vbcable() -> Result<bool, String> {
    Ok(is_installed())
}

#[cfg(feature = "vbcable")]
#[tauri::command]
pub async fn install_vbcable(app: tauri::AppHandle) -> Result<VBCableResult, String> {
    Ok(install(app).await)
}

#[cfg(not(feature = "vbcable"))]
#[tauri::command]
pub fn install_vbcable() -> Result<VBCableResult, String> {
    Ok(VBCableResult {
        success: false,
        error_type: Some("feature_disabled".to_string()),
        message: Some("VB-Cable installation feature not enabled".to_string()),
    })
}
