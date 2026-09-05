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

use md5::Digest;
use reqwest::Client;
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
    env!("CARGO_PKG_VERSION").to_string()
}

fn get_mirror_os() -> &'static str {
    if cfg!(target_os = "windows") {
        "win"
    } else if cfg!(target_os = "macos") {
        "macos"
    } else {
        "linux"
    }
}

fn get_mirror_arch() -> &'static str {
    if cfg!(target_arch = "aarch64") {
        "arm64"
    } else {
        "x64"
    }
}

#[derive(serde::Deserialize)]
struct MirrorChyanResponse {
    code: i32,
    #[allow(dead_code)]
    msg: Option<String>,
    data: Option<MirrorChyanData>,
}

#[derive(serde::Deserialize)]
struct MirrorChyanData {
    version_name: Option<String>,
    #[allow(dead_code)]
    version_number: Option<i64>,
    release_note: Option<String>,
    url: Option<String>,
    cdk_expired_time: Option<i64>,
}

#[derive(serde::Serialize)]
#[serde(rename_all = "camelCase")]
pub struct UpdateCheckResult {
    pub has_update: bool,
    pub current_version: String,
    pub latest_version: String,
    pub release_url: String,
    pub release_notes: Option<String>,
    pub is_mirror: bool,
    pub cdk_expired_time: Option<i64>,
}

#[tauri::command]
pub async fn check_app_update(cdk: Option<String>) -> Result<UpdateCheckResult, String> {
    let current_version = env!("CARGO_PKG_VERSION");
    let current_semver = semver::Version::parse(current_version)
        .unwrap_or_else(|_| semver::Version::new(0, 0, 0));

    let client = Client::builder()
        .user_agent(format!("MicYou-Desktop/{}", current_version))
        .timeout(std::time::Duration::from_secs(10))
        .build()
        .map_err(|e| e.to_string())?;

    // 1. If CDK is provided, try MirrorChyan API first
    if let Some(cdk_str) = cdk.as_ref().map(|s| s.trim()).filter(|s| !s.is_empty()) {
        let os = get_mirror_os();
        let arch = get_mirror_arch();
        let mirror_url = format!(
            "https://mirrorchyan.com/api/resources/MicYou/latest?os={}&arch={}&cdk={}",
            os, arch, cdk_str
        );

        if let Ok(res) = client.get(&mirror_url).send().await {
            if res.status().is_success() {
                if let Ok(resp) = res.json::<MirrorChyanResponse>().await {
                    if resp.code == 0 {
                        if let Some(data) = resp.data {
                            if let Some(download_url) = data.url.filter(|u| !u.is_empty()) {
                                let latest_version = data
                                    .version_name
                                    .as_deref()
                                    .unwrap_or("")
                                    .trim_start_matches('v')
                                    .to_string();
                                let latest_semver = semver::Version::parse(&latest_version)
                                    .unwrap_or_else(|_| semver::Version::new(0, 0, 0));
                                let has_update = latest_semver > current_semver;

                                return Ok(UpdateCheckResult {
                                    has_update,
                                    current_version: current_version.to_string(),
                                    latest_version,
                                    release_url: download_url,
                                    release_notes: data.release_note,
                                    is_mirror: true,
                                    cdk_expired_time: data.cdk_expired_time,
                                });
                            }
                        }
                    }
                }
            }
        }
    }

    // 2. Try GitHub Release API (includes changelog notes if available)
    let api_res = client
        .get("https://api.github.com/repos/LanRhyme/MicYou/releases/latest")
        .header("Accept", "application/vnd.github+json")
        .send()
        .await;

    if let Ok(res) = api_res {
        if res.status().is_success() {
            if let Ok(json) = res.json::<serde_json::Value>().await {
                if let Some(tag) = json.get("tag_name").and_then(|t| t.as_str()) {
                    let latest_version = tag.trim_start_matches('v').to_string();
                    let latest_semver = semver::Version::parse(&latest_version)
                        .unwrap_or_else(|_| semver::Version::new(0, 0, 0));
                    let has_update = latest_semver > current_semver;
                    let release_url = json
                        .get("html_url")
                        .and_then(|u| u.as_str())
                        .unwrap_or("https://github.com/LanRhyme/MicYou/releases/latest")
                        .to_string();
                    let release_notes = json.get("body").and_then(|b| b.as_str()).map(|s| s.to_string());

                    return Ok(UpdateCheckResult {
                        has_update,
                        current_version: current_version.to_string(),
                        latest_version,
                        release_url,
                        release_notes,
                        is_mirror: false,
                        cdk_expired_time: None,
                    });
                }
            }
        }
    }

    // 3. Fallback to website redirect (GitHub releases/latest -> /releases/tag/vX.Y.Z)
    // Avoids GitHub API unauthenticated 60 req/hr rate limiting (HTTP 403)
    let web_res = client
        .get("https://github.com/LanRhyme/MicYou/releases/latest")
        .send()
        .await
        .map_err(|e| format!("网络请求失败: {e}"))?;

    let final_url = web_res.url().as_str();
    if let Some(tag) = final_url.split("/tag/").nth(1) {
        let tag = tag.trim_matches('/');
        let latest_version = tag.trim_start_matches('v').to_string();
        let latest_semver = semver::Version::parse(&latest_version)
            .unwrap_or_else(|_| semver::Version::new(0, 0, 0));
        let has_update = latest_semver > current_semver;

        return Ok(UpdateCheckResult {
            has_update,
            current_version: current_version.to_string(),
            latest_version,
            release_url: final_url.to_string(),
            release_notes: None,
            is_mirror: false,
            cdk_expired_time: None,
        });
    }

    Err("无法获取最新版本信息".to_string())
}

