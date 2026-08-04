use std::fs;
use std::path::PathBuf;

use serde_json::Value;
use tauri::{AppHandle, Manager};

const MAX_THEME_CSS_BYTES: usize = 2 * 1024 * 1024;

fn validate_theme_id(theme_id: &str) -> Result<(), String> {
    if theme_id.is_empty()
        || theme_id.len() > 96
        || !theme_id.chars().all(|character| {
            character.is_ascii_alphanumeric() || character == '-' || character == '_'
        })
    {
        return Err("invalid theme id".to_string());
    }
    Ok(())
}

fn themes_dir(app: &AppHandle) -> Result<PathBuf, String> {
    Ok(app
        .path()
        .app_data_dir()
        .map_err(|error| error.to_string())?
        .join("themes"))
}

fn theme_dir(app: &AppHandle, theme_id: &str) -> Result<PathBuf, String> {
    validate_theme_id(theme_id)?;
    Ok(themes_dir(app)?.join(theme_id))
}

#[tauri::command]
pub fn install_theme(
    app: AppHandle,
    theme_id: String,
    manifest_json: String,
    css: String,
) -> Result<(), String> {
    let directory = theme_dir(&app, &theme_id)?;
    let manifest: Value = serde_json::from_str(&manifest_json)
        .map_err(|error| format!("invalid theme manifest: {error}"))?;

    if manifest.get("id").and_then(Value::as_str) != Some(theme_id.as_str()) {
        return Err("theme manifest id does not match theme id".to_string());
    }
    if css.is_empty() {
        return Err("theme css is empty".to_string());
    }
    if css.len() > MAX_THEME_CSS_BYTES {
        return Err("theme css is too large".to_string());
    }

    fs::create_dir_all(&directory)
        .map_err(|error| format!("create theme directory failed: {error}"))?;
    fs::write(directory.join("manifest.json"), manifest_json)
        .map_err(|error| format!("write theme manifest failed: {error}"))?;
    fs::write(directory.join("theme.css"), css)
        .map_err(|error| format!("write theme css failed: {error}"))?;
    Ok(())
}

#[tauri::command]
pub fn list_installed_themes(app: AppHandle) -> Result<Vec<String>, String> {
    let directory = themes_dir(&app)?;
    if !directory.exists() {
        return Ok(Vec::new());
    }

    let mut themes = Vec::new();
    for entry in
        fs::read_dir(directory).map_err(|error| format!("read installed themes failed: {error}"))?
    {
        let entry = entry.map_err(|error| format!("read installed theme entry failed: {error}"))?;
        if entry
            .file_type()
            .map_err(|error| error.to_string())?
            .is_dir()
        {
            let theme_id = entry.file_name().to_string_lossy().to_string();
            if validate_theme_id(&theme_id).is_ok()
                && entry.path().join("manifest.json").is_file()
                && entry.path().join("theme.css").is_file()
            {
                themes.push(theme_id);
            }
        }
    }
    themes.sort();
    Ok(themes)
}

#[tauri::command]
pub fn remove_installed_theme(app: AppHandle, theme_id: String) -> Result<(), String> {
    let directory = theme_dir(&app, &theme_id)?;
    if directory.exists() {
        fs::remove_dir_all(directory)
            .map_err(|error| format!("remove installed theme failed: {error}"))?;
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::validate_theme_id;

    #[test]
    fn accepts_safe_theme_ids() {
        assert!(validate_theme_id("default-blue").is_ok());
        assert!(validate_theme_id("dark_mode_2").is_ok());
    }

    #[test]
    fn rejects_path_traversal_theme_ids() {
        assert!(validate_theme_id("../theme").is_err());
        assert!(validate_theme_id("theme/name").is_err());
        assert!(validate_theme_id("").is_err());
    }
}
