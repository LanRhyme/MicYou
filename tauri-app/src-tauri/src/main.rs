// Prevents additional console window on Windows in release, DO NOT REMOVE!!
#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

fn main() {
    ort::set_api(ort_tract::api());
    tauri_app_lib::run()
}
