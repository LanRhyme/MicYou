# Audio Inspector（WASM 示例）

纯 WASM 音频状态监视器：每 2 秒采样 `audio_state` 与 `connected_devices`，持久化到配置，面板轮询展示

- 运行时：WASM（wasmi 沙箱）
- 演示 API：set_interval / audio_state / connected_devices / set_config / set_panel_icon / 面板
- 源码：`audioinspector.wat`（编译需 wat2wasm 或 wat crate）
