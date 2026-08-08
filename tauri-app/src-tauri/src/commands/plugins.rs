//! Plugin management commands for the frontend.

use crate::server::ServerState;
use micyou_plugin::manifest::UiDescriptor;
use micyou_plugin::PluginSyncTransport;
use serde::Serialize;
use tauri::Manager;
use tauri::State;

/// Frontend view of one plugin.
#[derive(Serialize, Clone)]
#[serde(rename_all = "camelCase")]
pub struct PluginView {
    pub id: String,
    pub name: String,
    pub version: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub author: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub description: Option<String>,
    pub runtime: String,
    pub kind: String,
    pub platforms: Vec<String>,
    pub capabilities: Vec<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub ui: Option<UiDescriptor>,
    pub enabled: bool,
    pub loaded: bool,
    pub dsp_node: bool,
    /// Load/enable error surfaced to the user (e.g. artifact missing).
    #[serde(skip_serializing_if = "Option::is_none")]
    pub error: Option<String>,
    /// Localized names, keyed by BCP-47 locale tag.
    #[serde(default, skip_serializing_if = "std::collections::HashMap::is_empty")]
    pub name_i18n: std::collections::HashMap<String, String>,
    /// Localized descriptions, keyed by BCP-47 locale tag.
    #[serde(default, skip_serializing_if = "std::collections::HashMap::is_empty")]
    pub description_i18n: std::collections::HashMap<String, String>,
    /// Declared dependencies on other plugins (id, version requirement).
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    pub dependencies: Vec<micyou_plugin::manifest::PluginDependency>,
    /// Declarative settings schema for automatic form generation.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub config_schema: Option<micyou_plugin::manifest::ConfigSchema>,
}

/// Cross-device sync status for the plugins page.
#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub struct PluginSyncStatus {
    /// Whether a phone device session is connected.
    pub device_connected: bool,
    /// Plugins can currently reach the remote device.
    pub transport_ready: bool,
}

/// List all installed plugins (registry + load state).
#[tauri::command]
pub fn list_plugins(state: State<'_, ServerState>) -> Result<Vec<PluginView>, String> {
    let plugins = &state.plugins;
    let manager = plugins
        .manager
        .lock()
        .map_err(|_| "plugin manager lock poisoned".to_string())?;
    let dsp_ids = plugins.dsp_registry.plugin_ids();

    let mut views: Vec<PluginView> = manager
        .entries()
        .into_iter()
        .map(|entry| {
            let m = &entry.manifest;
            let id = m.id.clone();
            PluginView {
                dsp_node: dsp_ids.contains(&id),
                loaded: manager.is_loaded(&id),
                enabled: entry.state.is_enabled(),
                error: None,
                id: m.id.clone(),
                name: m.name.clone(),
                name_i18n: m.name_i18n.clone(),
                description_i18n: m.description_i18n.clone(),
                dependencies: m.dependencies.clone(),
                config_schema: m.config_schema.clone(),
                version: m.version.clone(),
                author: m.author.clone(),
                description: m.description.clone(),
                runtime: m.runtime.to_string(),
                kind: m.kind.to_string(),
                platforms: m.platforms.clone(),
                capabilities: m.capabilities.clone(),
                ui: m.ui.clone(),
            }
        })
        .collect();

    // Re-attempt loading enabled-but-failed plugins lazily and report errors.
    let ids: Vec<String> = views
        .iter()
        .filter(|v| v.enabled && !v.loaded)
        .map(|v| v.id.clone())
        .collect();
    drop(manager);
    for id in ids {
        if let Err(e) = plugins.enable_plugin(&id) {
            if let Some(view) = views.iter_mut().find(|v| v.id == id) {
                view.error = Some(e.to_string());
            }
        }
    }
    Ok(views)
}

/// Enable or disable a plugin (loads/unloads the runtime, updates DSP nodes).
#[tauri::command]
pub fn set_plugin_enabled(
    state: State<'_, ServerState>,
    id: String,
    enabled: bool,
) -> Result<(), String> {
    let result = if enabled {
        state.plugins.enable_plugin(&id)
    } else {
        state.plugins.disable_plugin(&id)
    };
    result.map_err(|e| e.to_string())
}

