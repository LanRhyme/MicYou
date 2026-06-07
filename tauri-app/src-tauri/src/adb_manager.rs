use std::process::Command;
use std::env;
use std::path::PathBuf;

pub fn find_adb() -> Option<PathBuf> {
    // 1. Try PATH
    if let Ok(path) = env::var("PATH") {
        let separator = if cfg!(windows) { ";" } else { ":" };
        let executable = if cfg!(windows) { "adb.exe" } else { "adb" };
        
        for dir in path.split(separator) {
            let mut adb_path = PathBuf::from(dir);
            adb_path.push(executable);
            if adb_path.exists() {
                return Some(adb_path);
            }
        }
    }

    // 2. Try Common Locations
    let common_paths = if cfg!(windows) {
        vec![
            format!("{}\\Android\\Sdk\\platform-tools\\adb.exe", env::var("LOCALAPPDATA").unwrap_or_default()),
            format!("{}\\AppData\\Local\\Android\\Sdk\\platform-tools\\adb.exe", env::var("USERPROFILE").unwrap_or_default()),
            "C:\\Android\\sdk\\platform-tools\\adb.exe".to_string(),
        ]
    } else {
        vec![
            format!("{}/Android/Sdk/platform-tools/adb", env::var("HOME").unwrap_or_default()),
            "/usr/bin/adb".to_string(),
            "/usr/local/bin/adb".to_string(),
            "/opt/android-sdk/platform-tools/adb".to_string(),
        ]
    };

    for path in common_paths {
        if !path.is_empty() {
            let adb_path = PathBuf::from(path);
            if adb_path.exists() {
                return Some(adb_path);
            }
        }
    }

    None
}

pub fn run_adb_reverse(port: u16) -> Result<(), String> {
    let adb = find_adb().ok_or("ADB not found in PATH or common locations")?;
    
    let output = Command::new(adb)
        .arg("reverse")
        .arg(format!("tcp:{}", port))
        .arg(format!("tcp:{}", port))
        .output()
        .map_err(|e| format!("Failed to execute adb command: {}", e))?;

    if output.status.success() {
        Ok(())
    } else {
        let err_msg = String::from_utf8_lossy(&output.stderr);
        Err(format!("ADB reverse failed: {}", err_msg))
    }
}
