use serde::Deserialize;
use std::sync::Mutex;
use tauri::{
    menu::{Menu, MenuItem, PredefinedMenuItem},
    tray::{MouseButton, TrayIconBuilder, TrayIconEvent},
    AppHandle, Emitter, Manager, Runtime,
};

#[derive(Deserialize, Debug, Clone, Default)]
pub struct TrayMenuStrings {
    pub tooltip: String,
    pub show: String,
    pub hide: String,
    pub start: String,
    pub stop: String,
    pub exit: String,
}

impl TrayMenuStrings {
    pub fn english_defaults() -> Self {
        Self {
            tooltip: "MicYou Desktop".to_string(),
            show: "Show App".to_string(),
            hide: "Hide App".to_string(),
            start: "Start Streaming".to_string(),
            stop: "Stop Streaming".to_string(),
            exit: "Exit".to_string(),
        }
    }
}

#[derive(Deserialize, Debug, Clone, Copy, Default)]
#[serde(rename_all = "camelCase")]
pub struct TrayState {
    pub window_visible: bool,
    pub is_streaming: bool,
}

/// Returns the localized label for the show/hide menu item given the current
/// window visibility. The click id stays the same regardless of label.
pub fn show_hide_label(state: TrayState, strings: &TrayMenuStrings) -> &str {
    if state.window_visible {
        &strings.hide
    } else {
        &strings.show
    }
}

/// Returns the localized label for the streaming toggle menu item.
pub fn stream_toggle_label(state: TrayState, strings: &TrayMenuStrings) -> &str {
    if state.is_streaming {
        &strings.stop
    } else {
        &strings.start
    }
}

pub const MENU_ID_SHOW: &str = "show";
pub const MENU_ID_TOGGLE_STREAM: &str = "toggle_stream";
pub const MENU_ID_EXIT: &str = "exit";

pub struct TrayContext {
    pub strings: Mutex<TrayMenuStrings>,
    pub state: Mutex<TrayState>,
}

impl Default for TrayContext {
    fn default() -> Self {
        Self {
            strings: Mutex::new(TrayMenuStrings::english_defaults()),
            state: Mutex::new(TrayState::default()),
        }
    }
}

struct TrayHandleStorage<R: Runtime>(Mutex<Option<tauri::tray::TrayIcon<R>>>);

pub fn build_tray<R: Runtime>(app: &AppHandle<R>) -> tauri::Result<()> {
    let ctx = app.state::<TrayContext>();
    let strings = ctx
        .strings
        .lock()
        .map_err(|e| {
            log::error!(target: "tray", "Tray strings mutex poisoned: {e}");
            tauri::Error::AssetNotFound("tray strings lock poisoned".into())
        })?
        .clone();
    let state = *ctx.state.lock().map_err(|e| {
        log::error!(target: "tray", "Tray state mutex poisoned: {e}");
        tauri::Error::AssetNotFound("tray state lock poisoned".into())
    })?;

    let menu = build_menu(app, &strings, state)?;
    let icon = app
        .default_window_icon()
        .cloned()
        .ok_or_else(|| tauri::Error::AssetNotFound("default window icon".into()))?;

    let tray = TrayIconBuilder::with_id("micyou-main-tray")
        .icon(icon)
        .icon_as_template(false)
        .tooltip(&strings.tooltip)
        .menu(&menu)
        .show_menu_on_left_click(true)
        .on_menu_event(|app, event| {
            let id = event.id().as_ref();
            log::info!(target: "tray", "menu event: {id}");
            match id {
                MENU_ID_SHOW | MENU_ID_TOGGLE_STREAM | MENU_ID_EXIT => {
                    let _ = app.emit("tray-action", id);
                }
                other => {
                    log::warn!(target: "tray", "unknown menu id: {other}");
                }
            }
        })
        .on_tray_icon_event(|tray, event| {
            if let TrayIconEvent::DoubleClick {
                button: MouseButton::Left,
                ..
            } = event
            {
                let app = tray.app_handle();
                let _ = app.emit("tray-action", MENU_ID_SHOW);
            }
        })
        .build(app)?;

    app.manage(TrayHandleStorage(Mutex::new(Some(tray))));
    Ok(())
}