/// Uninstall a plugin (deletes its directory).
#[tauri::command]
pub fn uninstall_plugin(state: State<'_, ServerState>, id: String) -> Result<(), String> {
    state
        .plugins
        .uninstall_plugin(&id)
        .map_err(|e| e.to_string())
}

/// Read a plugin's persisted config.
#[tauri::command]
pub fn get_plugin_config(
    state: State<'_, ServerState>,
    id: String,
) -> Result<serde_json::Value, String> {
    let manager = state
        .plugins
        .manager
        .lock()
        .map_err(|_| "plugin manager lock poisoned".to_string())?;
    let map = manager.plugin_config(&id).map_err(|e| e.to_string())?;
    Ok(serde_json::Value::Object(map))
}

/// Write one plugin config value.
#[tauri::command]
pub fn set_plugin_config(
    state: State<'_, ServerState>,
    id: String,
    key: String,
    value: serde_json::Value,
) -> Result<(), String> {
    // 先持久化（释放 manager 锁后再 dispatch，dispatch 会再次锁 manager）
    {
        let manager = state
            .plugins
            .manager
            .lock()
            .map_err(|_| "plugin manager lock poisoned".to_string())?;
        manager
            .set_plugin_config(&id, &key, value.clone())
            .map_err(|e| e.to_string())?;
    }
    // 通知插件配置已变更（config:changed 热更新，插件据此重新读取配置）
    let payload = serde_json::json!({ "key": key, "value": value });
    let msg = micyou_plugin::bus::PluginMessage::new(
        "host",
        &id,
        "config:changed",
        payload.to_string().into_bytes(),
    );
    state.plugins.bus.handle_incoming(&msg);
    Ok(())
}

/// Recent log lines emitted by a plugin.
#[tauri::command]
pub fn get_plugin_logs(state: State<'_, ServerState>, id: String) -> Result<Vec<String>, String> {
    Ok(state.plugins.logs.lines(&id))
}

/// Cross-device plugin sync status.
#[tauri::command]
pub fn get_plugin_sync_status(state: State<'_, ServerState>) -> Result<PluginSyncStatus, String> {
    let connected = state.plugins.sync.is_connected();
    Ok(PluginSyncStatus {
        device_connected: connected,
        transport_ready: connected,
    })
}

/// Open the plugin directory in the system file manager (helper for manual
/// installs: drop a plugin folder / .zip there).
#[tauri::command]
pub fn open_plugins_dir(
    app: tauri::AppHandle,
    state: State<'_, ServerState>,
) -> Result<String, String> {
    use tauri_plugin_opener::OpenerExt;
    let dir = state
        .plugins
        .manager
        .lock()
        .map_err(|_| "plugin manager lock poisoned".to_string())?
        .plugins_dir()
        .to_path_buf();
    std::fs::create_dir_all(&dir).map_err(|e| e.to_string())?;
    // 直接在 Rust 侧打开目录，不经过 IPC 的 ACL scope 检查。
    // 插件目录是自定义的 %APPDATA%\micyou（config_dir() 用 "micyou" 而非应用标识符），
    // 而 Tauri scope 的 $APPDATA 会拼上 com.lanrhyme.micyou，无法匹配该路径，
    // 前端 openPath 会因此抛 "Not allowed to open path"。
    app.opener()
        .open_path(dir.display().to_string(), None::<&str>)
        .map_err(|e| format!("open plugins dir: {e}"))?;
    Ok(dir.display().to_string())
}

