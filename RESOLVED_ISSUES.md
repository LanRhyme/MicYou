# 已处理 Issue 记录

本文档用于记录已修复并待关闭或已解决的 GitHub Issue

---

## Issue #325

- **标题**: [Bug]: 手机端 -- 删除IP 的时候，删除到最后一个数字，删除不掉，而且光标会跳到数字前面
- **链接**: https://github.com/LanRhyme/MicYou/issues/325
- **类型**: Bug
- **影响平台**: Android
- **原因分析**: `AudioStreamViewModel.setIp` 在接收到空输入时使用了 `ip.ifBlank { _uiState.value.ipAddress }`，导致用户清空输入框时状态值被重置为删除前的最后一位字符，使得 Compose BasicTextField 内部状态检测到值未变，光标被迫跳至首位
- **修复方案**:
  1. `setIp` 允许空字符串，正常更新 `_uiState` 中的 `ipAddress` 为用户输入（包括空字符串），使输入框可完整清空
  2. 仅在 `ip.isNotBlank()` 时持久化保存至 `settings`，避免持久化空字符串
  3. `startStreamInternal` 维持原有的空 IP 错误提示与拦截机制
- **涉及文件**:
  - `composeApp/src/main/kotlin/com/lanrhyme/micyou/viewmodel/AudioStreamViewModel.kt`
- **状态**: 待验证 / 待关闭
- **建议关闭留言**:
  ```markdown
  Fixed in branch `fix/issue-fixes`
  - Allowed blank string in `AudioStreamViewModel.setIp` so users can delete all characters in the IP input field
  - Persist to preferences only when non-blank
  ```

---

## Issue #323

- **标题**: [Bug]: Transparent window on launch (fixed with WEBKIT_DISABLE_DMABUF_RENDERER=1)
- **链接**: https://github.com/LanRhyme/MicYou/issues/323
- **类型**: Bug
- **影响平台**: Linux (WebKitGTK / NVIDIA / 部分窗口管理器如 bspwm + picom)
- **原因分析**: WebKitGTK 默认启用的 DMA-BUF 硬件加速渲染路径在特定显卡驱动或合成器环境下无法正常构建 framebuffer，导致窗口透明或黑屏
- **修复方案**: 在 Linux 平台下默认设置 `WEBKIT_DISABLE_DMABUF_RENDERER=1`（若用户未显式指定），回退至稳定且依然具备硬件加速的共享内存路径
- **涉及文件**:
  - `tauri-app/src-tauri/src/main.rs`
- **状态**: 待验证 / 待关闭
- **建议关闭留言**:
  ```markdown
  Fixed in branch `fix/issue-fixes` by disabling WebKitGTK DMA-BUF renderer by default on Linux when not explicitly set
  ```

---

## Issue #322

- **标题**: Windows 下未正确选择虚拟音频设备时会回落到物理音响，导致严重回声；耳返开关无法解决
- **链接**: https://github.com/LanRhyme/MicYou/issues/322
- **类型**: Enhancement / Bug
- **影响平台**: Windows / macOS / Linux
- **原因分析**:
  1. `set_monitoring` 指令此前仅更新了 ServerState 状态变量，未将开关指令分发到 `AudioOutputHandle`，导致耳返监听流未实际启停
  2. 输出设备设置页面中，`auto` 模式无论是否检测到虚拟设备均显示“虚拟音频路由已激活”，且在选择物理扬声器或回落到物理设备时缺少回声与啸叫风险提示
  3. 后端在自动检测虚拟设备失败回落到默认设备时缺少告警日志
- **修复方案**:
  1. 在 `commands/audio.rs` 的 `set_monitoring` 中调用 `state.audio_output.set_monitoring(enabled)`，使耳返开关联路生效
  2. 在 `SettingsDialog.vue` 中重构虚拟/物理设备检测逻辑，当选择物理扬声器或自动回落到物理扬声器时展示清晰的回声风险警示与虚拟驱动安装指引
  3. 在开启耳返/监听功能时增加弹窗风险提示（建议佩戴耳机使用，外放可能造成啸叫），支持勾选不再提示
  4. 在 `micyou-audio` 的 `engine.rs` 设备回落路径中增加 `log::warn!` 提示
  5. 补齐所有 7 种语言的国际化文案
- **涉及文件**:
  - `tauri-app/src-tauri/src/commands/audio.rs`
  - `tauri-app/crates/micyou-audio/src/engine.rs`
  - `tauri-app/src/features/audio/composables/useAudio.ts`
  - `tauri-app/src/shared/components/MonitoringWarningDialog.vue`
  - `tauri-app/src/features/settings/components/SettingsDialog.vue`
  - `tauri-app/src/App.vue`
  - `tauri-app/src/shared/locales/*.json`
