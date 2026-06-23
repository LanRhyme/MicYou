#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

fn main() {
    tauri_app_lib::audio::init_onnx_runtime();
    tauri_app_lib::run()
}