/// Open a plugin panel in its own Tauri window (shared by the frontend
/// command and the plugin Host API `open_window`)
pub(crate) fn open_plugin_window_impl(
    app: &tauri::AppHandle,
    plugin_id: &str,
    panel_id: &str,
) -> Result<(), String> {
    let state = app
        .try_state::<ServerState>()
        .ok_or_else(|| "server state unavailable".to_string())?;
    let (title, label) = {
        let manager = state
            .plugins
            .manager
            .lock()
            .map_err(|_| "plugin manager lock poisoned".to_string())?;
        let entry = manager
            .entry(plugin_id)
            .map_err(|e| e.to_string())?
            .ok_or_else(|| format!("unknown plugin {plugin_id}"))?;
        let panel = entry
            .manifest
            .ui
            .as_ref()
            .and_then(|u| u.panels.iter().find(|p| p.id == panel_id))
            .ok_or_else(|| format!("unknown panel {panel_id}"))?;
        (
            format!("{} · {}", entry.manifest.name, panel.label),
            format!("plugin-window-{}", plugin_id.replace('.', "_")),
        )
    };
    if app.get_webview_window(&label).is_some() {
        return Ok(()); // 已在独立窗口打开
    }
    tauri::WebviewWindowBuilder::new(
        app,
        &label,
        tauri::WebviewUrl::App(format!("index.html#/plugin/{plugin_id}/{panel_id}").into()),
    )
    .title(title)
    .inner_size(520.0, 720.0)
    .min_inner_size(360.0, 480.0)
    .build()
    .map_err(|e| e.to_string())?;
    Ok(())
}

/// Open a plugin panel in its own Tauri window
#[tauri::command]
pub fn open_plugin_window(
    app: tauri::AppHandle,
    plugin_id: String,
    panel_id: String,
) -> Result<(), String> {
    open_plugin_window_impl(&app, &plugin_id, &panel_id)
}

/// Read a plugin-authored settings page (self-contained HTML file inside
/// the plugin directory, rendered by the frontend in a sandboxed iframe).
#[tauri::command]
pub fn get_plugin_panel(
    state: State<'_, ServerState>,
    plugin_id: String,
    panel_id: String,
) -> Result<String, String> {
    let manager = state
        .plugins
        .manager
        .lock()
        .map_err(|_| "plugin manager lock poisoned".to_string())?;
    let entry = manager
        .entry(&plugin_id)
        .map_err(|e| e.to_string())?
        .ok_or_else(|| format!("unknown plugin {plugin_id}"))?;
    let panel = entry
        .manifest
        .ui
        .as_ref()
        .and_then(|u| u.panels.iter().find(|p| p.id == panel_id))
        .ok_or_else(|| format!("unknown panel {panel_id}"))?;
    let path = entry.dir.join(&panel.entry);
    std::fs::read_to_string(&path).map_err(|e| format!("read {}: {e}", path.display()))
}

/// Deliver a UI action to a plugin instance (soundpad buttons etc).
/// The plugin receives `{ action, payload }` through its message entry.
#[tauri::command]
pub fn plugin_trigger(
    state: State<'_, ServerState>,
    pluginId: String,
    action: String,
    payload: Option<String>,
) -> Result<(), String> {
    // 注入逻辑在 PluginHost::trigger（payload 为空时注入 {"action":...}）
    let bytes = payload.unwrap_or_default().into_bytes();
    state
        .plugins
        .trigger(&pluginId, &action, &bytes)
        .map_err(|e| e.to_string())
}

/// Preview of a plugin zip before installation (no files are extracted).
#[derive(serde::Serialize)]
#[serde(rename_all = "camelCase")]
pub struct PluginPreview {
    pub id: String,
    pub name: String,
    pub version: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub author: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub description: Option<String>,
    pub runtime: String,
    pub kind: String,
    pub capabilities: Vec<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub license: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub homepage: Option<String>,
}

/// Peek a plugin .zip and return its manifest summary without installing.
/// The frontend shows this as a permission prompt before import_plugin.
#[tauri::command]
pub fn preview_plugin_zip(zip_path: String) -> Result<PluginPreview, String> {
    let (manifest, _prefix) = read_manifest_from_zip(&std::path::PathBuf::from(&zip_path))?;
    let id = manifest.id.clone();
    Ok(PluginPreview {
        id,
        name: manifest.name.clone(),
        version: manifest.version.clone(),
        author: manifest.author.clone(),
        description: manifest.description.clone(),
        runtime: manifest.runtime.to_string(),
        kind: format!("{:?}", manifest.kind).to_lowercase(),
        capabilities: manifest.capabilities.clone(),
        license: manifest.license.clone(),
        homepage: manifest.homepage.clone(),
    })
}