- **状态**: 待验证 / 待关闭
- **建议关闭留言**:
  ```markdown
  Fixed in branch `fix/issue-fixes`
  - Connected the `set_monitoring` command to `AudioOutputHandle` so ear-return/monitoring stream properly starts/stops
  - Added warning prompt when enabling monitoring (recommending headphones to prevent acoustic feedback, with 'Do not show again' option)
  - Added acoustic feedback / echo risk warnings and virtual driver guidance in Settings when physical speakers are selected or fallen back to
  - Added backend warning logs on virtual device fallback
  ```

---

## Issue #287

- **标题**: [Bug]: 手机端会发两次相同的包给PC端，XOR手机端跟电脑端的序号对不上无法进行乱序恢复
- **链接**: https://github.com/LanRhyme/MicYou/issues/287
- **类型**: Bug
- **影响平台**: Android / Desktop (UDP / FEC / JitterBuffer)
- **原因分析**:
  1. 旧版本 Android 端在生成 FEC 校验包时占用了常规序列号槽位（如组 1 发送 0..11 + FEC 占用 12，组 2 变成 13..24），导致序列号跳跃并与 PC 端的取模分组对齐错位
  2. Proto3 标量默认值为 0，导致常规第 0 号包容易与缺省标记混淆
  3. 变长 Opus 编码包在 XOR 还原后缺少原始长度记录，导致还原的数据含有尾部零填充
- **修复方案**:
  - 该问题已在 PR #289（commit `44271e20`）中彻底修复：
    1. Android 端将 FEC 独立为带外数据，常规包序列号保持 0, 1, 2, 3 连续递增，FEC 包通过 `fecSequenceNumber = fecGroupStartSeq` 以及 `fecBuffer = byteArrayOf(1)` 明确标识组起始序号
    2. 新增 `fecPacketLengths` 元数据记录变长 Opus 包的真实字节长度，确保 XOR 还原时精确截断
    3. 桌面端 `JitterBuffer` 支持显式 FEC 分组边界与历史已播放包补偿（`played_fec_groups`），并覆盖完备的单测用例
- **涉及文件**:
  - `composeApp/src/main/kotlin/com/lanrhyme/micyou/audio/AudioEngine.kt`
  - `tauri-app/src-tauri/src/jitter_buffer.rs`
  - `tauri-app/crates/micyou-protocol/proto/network.proto`
- **状态**: 已在 PR #289 修复 / 可直接关闭
- **建议关闭留言**:
  ```markdown
  This issue has already been resolved in PR #289 (commit `44271e20`).
  - Regular audio packet sequence numbers are strictly contiguous
  - Out-of-band FEC packets explicitly carry group start sequences and variable-length metadata
  - JitterBuffer FEC recovery and out-of-order gap handling are fully covered by unit tests
  ```

---

## Issue #307

- **标题**: 建议：希望能找回悬浮窗 / Overlay 功能 (Feature Request: Floating Window)
- **链接**: https://github.com/LanRhyme/MicYou/issues/307
- **类型**: Feature / Enhancement
- **影响平台**: Windows / macOS / Linux
- **原因分析**: 旧版 KMP (`FloatingMicWindow.kt`) 中的桌面置顶麦克风悬浮球在迁移到 Tauri 2 初期暂未移植，用户在游戏或全屏应用中无法便捷查看麦克风状态或快速静音
- **修复方案**:
  1. 新增 `FloatingWindow.vue` 悬浮球组件，基于纯原生 SVG 渲染 1:1 复刻 `legacy/v1` 的声浪动画数学模型（三种状态：Muted 红色斜杠、Streaming 旋转声浪柱与电平弧环、Idle 静止微光环），规避 WebKitGTK 透明窗口 Canvas 混合异常
  2. 针对 Linux Wayland 实现 `gtk-layer-shell` 动态挂载，确保平铺窗口管理器下不被强制拉伸并置顶悬浮
  3. 支持单击静音/取消静音、拖拽移动与双击聚焦主面板
  4. Tauri 2 后端新增 `show_floating_window`、`hide_floating_window`、`toggle_floating_window`、`allow_firewall`（Windows TCP/UDP 双向防火墙一键放行）等指令
  5. 设置面板中增加“桌面悬浮窗”开关，UDP 拦截告警中增加一键放行按钮与全套 7 种语言国际化支持
- **涉及文件**:
  - `tauri-app/src/features/floating/components/FloatingWindow.vue`
  - `tauri-app/src/main.ts`
  - `tauri-app/src-tauri/src/commands/system.rs`
  - `tauri-app/src-tauri/src/commands/audio.rs`
  - `tauri-app/src-tauri/src/layer_shell.rs`
  - `tauri-app/src-tauri/src/stats.rs`
  - `tauri-app/src-tauri/src/tcp_server.rs`
  - `tauri-app/src-tauri/src/lib.rs`
  - `tauri-app/src/features/settings/components/SettingsDialog.vue`
  - `tauri-app/src/shared/components/UdpWarningDialog.vue`
  - `tauri-app/src/App.vue`
  - `tauri-app/src/shared/locales/*.json`
