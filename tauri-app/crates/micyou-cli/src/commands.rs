use crate::config;
use micyou_audio::dsp::AudioDspSettings;
use std::process::exit;
use tauri_app_lib::mode_lock as lock;

pub fn cmd_devices() {
    let devices = tauri_app_lib::commands::audio::get_audio_devices();
    if devices.is_empty() {
        println!("no audio output devices found");
        return;
    }
    println!("audio output devices:");
    for (i, name) in devices.iter().enumerate() {
        println!("  {}. {name}", i + 1);
    }
}

pub fn cmd_status() {
    match lock::read_lock() {
        Some(lock_info) => {
            let mode = match lock_info.mode {
                lock::RunMode::Gui => "GUI",
                lock::RunMode::Cli => "CLI",
                lock::RunMode::Tui => "TUI",
            };
            let now = std::time::SystemTime::now()
                .duration_since(std::time::UNIX_EPOCH)
                .map(|d| d.as_secs())
                .unwrap_or(0);
            let started_ago = now.saturating_sub(lock_info.started_at);
            println!(
                "mode: {mode}\npid: {}\nstarted {}s ago\nlock file: {}",
                lock_info.pid,
                started_ago,
                lock::lock_path().display()
            );
        }
        None => {
            println!("no server running");
        }
    }
    println!("settings file: {}", config::settings_path().display());
    println!("data dir: {}", lock::data_dir().display());
}

pub fn cmd_stop() {
    match lock::read_lock() {
        Some(lock_info) if lock_info.mode == lock::RunMode::Cli => {
            println!(
                "the CLI server (pid {}) manages its own lifetime - stop it by pressing Ctrl+C in its terminal",
                lock_info.pid
            );
        }
        Some(lock_info) if lock_info.mode == lock::RunMode::Tui => {
            println!(
                "the TUI server (pid {}) manages its own lifetime - stop it by pressing q or Ctrl+C in its terminal",
                lock_info.pid
            );
        }
        Some(lock_info) if lock_info.mode == lock::RunMode::Gui => {
            println!(
                "the GUI (pid {}) is running - stop the server from the app window or tray",
                lock_info.pid
            );
        }
        Some(_) => {
            println!("unknown lock state");
        }
        None => {
            println!("no server running");
        }
    }
}

fn print_settings(settings: &AudioDspSettings) {
    let raw = serde_json::to_string_pretty(settings).unwrap_or_default();
    println!("{raw}");
}

pub fn cmd_settings_get(key: Option<String>) -> Result<(), String> {
    let settings = config::load_settings();
    match key {
        None => print_settings(&settings),
        Some(k) => {
            let value = serde_json::to_value(&settings).map_err(|e| e.to_string())?;
            match value.get(&k) {
                Some(v) => println!("{k} = {v}"),
                None => {
                    eprintln!("unknown setting: {k}");
                    return Err(format!("unknown setting: {k}"));
                }
            }
        }
    }
    Ok(())
}

pub fn cmd_settings_set(key: String, value: String) -> Result<(), String> {
    let mut settings = config::load_settings();
    let mut current = serde_json::to_value(&settings).map_err(|e| e.to_string())?;

    let parsed = parse_value(&value);
    match current.get_mut(&key) {
        Some(holder) => {
            *holder = parsed;
        }
        None => {
            return Err(format!("unknown setting: {key}"));
        }
    }
    settings = serde_json::from_value(current).map_err(|e| e.to_string())?;
    if key == "aecEnabled"
        && settings.aec_enabled
        && !tauri_app_lib::commands::audio::aec_supported()
    {
        return Err("AEC is not supported on macOS".to_string());
    }
    config::save_settings(&settings)?;
    println!("{key} = {value}");
    Ok(())
}

fn parse_value(value: &str) -> serde_json::Value {
    if let Ok(n) = value.parse::<i64>() {
        return serde_json::json!(n);
    }
    if let Ok(f) = value.parse::<f64>() {
        return serde_json::json!(f);
    }
    if value == "true" {
        return serde_json::json!(true);
    }
    if value == "false" {
        return serde_json::json!(false);
    }
    serde_json::Value::String(value.to_string())
}

pub fn cmd_chain_list() {
    let settings = config::load_settings();
    if settings.processing_chain.is_empty() {
        println!("processing chain: (empty)");
        return;
    }
    println!("processing chain:");
    for (i, stage) in settings.processing_chain.iter().enumerate() {
        println!("  {}. {stage}", i + 1);
    }
}

pub fn cmd_chain_set(chain: Vec<String>) -> Result<(), String> {
    let mut settings = config::load_settings();
    let mut normalized: Vec<String> = Vec::new();
    for item in chain {
        if item == "AEC" && !normalized.is_empty() && normalized[0] == "AEC" {
            continue; // dedupe
        }
        normalized.push(item);
    }
    settings.processing_chain = normalized;
    config::save_settings(&settings)?;
    cmd_chain_list();
    Ok(())
}