/// Extract and validate the plugin.json from a zip, returning the manifest and
/// the folder prefix that contains it (shared by preview and import).
fn read_manifest_from_zip(
    zip_path: &std::path::Path,
) -> Result<(micyou_plugin::PluginManifest, std::path::PathBuf), String> {
    let file = std::fs::File::open(zip_path).map_err(|e| format!("open zip: {e}"))?;
    let mut archive = zip::ZipArchive::new(file).map_err(|e| format!("read zip: {e}"))?;
    let mut manifest_name: Option<String> = None;
    for i in 0..archive.len() {
        let name = archive
            .by_index(i)
            .map_err(|e| format!("zip entry: {e}"))?
            .name()
            .to_string();
        if name == "plugin.json" || name.ends_with("/plugin.json") {
            manifest_name = Some(name);
            break;
        }
    }
    let manifest_name = manifest_name.ok_or("zip contains no plugin.json")?;
    let manifest_text = {
        let mut entry = archive
            .by_name(&manifest_name)
            .map_err(|e| format!("read manifest: {e}"))?;
        let mut text = String::new();
        std::io::Read::read_to_string(&mut entry, &mut text)
            .map_err(|e| format!("read manifest: {e}"))?;
        text
    };
    let manifest = micyou_plugin::PluginManifest::from_json(&manifest_text)
        .map_err(|e| format!("invalid plugin: {e}"))?;
    let prefix = std::path::Path::new(&manifest_name)
        .parent()
        .map(|p| p.to_path_buf())
        .unwrap_or_default();
    Ok((manifest, prefix))
}

/// A detected newer version of an installed plugin.
#[derive(serde::Serialize)]
#[serde(rename_all = "camelCase")]
pub struct PluginUpdate {
    pub id: String,
    pub current_version: String,
    pub latest_version: String,
    pub update_url: String,
}

/// Check every installed plugin that declares `updateUrl` for newer versions.
/// Blocking: each remote manifest is fetched with a 5s timeout.
#[tauri::command]
pub fn check_plugin_updates(state: State<'_, ServerState>) -> Result<Vec<PluginUpdate>, String> {
    let updates: Vec<PluginUpdate> = {
        let manager = state
            .plugins
            .manager
            .lock()
            .map_err(|_| "plugin manager lock poisoned".to_string())?;
        let entries = manager.entries();
        entries
            .into_iter()
            .filter_map(|entry| {
                let m = &entry.manifest;
                let url = m.update_url.as_ref()?;
                let current = semver::Version::parse(&m.version).ok()?;
                let client = reqwest::blocking::Client::builder()
                    .timeout(std::time::Duration::from_secs(5))
                    .build()
                    .ok()?;
                let text = client.get(url).send().ok()?.text().ok()?;
                let remote = micyou_plugin::PluginManifest::from_json(&text).ok()?;
                let latest = semver::Version::parse(&remote.version).ok()?;
                if latest > current {
                    Some(PluginUpdate {
                        id: m.id.clone(),
                        current_version: m.version.clone(),
                        latest_version: remote.version.clone(),
                        update_url: url.clone(),
                    })
                } else {
                    None
                }
            })
            .collect()
    };
    Ok(updates)
}

