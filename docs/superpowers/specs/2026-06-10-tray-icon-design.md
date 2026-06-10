# MicYou Tauri - System Tray Icon Design

**Date:** 2026-06-10
**Branch:** RFC/Tauri
**Status:** Approved (approach selected: A)

## Context

MicYou 正在从 Kotlin Multiplatform 桌面端迁移到 Tauri 2 + Vue 3。原 KMP 实现 (`composeApp/src/jvmMain/.../main.kt:110-131`) 有一个系统托盘，包含三个菜单项 (Show/Hide、Start/Stop、Exit)，并配有确认弹窗处理关闭按钮 (`CloseConfirmDialog.kt`)。Tauri 端目前完全没有托盘，也没有自定义关闭处理。

本 spec 实现托盘及其相关 UI 同步，目标：让桌面端的"最小化到托盘"体验与原 KMP 版一致。

## Approach

**架构 (Approach A)**：Rust 拥有托盘，Vue 拥有应用状态，通过事件/命令双向同步。

- 启动时 Rust `setup()` 构建托盘，菜单点击 emit `tray-action` 给 Vue
- Vue 状态变化时 invoke `set_tray_state` 更新菜单文字
- Vue 启动 / 切语言时 invoke `set_tray_strings` 推送 i18n 文案
- Vue 启动时 invoke `set_tray_state({ window_visible: true, is_streaming: false })` 初始化

**为何不选 Approach B** (Rust 镜像流状态)：会把 `serverState` 从 Vue 搬到 Rust，属于更大的重构，超出"实现托盘"范围。

## Architecture

### Rust 侧

**新文件** `tauri-app/src-tauri/src/tray.rs`：
- 公开 `pub fn build_tray(app: &AppHandle) -> tauri::Result<()>` —— 在 `lib.rs` 的 `setup()` 里调用
- 内部用 `tauri::tray::TrayIconBuilder` 建托盘 + `tauri::menu::{Menu, MenuItem, PredefinedMenuItem}` 建菜单
- 用 `app.state::<Mutex<TrayMenuStrings>>()` 存储 i18n 文案，`app.state::<Mutex<TrayState>>()` 存储状态
- 公开 `pub fn rebuild_menu(app: &AppHandle)` —— 在 strings/state 更新时重建菜单
- `on_menu_event` 处理器：从 `MenuId` 读出字符串 id，emit `"tray-action"` 事件

**`lib.rs` 修改**：
- `mod tray;`
- `setup` 中调用 `tray::build_tray(&app_handle)`，错误仅 log 不致命
- 四个新 commands：
  - `set_tray_strings(strings: TrayMenuStrings)`
  - `set_tray_state(state: TrayState)`
  - `show_main_window()` → `get_webview_window("main")?.show() + set_focus() + unminimize()`
  - `hide_main_window()` → `get_webview_window("main")?.hide()`
  - `exit_app()` → `stop_server` (内部调用) + `app.exit(0)`。`restore_default_audio` 留 stub（no-op + log）
- 全部注册到 `invoke_handler`

**`Cargo.toml` 修改**：
- `tauri = { version = "2", features = ["tray-icon", "image-png"] }`

### Vue 侧

**新文件** `tauri-app/src/composables/useTray.ts`：
- 导出 `useTray()` composable
- 内部：
  - 启动时从 `vue-i18n` 取 5 个 key 翻译 + `appName` 推 strings
  - 启动时推初始 state (window_visible=true, is_streaming=false)
  - 监听 `i18n.locale` 变化 → 重推 strings
  - `listen("tray-action", handler)` 触发回调
  - 导出 `setTrayState(state)` 和 `pushTrayStrings()` 供外部调用
  - `onUnmounted` 清理 listener

**新文件** `tauri-app/src/components/CloseConfirmDialog.vue`：
- Props: `modelValue: boolean`
- Emits: `update:modelValue`, `select(action: "hide" | "exit", remember: boolean)`
- UI：标题、说明文字、「隐藏到托盘」按钮、「退出」按钮、底部「记住我的选择」复选框、ESC / 点击外部关闭（只关弹窗不动作）
- 样式：与现有 `UdpWarningDialog.vue` 风格保持一致（半透明遮罩 + 圆角卡 + Material 颜色 token）

**`App.vue` 修改**：
- 新增响应式状态：`showCloseConfirm: boolean` (默认 false)，`isHidden: boolean` (默认 false)
- 新增两个本地 helper：
  - `showMainWindow()` → `await invoke("show_main_window")` + `isHidden = false` + `useTray.setTrayState({ windowVisible: true, isStreaming: serverState !== 'idle' })`
  - `hideMainWindow()` → `await invoke("hide_main_window")` + `isHidden = true` + `useTray.setTrayState({ windowVisible: false, isStreaming: serverState !== 'idle' })`
  - `exitApp()` → `await invoke("exit_app")`
- `closeWindow` 改造为 `requestClose()`：
  1. 读 `localStorage.getItem("micyou_remember_close_action")`
  2. 若值为 `"hide"` → `hideMainWindow()`；若 `"exit"` → `exitApp()`
  3. 否则 `showCloseConfirm = true`
- 监听 `serverState` 变化 → `useTray.setTrayState({ windowVisible: !isHidden, isStreaming: serverState !== 'idle' })`
- 调用 `useTray({ onShow, onToggleStream, onExit })`：
  - `onShow` → 若 `isHidden` 则 `showMainWindow()`，否则仅 `invoke("set_focus")` 把已可见窗口拉到前台
  - `onToggleStream` → 调用现有的 `toggleStreaming()`
  - `onExit` → `exitApp()`
