use crate::error::AppError;
use tauri::window::Effect;
use tauri::{AppHandle, Manager, State};

use crate::server::state::ServerState;
use crate::tray::{TrayContext, TrayMenuStrings, TrayState};

#[tauri::command]
pub async fn start_server(
    app_handle: AppHandle,
    state: State<'_, ServerState>,
    port: u16,
    mode: String,
    bind_address: Option<String>,
    output_device: Option<String>,
) -> Result<String, AppError> {
    let bind_addr = bind_address.unwrap_or_else(|| "0.0.0.0".to_string());

    let mut handle_lock = state.server_handle.lock().await;
    if handle_lock.is_some() {
        return Err(AppError::server_already_running());
    }

    let config = crate::server::lifecycle::ServerConfig {
        port,
        mode,
        bind_address: bind_addr,
        output_device,
        model_dir: crate::util::find_model_dir(),
    };

    let handle = crate::server::lifecycle::ServerHandle::start(
        config,
        app_handle,
        state.dsp_settings.clone(),
        state.network_stats.clone(),
    )
    .await?;

    *handle_lock = Some(handle);
    Ok(format!("Server started on port {}", port))
}

#[tauri::command]
pub async fn stop_server(
    app: AppHandle,
    state: State<'_, ServerState>,
) -> Result<String, AppError> {
    let mut handle_lock = state.server_handle.lock().await;
    if let Some(handle) = handle_lock.take() {
        handle.stop(&app).await;
        Ok("Server stopped".to_string())
    } else {
        Err(AppError::server_not_running())
    }
}

#[tauri::command]
pub fn set_tray_strings(app: AppHandle, strings: TrayMenuStrings) -> Result<(), AppError> {
    {
        let ctx = app.state::<TrayContext>();
        *ctx.strings
            .lock()
            .map_err(|e| AppError::other(format!("Tray lock poisoned: {e}")))? = strings;
    }
    crate::tray::rebuild_menu(&app).map_err(|e| AppError::other(e.to_string()))
}

#[tauri::command]
pub fn set_tray_state(app: AppHandle, state: TrayState) -> Result<(), AppError> {
    {
        let ctx = app.state::<TrayContext>();
        *ctx.state
            .lock()
            .map_err(|e| AppError::other(format!("Tray lock poisoned: {e}")))? = state;
    }
    crate::tray::rebuild_menu(&app).map_err(|e| AppError::other(e.to_string()))
}

fn main_window<R: tauri::Runtime>(app: &AppHandle<R>) -> Result<tauri::WebviewWindow<R>, AppError> {
    app.get_webview_window("main")
        .ok_or_else(|| AppError::window("main window not found"))
}

#[tauri::command]
pub fn set_window_effects(app: AppHandle, enabled: bool) -> Result<(), AppError> {
    use tauri::window::EffectsBuilder;

    let window = app
        .get_webview_window("main")
        .ok_or_else(|| AppError::window("Main window not found"))?;

    if enabled {
        window
            .set_effects(EffectsBuilder::new().effect(Effect::Acrylic).build())
            .map_err(|e| AppError::window(e.to_string()))?;
    } else {
        window
            .set_effects(None::<tauri::utils::config::WindowEffectsConfig>)
            .map_err(|e| AppError::window(e.to_string()))?;
    }

    Ok(())
}

/// Windows-specific: custom window drag using raw Win32 API.
#[cfg(windows)]
#[tauri::command]
pub async fn start_window_drag(app: AppHandle) -> Result<(), AppError> {
    use winapi::um::winuser::{
        GetAsyncKeyState, GetCursorPos, SetWindowPos, SWP_NOACTIVATE, SWP_NOSIZE, SWP_NOZORDER,
        VK_LBUTTON,
    };

    let mut cursor_pos: winapi::shared::windef::POINT = unsafe { std::mem::zeroed() };
    if unsafe { GetCursorPos(&mut cursor_pos as *mut _) } == 0 {
        return Err(AppError::window("GetCursorPos failed"));
    }
    let start_cursor = (cursor_pos.x, cursor_pos.y);

    let window = app
        .get_webview_window("main")
        .ok_or_else(|| AppError::window("Main window not found"))?;
    let pos = window
        .outer_position()
        .map_err(|e| AppError::window(e.to_string()))?;
    let start_win = (pos.x, pos.y);

    let hwnd = window.hwnd().map_err(|e| AppError::window(e.to_string()))?;

    let _ = window.set_effects(None::<tauri::utils::config::WindowEffectsConfig>);
    drop(window);

    let app_clone = app.clone();
    let flags = SWP_NOSIZE | SWP_NOZORDER | SWP_NOACTIVATE;
    let hwnd_raw = hwnd.0 as isize;

    std::thread::spawn(move || {
        loop {
            unsafe {
                if GetAsyncKeyState(VK_LBUTTON) as i16 >= 0 {
                    break;
                }

                let mut cur: winapi::shared::windef::POINT = std::mem::zeroed();
                if GetCursorPos(&mut cur as *mut _) == 0 {
                    break;
                }

                let dx = cur.x - start_cursor.0;
                let dy = cur.y - start_cursor.1;

                SetWindowPos(
                    hwnd_raw as *mut _,
                    std::ptr::null_mut(),
                    start_win.0 + dx as i32,
                    start_win.1 + dy as i32,
                    0,
                    0,
                    flags,
                );
            }
            std::thread::sleep(std::time::Duration::from_millis(8));
        }

        if let Some(win) = app_clone.get_webview_window("main") {
            let _ = restore_acrylic(&win);
        }
    });

    Ok(())
}

#[cfg(not(windows))]
#[tauri::command]
pub async fn start_window_drag(_app: AppHandle) -> Result<(), AppError> {
    Err(AppError::window("Window drag is only supported on Windows"))
}

#[cfg(windows)]
fn restore_acrylic(window: &tauri::WebviewWindow) -> Result<(), AppError> {
    use tauri::window::EffectsBuilder;
    window
        .set_effects(EffectsBuilder::new().effect(Effect::Acrylic).build())
        .map_err(|e| AppError::window(e.to_string()))
}

#[tauri::command]
pub fn show_main_window(app: AppHandle) -> Result<(), AppError> {
    let win = main_window(&app)?;
    let _ = win.unminimize();
    win.show().map_err(|e| AppError::window(e.to_string()))?;
    win.set_focus()
        .map_err(|e| AppError::window(e.to_string()))?;
    Ok(())
}

#[tauri::command]
pub fn hide_main_window(app: AppHandle) -> Result<(), AppError> {
    let win = main_window(&app)?;
    win.hide().map_err(|e| AppError::window(e.to_string()))?;
    Ok(())
}

#[tauri::command]
pub async fn exit_app(app: AppHandle, state: State<'_, ServerState>) -> Result<(), AppError> {
    let _ = stop_server(app.clone(), state).await;
    log::info!(target: "tray", "exit_app: stopping application");
    app.exit(0);
    Ok(())
}