- **状态**: 待验证 / 待关闭
- **建议关闭留言**:
  ```markdown
  Fixed in branch `fix/issue-fixes`
  - Restored the legacy v1 circular floating mic window / overlay with crisp SVG visualizer, rotating waveform bars, and mute slash animation
  - Added click-to-mute, dragging, and double-click to focus main window
  - Added Windows Firewall TCP & UDP one-click rule addition
  - Added Desktop Floating Window toggle in Settings with multi-language support
  ```

---

## Issue #308

- **标题**: 将插件系统从Tauri GUI 的专属生命周期解耦，使CLI与TUI能使用插件系统
- **链接**: https://github.com/LanRhyme/MicYou/issues/308
- **类型**: Feat
- **影响平台**: All platforms (CLI / TUI / Desktop Core)
- **原因分析**: 插件生命周期过去绑定在 GUI 运行时中，CLI 与 TUI 作为独立前端无法复用已启用的插件，导致插件生态在非 GUI 场景下失效
- **修复方案**:
  1. 在 `PluginHost` 中新增 `load_saved_plugins()` 方法，自动扫描并加载启用状态的插件
  2. 在 `start_server_inner` 统一启动流中调用 `load_saved_plugins()`，使 GUI、CLI 和 TUI 均具备插件运行能力
  3. 在 `micyou-cli` 中新增 `plugin enable` 与 `plugin disable` 子命令，支持命令行管理
- **涉及文件**:
  - `tauri-app/src-tauri/src/plugins.rs`
  - `tauri-app/src-tauri/src/commands/system.rs`
  - `tauri-app/crates/micyou-cli/src/plugin_cmds.rs`
- **状态**: 待验证 / 待关闭

---

## Issue #309

- **标题**: 扩展插件能力边界：引入控制面信令的观察与拦截机制
- **链接**: https://github.com/LanRhyme/MicYou/issues/309
- **类型**: Feat
- **影响平台**: All platforms (Plugin Host / C ABI / WASM)
- **原因分析**: 插件缺乏对宿主控制面（静音、耳返监听、DSP 设置等）的系统性读写能力与一致的变更感知事件
- **修复方案**:
  1. 引入成对的控制面读写接口（`get_muted` / `set_muted`、`get_monitoring` / `set_monitoring`、`get_dsp_settings` / `set_dsp_settings`），严格区分 `control.observe` 与 `control.intercept` 权限
  2. 扩展 C ABI（`mpl_host_api_t`）与 WASM Linker，规范 Strict Append-Only 布局与 ABI 版本控制策略（`MPL_ABI_VERSION = 1`, `MPL_API_VERSION = 2`）
  3. 扩充 `MonitoringChanged` 事件，形成标准化的控制面事件总线
  4. 宿主通过 `ControlPlaneHandlers` 全面贯通音频引擎、网络信令与配置持久化
- **涉及文件**:
  - `tauri-app/crates/micyou-plugin/include/micyou_plugin_abi.h`
  - `tauri-app/crates/micyou-plugin/src/abi.rs`
  - `tauri-app/crates/micyou-plugin/src/host.rs`
  - `tauri-app/crates/micyou-plugin/src/wasm.rs`
  - `tauri-app/crates/micyou-plugin/src/plugin.rs`
  - `tauri-app/crates/micyou-plugin/src/native.rs`
  - `tauri-app/src-tauri/src/plugins.rs`
  - `tauri-app/src-tauri/src/commands/system.rs`
- **状态**: 待验证 / 待关闭

---

## Issue #310

- **标题**: 优化插件开发日志体验：支持文本选中、崩溃日志落盘与官方日志预制件
- **链接**: https://github.com/LanRhyme/MicYou/issues/310
- **类型**: Feat
- **影响平台**: Desktop GUI / Plugin DX
- **原因分析**: 插件运行日志排查困难，缺少对日志物理路径的直接查看、一键打开与文件导出能力
- **修复方案**:
  1. 后端新增 `get_log_path`、`get_log_content` 和 `open_log_dir` 命令
  2. 设置页面展示日志实际物理路径，支持一键打开目录、复制路径、复制内容与导出日志文件
  3. 补齐全套 7 种语言的多语言翻译
- **涉及文件**:
  - `tauri-app/src-tauri/src/commands/about.rs`
  - `tauri-app/src/features/settings/components/SettingsDialog.vue`
  - `tauri-app/src/shared/locales/*.json`
- **状态**: 待验证 / 待关闭