/// Update an installed plugin: fetch the remote manifest, derive the zip URL
/// (manifest URL with the filename's `.json` replaced by `.zip`, or a
/// `distribution` field), replace the install dir and re-enable.
#[tauri::command]
pub fn update_plugin(state: State<'_, ServerState>, id: String) -> Result<String, String> {
    // Resolve the update source from the installed manifest.
    let (update_url, enabled) = {
        let manager = state
            .plugins
            .manager
            .lock()
            .map_err(|_| "plugin manager lock poisoned".to_string())?;
        let entry = manager
            .entry(&id)
            .map_err(|e| e.to_string())?
            .ok_or_else(|| format!("unknown plugin {id}"))?;
        let url = entry
            .manifest
            .update_url
            .clone()
            .ok_or_else(|| format!("plugin {id} declares no updateUrl"))?;
        (url, entry.state.is_enabled())
    };

    // Fetch the remote manifest.
    let client = reqwest::blocking::Client::builder()
        .timeout(std::time::Duration::from_secs(10))
        .build()
        .map_err(|e| e.to_string())?;
    let text = client
        .get(&update_url)
        .send()
        .map_err(|e| format!("fetch update manifest: {e}"))?
        .text()
        .map_err(|e| e.to_string())?;
    let remote = micyou_plugin::PluginManifest::from_json(&text)
        .map_err(|e| format!("remote manifest invalid: {e}"))?;
    if remote.id != id {
        return Err(format!(
            "remote manifest id mismatch: {} != {id}",
            remote.id
        ));
    }
    // Derive the zip URL: same path with .json -> .zip, or `distribution`.
    let zip_url = remote
        .homepage
        .as_ref()
        .filter(|_| false)
        .map(|_| String::new())
        .unwrap_or_else(|| {
            let p = std::path::Path::new(&update_url);
            let stem = p
                .file_stem()
                .map(|s| s.to_string_lossy().to_string())
                .unwrap_or_default();
            let parent = p
                .parent()
                .map(|s| s.to_string_lossy().to_string())
                .unwrap_or_default();
            format!("{parent}/{stem}.zip")
        });

    // Download to a temp file.
    let tmp_dir = std::env::temp_dir();
    let tmp_zip = tmp_dir.join(format!("micyou-update-{id}.zip"));
    let bytes = client
        .get(&zip_url)
        .send()
        .map_err(|e| format!("download update: {e}"))?
        .bytes()
        .map_err(|e| format!("read update: {e}"))?;
    std::fs::write(&tmp_zip, &bytes).map_err(|e| format!("write temp zip: {e}"))?;

    // Disable (if running), replace the install dir, re-import and re-enable.
    state.plugins.disable_plugin(&id).ok();
    let plugins_dir = state
        .plugins
        .manager
        .lock()
        .map_err(|_| "plugin manager lock poisoned".to_string())?
        .plugins_dir()
        .to_path_buf();
    let dest = plugins_dir.join(&id);
    if dest.exists() {
        std::fs::remove_dir_all(&dest).map_err(|e| format!("remove old install: {e}"))?;
    }
    import_plugin_zip(&tmp_zip, &plugins_dir).map_err(|e| format!("install update: {e}"))?;
    let _ = std::fs::remove_file(&tmp_zip);
    if enabled {
        state
            .plugins
            .enable_plugin(&id)
            .map_err(|e| e.to_string())?;
    }
    Ok(remote.version)
}

/// Host UI language (from ui.json), so plugin panels can localize themselves.
#[tauri::command]
pub fn get_app_locale() -> String {
    crate::app_config::load_ui_prefs().language
}

/// Return the dynamic sidebar-panel icons set by the plugin via
/// `set_panel_icon` (panel id -> icon string).
#[tauri::command]
pub fn get_plugin_panel_icons(
    state: State<'_, ServerState>,
    id: String,
) -> std::collections::HashMap<String, String> {
    state
        .plugins
        .panel_icons
        .lock()
        .map(|m| m.get(&id).cloned().unwrap_or_default())
        .unwrap_or_default()
}

