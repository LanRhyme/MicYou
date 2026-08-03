use micyou_audio::dsp::AudioDspSettings;

// All settings persistence lives in tauri_app_lib::app_config so the GUI and CLI
// share one settings.json / ui.json / theme.json under ~/.config/micyou.

pub fn config_dir() -> std::path::PathBuf {
    tauri_app_lib::app_config::config_dir()
}

pub fn settings_path() -> std::path::PathBuf {
    tauri_app_lib::app_config::settings_path()
}

pub fn load_settings() -> AudioDspSettings {
    tauri_app_lib::app_config::load_dsp_settings()
}

pub fn save_settings(settings: &AudioDspSettings) -> Result<(), String> {
    tauri_app_lib::app_config::save_dsp_settings(settings)
}