#[tauri::command]
pub async fn get_sponsors() -> Result<String, String> {
    let api_token = get_local_property("AIFADIAN_API_TOKEN");
    let user_id = get_local_property("AIFADIAN_USER_ID");

    if api_token.is_empty() || user_id.is_empty() {
        return Err("API not configured".to_string());
    }

    let ts = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap()
        .as_secs();
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
        .map_err(|e| e.to_string())?;

    let text = res.text().await.map_err(|e| e.to_string())?;
    Ok(text)
}

#[tauri::command]
pub fn export_log(app: tauri::AppHandle) -> Result<(), String> {
    use std::fs;
    use tauri::Manager;
    use tauri_plugin_dialog::DialogExt;

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

#[tauri::command]
pub fn get_log_path(app: tauri::AppHandle) -> Result<String, String> {
    use tauri::Manager;
    let log_dir = app.path().app_log_dir().map_err(|e| e.to_string())?;
    let log_file = log_dir.join("micyou.log");
    Ok(log_file.display().to_string())
}

#[tauri::command]
pub fn get_log_content(app: tauri::AppHandle) -> Result<String, String> {
    use tauri::Manager;
    let log_dir = app.path().app_log_dir().map_err(|e| e.to_string())?;
    let log_file = log_dir.join("micyou.log");
    if !log_file.exists() {
        return Ok(String::new());
    }
    std::fs::read_to_string(&log_file).map_err(|e| e.to_string())
}

#[tauri::command]
pub fn open_log_dir(app: tauri::AppHandle) -> Result<String, String> {
    use tauri::Manager;
    use tauri_plugin_opener::OpenerExt;
    let log_dir = app.path().app_log_dir().map_err(|e| e.to_string())?;
    std::fs::create_dir_all(&log_dir).map_err(|e| e.to_string())?;
    app.opener()
        .open_path(log_dir.display().to_string(), None::<&str>)
        .map_err(|e| format!("open log dir: {e}"))?;
    Ok(log_dir.display().to_string())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_get_app_version() {
        let version = get_app_version();
        assert_eq!(version, env!("CARGO_PKG_VERSION"));
        assert_eq!(version, "2.0.2");
    }

    #[test]
    fn test_get_mirror_os_and_arch() {
        let os = get_mirror_os();
        let arch = get_mirror_arch();
        assert!(["win", "macos", "linux"].contains(&os));
        assert!(["x64", "arm64"].contains(&arch));
    }
}