/// Fetch a remote manifest (market) and return a preview without installing.
#[tauri::command]
pub fn preview_plugin_from_url(manifest_url: String) -> Result<PluginPreview, String> {
    let client = reqwest::blocking::Client::builder()
        .timeout(std::time::Duration::from_secs(5))
        .build()
        .map_err(|e| format!("http client: {e}"))?;
    let text = client
        .get(&manifest_url)
        .send()
        .map_err(|e| format!("fetch manifest: {e}"))?
        .error_for_status()
        .map_err(|e| format!("fetch manifest: {e}"))?
        .text()
        .map_err(|e| format!("read manifest: {e}"))?;
    let manifest = micyou_plugin::PluginManifest::from_json(&text)
        .map_err(|e| format!("invalid plugin manifest: {e}"))?;
    Ok(PluginPreview {
        id: manifest.id.clone(),
        name: manifest.name.clone(),
        version: manifest.version.clone(),
        author: manifest.author.clone(),
        description: manifest.description.clone(),
        runtime: manifest.runtime.to_string(),
        kind: format!("{:?}", manifest.kind).to_lowercase(),
        capabilities: manifest.capabilities.clone(),
        license: manifest.license.clone(),
        homepage: manifest.homepage.clone(),
    })
}

/// Download a plugin zip from the market and install it (permission prompt
/// happens in the frontend via preview_plugin_from_url first).
#[tauri::command]
pub fn install_plugin_from_url(
    state: State<'_, ServerState>,
    zip_url: String,
) -> Result<String, String> {
    let client = reqwest::blocking::Client::builder()
        .timeout(std::time::Duration::from_secs(30))
        .build()
        .map_err(|e| format!("http client: {e}"))?;
    let bytes = client
        .get(&zip_url)
        .send()
        .map_err(|e| format!("下载插件失败：{e}"))?
        .error_for_status()
        .map_err(|e| format!("下载插件失败（清单可能已过期，请刷新市场后重试）：{e}"))?
        .bytes()
        .map_err(|e| format!("读取插件包失败：{e}"))?;
    // 临时文件下载，随后走标准 zip 导入（含路径穿越防护）
    let tmp = std::env::temp_dir().join(format!("micyou-market-{}.zip", std::process::id()));
    std::fs::write(&tmp, &bytes).map_err(|e| format!("write temp zip: {e}"))?;
    let result = (|| {
        let plugins_dir = state
            .plugins
            .manager
            .lock()
            .map_err(|_| "plugin manager lock poisoned".to_string())?
            .plugins_dir()
            .to_path_buf();
        std::fs::create_dir_all(&plugins_dir).map_err(|e| e.to_string())?;
        let id = match import_plugin_zip(&tmp, &plugins_dir) {
            Ok(id) => id,
            Err(e) if e.contains("already installed") => {
                // 幂等：已安装视为成功，前端随后刷新列表
                let manifest = read_manifest_from_zip(&tmp).map_err(|e| e.to_string())?.0;
                manifest.id
            }
            Err(e) => return Err(e),
        };
        let mut manager = state
            .plugins
            .manager
            .lock()
            .map_err(|_| "plugin manager lock poisoned".to_string())?;
        let _ = manager.discover_plugin(plugins_dir.join(&id));
        Ok::<String, String>(id)
    })();
    let _ = std::fs::remove_file(&tmp);
    let id = result?;
    // 权限已在前端确认，安装成功后自动启用（失败不阻断安装，用户可手动启用）
    if let Err(e) = state.plugins.enable_plugin(&id) {
        log::warn!("[plugins] auto-enable after install failed for {id}: {e}");
    }
    Ok(id)
}

