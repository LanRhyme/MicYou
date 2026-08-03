use serde::{Deserialize, Serialize};
use std::fs;
use std::io::Write;
use std::path::PathBuf;

/// Mode lock: guarantees GUI, CLI and TUI never run the audio server at the same time.
/// Stored at the platform data dir as `mode.lock` with JSON `{ mode, pid }`.
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub enum RunMode {
    #[serde(rename = "gui")]
    Gui,
    #[serde(rename = "cli")]
    Cli,
    #[serde(rename = "tui")]
    Tui,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ModeLock {
    pub mode: RunMode,
    pub pid: u32,
    pub started_at: u64,
}

pub fn data_dir() -> PathBuf {
    #[cfg(target_os = "windows")]
    {
        std::env::var("APPDATA")
            .map(PathBuf::from)
            .unwrap_or_else(|_| PathBuf::from("."))
            .join("micyou")
    }
    #[cfg(not(target_os = "windows"))]
    {
        std::env::var("XDG_DATA_HOME")
            .ok()
            .filter(|p| !p.is_empty())
            .map(PathBuf::from)
            .or_else(|| {
                std::env::var("HOME")
                    .ok()
                    .map(|h| PathBuf::from(h).join(".local/share"))
            })
            .unwrap_or_else(|| PathBuf::from("."))
            .join("micyou")
    }
}

pub fn lock_path() -> PathBuf {
    data_dir().join("mode.lock")
}

/// Public wrapper so GUI/CLI/TUI commands can check liveness.
pub fn pid_alive_public(pid: u32) -> bool {
    pid_alive(pid)
}

/// Returns true when a process with `pid` is alive on this system.
fn pid_alive(pid: u32) -> bool {
    if pid == 0 {
        return false;
    }
    #[cfg(unix)]
    {
        // Signal 0 performs existence check without delivering a signal
        unsafe { libc::kill(pid as i32, 0) == 0 }
    }
    #[cfg(windows)]
    {
        use winapi::um::processthreadsapi::OpenProcess;
        use winapi::um::winnt::PROCESS_QUERY_LIMITED_INFORMATION;
        let handle = unsafe { OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION, 0, pid) };
        if handle.is_null() {
            return false;
        }
        unsafe { winapi::um::handleapi::CloseHandle(handle) };
        true
    }
    #[cfg(not(any(unix, windows)))]
    {
        false
    }
}

pub fn read_lock() -> Option<ModeLock> {
    let path = lock_path();
    let raw = fs::read_to_string(path).ok()?;
    serde_json::from_str(&raw).ok()
}

/// Acquire the mode lock for `mode`. Returns Ok(()) when acquired or the lock
/// belongs to a dead process (stale lock is cleaned up). Returns Err with a
/// helpful message when another live process owns the lock.
pub fn acquire(mode: RunMode) -> Result<(), String> {
    let dir = data_dir();
    fs::create_dir_all(&dir)
        .map_err(|e| format!("cannot create data dir {}: {e}", dir.display()))?;

    if let Some(existing) = read_lock() {
        if existing.mode == mode && existing.pid == std::process::id() {
            return Ok(());
        }
        if pid_alive(existing.pid) {
            let other = match existing.mode {
                RunMode::Gui => "MicYou GUI",
                RunMode::Cli => "MicYou CLI",
                RunMode::Tui => "MicYou TUI",
            };
            return Err(format!(
                "{other} is already running (pid {})\n\
                 Use the GUI or tray to switch modes, or stop the other process first",
                existing.pid
            ));
        }
        // Stale lock from a dead process: overwrite
    }

    let lock = ModeLock {
        mode,
        pid: std::process::id(),
        started_at: std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .map(|d| d.as_secs())
            .unwrap_or(0),
    };
    let path = lock_path();
    let mut file = fs::File::create(&path).map_err(|e| format!("cannot create lock: {e}"))?;
    let raw = serde_json::to_string_pretty(&lock).map_err(|e| e.to_string())?;
    file.write_all(raw.as_bytes()).map_err(|e| e.to_string())?;
    Ok(())
}

/// Remove the mode lock if we own it.
pub fn release() {
    if let Some(existing) = read_lock() {
        if existing.pid == std::process::id() {
            let _ = fs::remove_file(lock_path());
        }
    }
}

#[cfg(test)]
mod tests {
    use super::RunMode;

    #[test]
    fn tui_mode_has_stable_lock_value() {
        assert_eq!(serde_json::to_string(&RunMode::Tui).unwrap(), "\"tui\"");
        assert_eq!(
            serde_json::from_str::<RunMode>("\"tui\"").unwrap(),
            RunMode::Tui
        );
    }
}