- 模板：把 `appWindow.close()` 替换为 `requestClose()`；在合适位置（与 `UdpWarningDialog` 并列）挂 `<CloseConfirmDialog v-model="showCloseConfirm" @select="handleCloseSelect" />`
- `handleCloseSelect({ action, remember })`：`if (remember) localStorage.setItem("micyou_remember_close_action", action)` else `localStorage.removeItem(...)`；然后 `action === "hide" ? hideMainWindow() : exitApp()`；最后 `showCloseConfirm = false`

**`useTray.ts` 暴露给 App.vue 的回调签名：**
```ts
useTray({
  onShow: () => Promise<void>,
  onToggleStream: () => Promise<void>,
  onExit: () => Promise<void>,
})
```

### i18n

**`en.json` 新增：**
```json
"tray": {
  "tooltip": "MicYou Desktop",
  "show": "Show App",
  "hide": "Hide App",
  "start": "Start Streaming",
  "stop": "Stop Streaming",
  "exit": "Exit"
},
"closeConfirm": {
  "title": "Close MicYou",
  "message": "What would you like to do?",
  "hide": "Hide to Tray",
  "exit": "Exit",
  "remember": "Remember my choice"
}
```

**`zh.json` 新增（中文）：**
```json
"tray": {
  "tooltip": "MicYou 桌面端",
  "show": "显示应用",
  "hide": "隐藏应用",
  "start": "开始推流",
  "stop": "停止推流",
  "exit": "退出"
},
"closeConfirm": {
  "title": "关闭 MicYou",
  "message": "你想要做什么？",
  "hide": "隐藏到托盘",
  "exit": "退出",
  "remember": "记住我的选择"
}
```

### Tauri Capabilities

`tauri-app/src-tauri/capabilities/default.json` 新增：
- `core:window:allow-show`
- `core:window:allow-hide`
- `core:window:allow-set-focus`
- `core:window:allow-unminimize`
- `core:event:allow-listen` (如不在 default)
- `core:event:allow-emit` (如不在 default)

## Data Contracts

**Rust:**
```rust
#[derive(serde::Deserialize, Debug, Clone)]
pub struct TrayMenuStrings {
    pub tooltip: String,
    pub show: String,
    pub hide: String,
    pub start: String,
    pub stop: String,
    pub exit: String,
}

#[derive(serde::Deserialize, Debug, Clone)]
pub struct TrayState {
    pub window_visible: bool,
    pub is_streaming: bool,
}
```

**TypeScript:**
```ts
export interface TrayMenuStrings {
  tooltip: string; show: string; hide: string;
  start: string; stop: string; exit: string;
}
export interface TrayState {
  windowVisible: boolean;
  isStreaming: boolean;
}
```

## Menu Item IDs

约定三个 id 字符串：
- `"show"` —— 永远用于"切换主窗口可见性"（菜单文案动态变化但 id 不变）
- `"toggle_stream"` —— 触发 toggleStreaming
- `"exit"` —— 触发 exitApp

点击语义与菜单文案解耦：菜单显示 "Show App" / "Hide App" 时点击 id 都是 `"show"`，Rust 收到后由 Vue 端决定具体动作（聚焦 vs 显示）。

## Error Handling

- **托盘构建失败** (headless Linux 等)：`tracing::warn!` 记日志、继续运行主窗口；不致命
- **Vue 推 strings 失败 / 命令报错**：`console.error` 后继续，不阻塞 UI
- **菜单点击事件 id 不匹配**：`tracing::warn!` 后忽略
- **退出流程**：`stop_server` → `restore_default_audio`（stub）→ `app.exit(0)`；任一步失败 log 后继续 exit
- **macOS 行为**：默认无 left-click 行为，只能右键（这是平台差异，不在本 spec 处理）

## Acceptance Criteria

1. 启动后系统托盘出现 MicYou 图标，悬停 tooltip = "MicYou Desktop"
2. 右键菜单依次：Hide App（窗口可见时）/ Show App（隐藏时）、Start Streaming（idle 时）/ Stop Streaming（推流时）、Exit
3. 点击 Hide App：主窗口隐藏；菜单项变 Show App
4. 点击 Show App：主窗口显示 + 聚焦；菜单项变 Hide App
5. 点击 Start Streaming：主窗口中推流启动，菜单项变 Stop Streaming
6. 点击 Stop Streaming：停止推流，菜单项变回 Start Streaming
7. 切语言 (en ↔ zh)：托盘菜单文字跟随变化
8. 主窗口点击 X：弹窗出现，含「隐藏到托盘」「退出」「记住我的选择」
9. 勾选记住 + 选 Hide 后再点 X：直接隐藏，不弹窗；选 Exit 后再点 X：直接退出
10. 托盘菜单点 Exit：清理 → 退出（即使主窗口处于隐藏状态）

## Out of Scope

- 推流/连接时托盘图标变色 / 加红点
- 托盘 left-click 切换窗口可见性（仅 Windows/Linux；macOS 平台限制）
- `VirtualAudioDeviceManager` 迁移与退出时还原系统默认麦克风（`restore_default_audio` 留 stub，后续单独 spec）
- 托盘点击显示 quick stats
- Pocket mode 的 floating window（当前 Tauri 还没有该功能）
