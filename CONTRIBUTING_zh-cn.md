# 为 MicYou 做出贡献

感谢您有兴趣为 MicYou 做出贡献！本指南涵盖项目结构、如何从源代码构建应用、如何添加翻译以及如何提交更改。

我们欢迎所有类型的贡献，包括错误报告、功能请求、代码贡献以及翻译。

## 项目结构

- `composeApp/` — Android 应用（Kotlin、Jetpack Compose、Material 3）
- `tauri-app/` — 桌面应用
  - `src/` — Vue 3 + Vite + Tailwind CSS 前端
  - `src-tauri/` — Tauri 2 + Rust 后端（GUI 服务器）
  - `crates/` — 共享 Rust 工作区 crate：
    - `micyou-protocol` — 网络协议
    - `micyou-audio` — 音频传输、缓冲与 DSP
    - `micyou-cli` — 无界面 CLI 服务器（二进制 `micyou`）
    - `micyou-tui` — 交互式终端仪表盘（二进制 `micyou-tui`）
- `docs/` — 项目文档（指向 micyou.top 在线文档）
- `img/` — README 与项目图片

## 环境要求

- Android SDK：compileSdk 36、minSdk 24、targetSdk 36（JDK 21）
- 桌面前端：Node.js 22（CI 中使用的版本）+ npm
- 桌面后端：Rust stable + Cargo。在 Linux 上还需安装 Tauri 2 系统依赖：`libwebkit2gtk-4.1-dev`、`libayatana-appindicator3-dev`、`librsvg2-dev`、`patchelf`、`libxdo-dev`、`libssl-dev`、`libasound2-dev`。

## 从源代码构建

### Android 应用（APK）

```bash
./gradlew :composeApp:assembleDebug
```

可选（仅维护者需要）：发布签名通过 `ANDROID_KEYSTORE_PATH`、`ANDROID_KEYSTORE_PASSWORD`、`ANDROID_KEY_ALIAS`、`ANDROID_KEY_PASSWORD` 环境变量配置；`local.properties` 中的 `AIFADIAN_API_TOKEN`、`AIFADIAN_USER_ID` 用于 App 内的赞助者列表（爱发电 API）。普通贡献者可忽略两者——未配置时赞助者弹窗只会显示 "API not configured"。

### 桌面前端（类型检查 + 构建）

```bash
cd tauri-app
npm install          # 仅在需要恢复或更新依赖时执行
npm run build        # vue-tsc 类型检查 + vite 构建
```

### 桌面 GUI（Tauri）

```bash
cd tauri-app
npm run tauri dev    # 开发模式
npm run tauri build  # 发布包（Windows 为 NSIS 安装程序，Linux 为 .deb/.rpm/.AppImage，macOS 为 .dmg）
```

### CLI 与 TUI 服务器

桌面服务器也可以不启动 GUI 运行：

```bash
cd tauri-app
cargo run -p micyou-cli -- serve    # CLI（无界面；运行 `cargo run -p micyou-cli -- --help` 查看全部命令）
cargo run -p micyou-tui             # 交互式 TUI 仪表盘
```

GUI、CLI 与 TUI 共享同一份服务器配置与 DSP 设置。

### 版本管理

版本号的唯一来源是 `gradle.properties`（`project.version`、`project.version.code`）。修改后请同步桌面端版本文件：

```bash
cd tauri-app
npm run sync-version
```

## 国际化（i18n）

用户可见的字符串存放在 Android 字符串资源与桌面端 locale JSON 文件中。两个平台的语言集合相互独立，各自平台内的键集合需保持一致。

### Android

- 位置：`composeApp/src/main/res/values*/strings.xml`
- 母语言（必须保持同步）：英文（`values/`）与简体中文（`values-zh/`）
- 语言注册：`composeApp/src/main/kotlin/com/lanrhyme/micyou/util/Localization.kt` 中的 `AppLanguage` 枚举

添加新语言：

1. 创建 `composeApp/src/main/res/values-xx/strings.xml`（将 `xx` 替换为语言代码，例如法语为 `fr`，基于 ISO 639-1 或 IETF BCP 47）。
2. 复制 `values/strings.xml` 中的键并翻译所有值，保持键不变：
```xml
<resources>
    <string name="appName">MicYou</string>
    <string name="ipLabel">IP : </string>
    <!-- ... -->
</resources>
```
3. 在 `AppLanguage` 中注册新语言：
```kotlin
enum class AppLanguage(val label: String, val code: String) {
    // ... 现有语言 ...
    French("Français", "fr"),  // 添加这一行
}
```

特殊变体（彩蛋）：
- `values-zh/` — 简体中文
- `values-zh-rTW/` — 繁体中文（台湾）
- `values-zh-rHK/` — 粤语（香港）
- `values-zh-rHD/` — 中文硬核模式（彩蛋）
- `values-ca/` — 猫猫语（彩蛋）

### 桌面端（Tauri）

- 位置：`tauri-app/src/shared/locales/*.json`（`en`、`zh`、`zh-hk`、`zh-tw`、`zh-ss`、`cat`、`lzh`）
- 母语言：英文（`en.json`）与简体中文（`zh.json`）
- 注册方式：在 `tauri-app/src/main.ts` 中导入 JSON 并加入 i18n `messages` 映射

添加新语言：

1. 创建 `tauri-app/src/shared/locales/xx.json`，键结构与 `zh.json` 一致。
2. 在 `tauri-app/src/main.ts` 中导入并注册到 `messages` 映射。

### 测试翻译

- Android：构建 APK（`./gradlew :composeApp:assembleDebug`），然后在 **设置 → 语言** 中检查。
- 桌面端：运行 `npm run tauri dev`，然后在 **设置 → 语言** 中检查。
- 确认所有字符串显示正确，布局没有裁剪或溢出。

## 提交更改

所有提交信息与 PR 标题使用 [Conventional Commits](https://www.conventionalcommits.org/) 格式：

- `feat(i18n): add fr (French) localization`
- `fix: resolve audio crash on Android 14`
- `docs: update build instructions`

在发起拉取请求前：

- 确保你修改的平台的所有语言文件键集合一致。
- 确保 Android 调试构建（`./gradlew :composeApp:assembleDebug`）与桌面端构建（`cd tauri-app && npm run build`）通过。
- CI（`.github/workflows/development.yml`）会在每次推送与拉取请求时构建 Android 调试 APK 以及 Windows、macOS、Linux 的 Tauri 安装包。

参与贡献即表示您同意遵守项目的[行为准则](./CODE_OF_CONDUCT.md)。
