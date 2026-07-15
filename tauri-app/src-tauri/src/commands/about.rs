use crate::error::AppError;
use md5::Digest;
use reqwest::Client;
use std::fs;

fn read_property_from_file(file_path: &str, key: &str) -> Option<String> {
    if let Ok(content) = fs::read_to_string(file_path) {
        for line in content.lines() {
            if line.starts_with(key) {
                let parts: Vec<&str> = line.splitn(2, '=').collect();
                if parts.len() == 2 {
                    return Some(parts[1].trim().to_string());
                }
            }
        }
    }
    None
}

fn get_local_property(key: &str) -> String {
    // In development, try reading from local.properties
    if let Some(val) = read_property_from_file("../../local.properties", key) {
        return val;
    }
    // In production, read from environment variable
    std::env::var(key).unwrap_or_default()
}

#[tauri::command]
pub fn get_app_version(app: tauri::AppHandle) -> String {
    // Use the version from Tauri's package info (embedded at compile time)
    app.package_info().version.to_string()
}

#[tauri::command]
pub async fn get_sponsors() -> Result<String, AppError> {
    let api_token = get_local_property("AIFADIAN_API_TOKEN");
    let user_id = get_local_property("AIFADIAN_USER_ID");

    if api_token.is_empty() || user_id.is_empty() {
        return Err(AppError::other("API not configured"));
    }

    let ts = crate::util::now_millis() as u64 / 1000;
    let params = r#"{"page":"1","per_page":"100"}"#;
    let sign_str = format!("{}params{}ts{}user_id{}", api_token, params, ts, user_id);
    let mut hasher = md5::Md5::new();
    md5::Digest::update(&mut hasher, sign_str.as_bytes());
    let digest = hasher.finalize();
    let sign = digest
        .iter()
        .map(|b| format!("{:02x}", b))
        .collect::<String>();

    let client = Client::new();
    let req_body = serde_json::json!({
        "user_id": user_id,
        "params": params,
        "ts": ts,
        "sign": sign
    });

    let res = client
        .post("https://afdian.com/api/open/query-sponsor")
        .json(&req_body)
        .send()
        .await
        .map_err(|e| AppError::network(e.to_string()))?;

    let text = res
        .text()
        .await
        .map_err(|e| AppError::network(e.to_string()))?;
    Ok(text)
}

#[tauri::command]
pub fn export_log(app: tauri::AppHandle) -> Result<(), AppError> {
    use std::fs;
    use tauri::Manager;
    use tauri_plugin_dialog::DialogExt;

    let log_dir = app
        .path()
        .app_log_dir()
        .map_err(|e| AppError::io(e.to_string()))?;
    let log_file = log_dir.join("micyou.log");

    if !log_file.exists() {
        return Err(AppError::io("Log file not found"));
    }

    app.dialog().file().save_file(move |file_path| {
        if let Some(path) = file_path {
            let p = path.into_path().unwrap();
            let _ = fs::copy(&log_file, p);
        }
    });

    Ok(())
}