pub fn rebuild_menu<R: Runtime>(app: &AppHandle<R>) -> tauri::Result<()> {
    let ctx = app.state::<TrayContext>();
    let strings = ctx
        .strings
        .lock()
        .map_err(|e| {
            log::error!(target: "tray", "Tray strings mutex poisoned (rebuild): {e}");
            tauri::Error::AssetNotFound("tray strings lock poisoned".into())
        })?
        .clone();
    let state = *ctx.state.lock().map_err(|e| {
        log::error!(target: "tray", "Tray state mutex poisoned (rebuild): {e}");
        tauri::Error::AssetNotFound("tray state lock poisoned".into())
    })?;
    let menu = build_menu(app, &strings, state)?;
    if let Some(storage) = app.try_state::<TrayHandleStorage<R>>() {
        let tray_opt = storage.0.lock().map_err(|e| {
            log::error!(target: "tray", "Tray handle mutex poisoned (rebuild): {e}");
            tauri::Error::AssetNotFound("tray handle lock poisoned".into())
        })?;
        if let Some(tray) = tray_opt.as_ref() {
            tray.set_menu(Some(menu))?;
        }
    }
    if let Some(tray) = app.tray_by_id("micyou-main-tray") {
        tray.set_tooltip(Some(&strings.tooltip))?;
    }
    Ok(())
}

fn build_menu<R: Runtime>(
    app: &AppHandle<R>,
    strings: &TrayMenuStrings,
    state: TrayState,
) -> tauri::Result<Menu<R>> {
    let show_hide = MenuItem::with_id(
        app,
        MENU_ID_SHOW,
        show_hide_label(state, strings),
        true,
        None::<&str>,
    )?;
    let toggle_stream = MenuItem::with_id(
        app,
        MENU_ID_TOGGLE_STREAM,
        stream_toggle_label(state, strings),
        true,
        None::<&str>,
    )?;
    let exit = MenuItem::with_id(app, MENU_ID_EXIT, &strings.exit, true, None::<&str>)?;
    let separator = PredefinedMenuItem::separator(app)?;
    Menu::with_items(app, &[&show_hide, &toggle_stream, &separator, &exit])
}

#[cfg(test)]
mod tests {
    use super::*;

    fn s() -> TrayMenuStrings {
        TrayMenuStrings {
            tooltip: "T".into(),
            show: "Show".into(),
            hide: "Hide".into(),
            start: "Start".into(),
            stop: "Stop".into(),
            exit: "Exit".into(),
        }
    }

    #[test]
    fn show_hide_label_uses_hide_when_visible() {
        assert_eq!(
            show_hide_label(
                TrayState {
                    window_visible: true,
                    is_streaming: false
                },
                &s()
            ),
            "Hide"
        );
    }

    #[test]
    fn show_hide_label_uses_show_when_hidden() {
        assert_eq!(
            show_hide_label(
                TrayState {
                    window_visible: false,
                    is_streaming: false
                },
                &s()
            ),
            "Show"
        );
    }

    #[test]
    fn stream_toggle_label_uses_stop_when_streaming() {
        assert_eq!(
            stream_toggle_label(
                TrayState {
                    window_visible: true,
                    is_streaming: true
                },
                &s()
            ),
            "Stop"
        );
    }

    #[test]
    fn stream_toggle_label_uses_start_when_idle() {
        assert_eq!(
            stream_toggle_label(
                TrayState {
                    window_visible: true,
                    is_streaming: false
                },
                &s()
            ),
            "Start"
        );
    }

    #[test]
    fn english_defaults_are_non_empty() {
        let d = TrayMenuStrings::english_defaults();
        assert!(!d.tooltip.is_empty());
        assert!(!d.show.is_empty() && !d.hide.is_empty());
        assert!(!d.start.is_empty() && !d.stop.is_empty());
        assert!(!d.exit.is_empty());
    }
}