pub fn cmd_mics() {
    #[cfg(target_os = "linux")]
    {
        use tauri_app_lib::pipewire;
        let available = pipewire::is_available();
        let setup = pipewire::is_setup();
        let device_exists = pipewire::device_exists();
        println!("PipeWire status:");
        println!("  available: {available}");
        println!("  virtual sink: {device_exists}");
        if available && !device_exists {
            println!("  run `micyou serve` to auto-setup the virtual sink, or use the GUI");
        }
        if !available {
            println!("  PipeWire not detected (is pipewire-pulse running?)");
        }
        let _ = setup;
    }
    #[cfg(target_os = "macos")]
    {
        let installed = tauri_app_lib::blackhole::is_installed();
        println!("BlackHole status:");
        println!("  installed: {installed}");
        if !installed {
            println!("  install BlackHole from https://existential.audio/blackhole/");
        }
    }
    #[cfg(target_os = "windows")]
    {
        let installed = tauri_app_lib::vbcable::is_installed();
        println!("VB-CABLE status:");
        println!("  installed: {installed}");
        if !installed {
            println!("  run `micyou mics install` to install VB-CABLE");
        }
    }
    #[cfg(not(any(target_os = "linux", target_os = "macos", target_os = "windows")))]
    {
        println!("unsupported platform");
    }
}

#[cfg(target_os = "windows")]
pub async fn cmd_mics_install() -> Result<(), String> {
    let events: std::sync::Arc<dyn tauri_app_lib::events::ServerEvents> =
        std::sync::Arc::new(crate::events::CliEventSink);
    let result = tauri_app_lib::vbcable::install(events).await;
    println!(
        "{}",
        serde_json::to_string_pretty(&result).unwrap_or_default()
    );
    if result.success {
        Ok(())
    } else {
        exit(1)
    }
}

pub fn cmd_adb_devices() {
    match tauri_app_lib::adb_manager::list_adb_devices() {
        Ok(devices) if devices.is_empty() => {
            println!("no ADB devices found");
        }
        Ok(devices) => {
            println!("ADB devices:");
            for device in devices {
                println!(
                    "  {} ({}) - {}",
                    device.serial, device.state, device.description
                );
            }
        }
        Err(e) => {
            eprintln!("failed to list ADB devices: {e}");
        }
    }
}

pub fn cmd_config_path() {
    println!("config dir: {}", config::config_dir().display());
    println!("settings: {}", config::settings_path().display());
    println!("lock: {}", lock::lock_path().display());
}

pub fn cmd_server_get() {
    let prefs = tauri_app_lib::app_config::load_server_prefs();
    println!("mode: {}", prefs.mode);
    println!("port: {}", prefs.port);
    println!("webPort: {}", prefs.web_port);
    println!("bindAddress: {}", prefs.bind_address);
    println!("autoBind: {}", prefs.auto_bind);
    println!("outputDevice: {}", prefs.output_device);
    println!(
        "file: {}",
        tauri_app_lib::app_config::server_prefs_path().display()
    );
}

pub fn cmd_server_set(key: &str, value: &str) -> Result<(), String> {
    let mut prefs = tauri_app_lib::app_config::load_server_prefs();
    match key {
        "port" => {
            let v: u16 = value
                .parse()
                .map_err(|_| format!("invalid port '{value}'"))?;
            if v == 0 {
                return Err("port must be > 0".to_string());
            }
            prefs.port = v;
        }
        "webPort" => {
            let v: u16 = value
                .parse()
                .map_err(|_| format!("invalid webPort '{value}'"))?;
            prefs.web_port = v;
        }
        "mode" => {
            if !["wifi", "usb", "web"].contains(&value) {
                return Err(format!(
                    "invalid mode '{value}' (expected wifi, usb or web)"
                ));
            }
            prefs.mode = value.to_string();
        }
        "bindAddress" => {
            prefs.bind_address = value.to_string();
        }
        "autoBind" => match value {
            "true" | "1" | "yes" | "on" => prefs.auto_bind = true,
            "false" | "0" | "no" | "off" => prefs.auto_bind = false,
            _ => return Err(format!("invalid boolean '{value}'")),
        },
        "outputDevice" => {
            prefs.output_device = value.to_string();
        }
        _ => {
            return Err(format!(
                "unknown key '{key}' (expected port, webPort, mode, bindAddress, autoBind, outputDevice)"
            ));
        }
    }
    tauri_app_lib::app_config::save_server_prefs(&prefs)?;
    println!("{key} = {value}");
    Ok(())
}