/// Import a plugin from a `.zip` file or a plugin directory.
///
/// The source manifest is validated first; the payload is then copied into
/// the plugins dir under the plugin id. Returns the imported plugin id.
#[tauri::command]
pub fn import_plugin(state: State<'_, ServerState>, source: String) -> Result<String, String> {
    let src = std::path::PathBuf::from(source);
    if !src.exists() {
        return Err(format!("source not found: {}", src.display()));
    }
    let plugins_dir = state
        .plugins
        .manager
        .lock()
        .map_err(|_| "plugin manager lock poisoned".to_string())?
        .plugins_dir()
        .to_path_buf();
    std::fs::create_dir_all(&plugins_dir).map_err(|e| e.to_string())?;

    let id = if src.is_dir() {
        import_plugin_dir(&src, &plugins_dir)
    } else if src
        .extension()
        .map(|e| e.eq_ignore_ascii_case("zip"))
        .unwrap_or(false)
    {
        import_plugin_zip(&src, &plugins_dir)
    } else {
        return Err("unsupported source: expected a directory or a .zip file".into());
    }
    .map_err(|e| e.to_string())?;

    // Register the new entry so it appears immediately without a rescan.
    {
        let mut manager = state
            .plugins
            .manager
            .lock()
            .map_err(|_| "plugin manager lock poisoned".to_string())?;
        manager
            .discover_plugin(plugins_dir.join(&id))
            .map_err(|e| e.to_string())?;
    }
    // 权限已确认，安装成功后自动启用（失败不阻断安装）
    if let Err(e) = state.plugins.enable_plugin(&id) {
        log::warn!("[plugins] auto-enable after import failed for {id}: {e}");
    }
    Ok(id)
}

/// Copy a plugin directory (validated) into the plugins dir.
fn import_plugin_dir(src: &std::path::Path, dest_root: &std::path::Path) -> Result<String, String> {
    let manifest = micyou_plugin::PluginManifest::load_from_dir(src)
        .map_err(|e| format!("invalid plugin: {e}"))?;
    let id = manifest.id.clone();
    let dest = dest_root.join(&id);
    if dest.exists() {
        return Err(format!("plugin {id} already installed"));
    }
    copy_dir_recursive(src, &dest).map_err(|e| format!("copy failed: {e}"))?;
    Ok(id)
}

/// Import a `.zip` plugin: peek the manifest for validation + id, then extract
/// with path-traversal protection into `dest_root/<id>/`.
fn import_plugin_zip(
    zip_path: &std::path::Path,
    dest_root: &std::path::Path,
) -> Result<String, String> {
    let (manifest, prefix) = read_manifest_from_zip(zip_path)?;
    let id = manifest.id.clone();
    let dest = dest_root.join(&id);
    if dest.exists() {
        return Err(format!("plugin {id} already installed"));
    }
    std::fs::create_dir_all(&dest).map_err(|e| format!("create dir: {e}"))?;

    let file = std::fs::File::open(zip_path).map_err(|e| format!("open zip: {e}"))?;
    let mut archive = zip::ZipArchive::new(file).map_err(|e| format!("read zip: {e}"))?;

    for i in 0..archive.len() {
        let mut entry = archive.by_index(i).map_err(|e| format!("zip entry: {e}"))?;
        // `enclosed_name` rejects absolute paths and `..` traversal
        let Some(rel) = entry.enclosed_name() else {
            continue;
        };
        let rel = if rel.starts_with(&prefix) {
            rel.strip_prefix(&prefix).unwrap_or(&rel).to_path_buf()
        } else {
            rel
        };
        let target = dest.join(&rel);
        if entry.is_dir() {
            std::fs::create_dir_all(&target).map_err(|e| format!("mkdir: {e}"))?;
        } else {
            if let Some(parent) = target.parent() {
                std::fs::create_dir_all(parent).map_err(|e| format!("mkdir: {e}"))?;
            }
            let mut out =
                std::fs::File::create(&target).map_err(|e| format!("create file: {e}"))?;
            std::io::copy(&mut entry, &mut out).map_err(|e| format!("extract: {e}"))?;
        }
    }
    Ok(id)
}

/// Recursive directory copy (no symlink following).
fn copy_dir_recursive(src: &std::path::Path, dest: &std::path::Path) -> std::io::Result<()> {
    std::fs::create_dir_all(dest)?;
    for entry in std::fs::read_dir(src)? {
        let entry = entry?;
        let from = entry.path();
        let to = dest.join(entry.file_name());
        if entry.file_type()?.is_dir() {
            copy_dir_recursive(&from, &to)?;
        } else {
            std::fs::copy(&from, &to)?;
        }
    }
    Ok(())
}
