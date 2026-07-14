pub mod adb;
#[cfg(target_os = "linux")]
pub mod linux_pipewire;
pub mod macos_blackhole;
pub mod windows_vbcable;

/// Platform-specific raw socket / file descriptor handle.
/// Used to force-close active connections from another task.
#[cfg(windows)]
pub type RawSocketHandle = std::os::windows::io::RawSocket;
#[cfg(unix)]
pub type RawSocketHandle = std::os::unix::io::RawFd;
