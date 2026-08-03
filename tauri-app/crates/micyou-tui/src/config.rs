use micyou_audio::dsp::AudioDspSettings;

pub fn load_settings() -> AudioDspSettings {
    tauri_app_lib::app_config::load_dsp_settings()
}

pub fn save_settings(settings: &AudioDspSettings) -> Result<(), String> {
    tauri_app_lib::app_config::save_dsp_settings(settings)
}
