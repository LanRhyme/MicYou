#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

fn main() {
    micyou_audio::init_onnx_runtime();
    tauri_app_lib::run()
}
