use reqwest::Client;
use md5::Digest;
use std::fs;
use std::time::{SystemTime, UNIX_EPOCH};

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
    if let Some(val) = read_property_from_file("../../local.properties", key) {
        return val;
    }
    std::env::var(key).unwrap_or_default()
}

#[tauri::command]
pub fn get_app_version() -> String {
    if let Some(val) = read_property_from_file("../../gradle.properties", "project.version") {
        return val;
    }
    "0.1.0".to_string()
}

#[tauri::command]
pub async fn get_sponsors() -> Result<String, String> {
    let api_token = get_local_property("AIFADIAN_API_TOKEN");
    let user_id = get_local_property("AIFADIAN_USER_ID");

    if api_token.is_empty() || user_id.is_empty() {
        return Err("API not configured".to_string());
    }

    let ts = SystemTime::now().duration_since(UNIX_EPOCH).unwrap().as_secs();
    let params = r#"{"page":"1","per_page":"100"}"#;
    let sign_str = format!("{}params{}ts{}user_id{}", api_token, params, ts, user_id);
    let mut hasher = md5::Md5::new();
    md5::Digest::update(&mut hasher, sign_str.as_bytes());
    let digest = hasher.finalize();
    let sign = digest.iter().map(|b| format!("{:02x}", b)).collect::<String>();

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
        .map_err(|e| e.to_string())?;

    let text = res.text().await.map_err(|e| e.to_string())?;
    Ok(text)
}

#[tauri::command]
pub fn export_log(app: tauri::AppHandle) -> Result<(), String> {
    use tauri_plugin_dialog::DialogExt;
    use tauri::Manager;
    use std::fs;

    let log_dir = app.path().app_log_dir().map_err(|e| e.to_string())?;
    let log_file = log_dir.join("micyou.log");

    if !log_file.exists() {
        return Err("Log file not found".to_string());
    }

    app.dialog().file().save_file(move |file_path| {
        if let Some(path) = file_path {
            let p = path.into_path().unwrap();
            let _ = fs::copy(&log_file, p);
        }
    });

    Ok(())
}
