#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

fn main() {
    // WebKitGTK 的 DMA-BUF 渲染器在 NVIDIA 专有驱动上可能分配 GBM 缓冲失败，
    // 导致窗口白屏/黑屏（日志：Failed to create GBM buffer ... 无效的参数）。
    // 仅当检测到 NVIDIA 驱动时才禁用 DMA-BUF（保留 GPU 合成），其他 GPU 保持完整
    // 硬件加速，不受影响。
    #[cfg(target_os = "linux")]
    if std::path::Path::new("/proc/driver/nvidia/version").exists() {
        std::env::set_var("WEBKIT_DISABLE_DMABUF_RENDERER", "1");
    }

    micyou_audio::init_onnx_runtime();
    tauri_app_lib::run()
}
