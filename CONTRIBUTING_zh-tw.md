# 為 MicYou 做出貢獻

感謝您有興趣為 MicYou 做出貢獻！本指南涵蓋專案結構、如何從原始程式碼構建應用、如何新增翻譯以及如何提交變更。

我們歡迎所有類型的貢獻，包括錯誤回報、功能請求、程式碼貢獻以及翻譯。

## 專案結構

- `composeApp/` — Android 應用（Kotlin、Jetpack Compose、Material 3）
- `tauri-app/` — 桌面應用
  - `src/` — Vue 3 + Vite + Tailwind CSS 前端
  - `src-tauri/` — Tauri 2 + Rust 後端（GUI 伺服器）
  - `crates/` — 共享 Rust 工作區 crate：
    - `micyou-protocol` — 網路協定
    - `micyou-audio` — 音訊傳輸、緩衝與 DSP
    - `micyou-cli` — 無介面 CLI 伺服器（二進位檔 `micyou`）
    - `micyou-tui` — 互動式終端機儀表板（二進位檔 `micyou-tui`）
- `docs/` — 專案文件（指向 micyou.top 線上文件）
- `img/` — README 與專案圖片

## 環境需求

- Android SDK：compileSdk 36、minSdk 24、targetSdk 36（JDK 21）
- 桌面前端：Node.js 22（CI 中使用的版本）+ npm
- 桌面後端：Rust stable + Cargo。在 Linux 上還需安裝 Tauri 2 系統依賴：`libwebkit2gtk-4.1-dev`、`libayatana-appindicator3-dev`、`librsvg2-dev`、`patchelf`、`libxdo-dev`、`libssl-dev`、`libasound2-dev`。

## 從原始程式碼構建

### Android 應用（APK）

```bash
./gradlew :composeApp:assembleDebug
```

選用（僅維護者需要）：發布簽名透過 `ANDROID_KEYSTORE_PATH`、`ANDROID_KEYSTORE_PASSWORD`、`ANDROID_KEY_ALIAS`、`ANDROID_KEY_PASSWORD` 環境變數配置；`local.properties` 中的 `AIFADIAN_API_TOKEN`、`AIFADIAN_USER_ID` 用於 App 內的贊助者清單（愛發電 API）。一般貢獻者可忽略兩者——未配置時贊助者彈窗只會顯示 "API not configured"。

### 桌面前端（型別檢查 + 構建）

```bash
cd tauri-app
npm install          # 僅在需要還原或更新依賴時執行
npm run build        # vue-tsc 型別檢查 + vite 構建
```

### 桌面 GUI（Tauri）

```bash
cd tauri-app
npm run tauri dev    # 開發模式
npm run tauri build  # 發布套件（Windows 為 NSIS 安裝程式，Linux 為 .deb/.rpm/.AppImage，macOS 為 .dmg）
```

### CLI 與 TUI 伺服器

桌面伺服器也可以不啟動 GUI 執行：

```bash
cd tauri-app
cargo run -p micyou-cli -- serve    # CLI（無介面；執行 `cargo run -p micyou-cli -- --help` 查看全部指令）
cargo run -p micyou-tui             # 互動式 TUI 儀表板
```

GUI、CLI 與 TUI 共用同一份伺服器設定與 DSP 設定。

### 版本管理

版本號的唯一來源是 `gradle.properties`（`project.version`、`project.version.code`）。修改後請同步桌面端版本檔案：

```bash
cd tauri-app
npm run sync-version
```

## 國際化（i18n）

使用者可見的字串存放在 Android 字串資源與桌面端 locale JSON 檔案中。兩個平台的語言集合相互獨立，各自平台內的鍵集合需保持一致。

### Android

- 位置：`composeApp/src/main/res/values*/strings.xml`
- 來源語言（必須保持同步）：英文（`values/`）與簡體中文（`values-zh/`）
- 語言註冊：`composeApp/src/main/kotlin/com/lanrhyme/micyou/util/Localization.kt` 中的 `AppLanguage` 列舉

新增語言：

1. 建立 `composeApp/src/main/res/values-xx/strings.xml`（將 `xx` 替換為語言代碼，例如法語為 `fr`，基於 ISO 639-1 或 IETF BCP 47）。
2. 複製 `values/strings.xml` 中的鍵並翻譯所有值，保持鍵不變：
```xml
<resources>
    <string name="appName">MicYou</string>
    <string name="ipLabel">IP : </string>
    <!-- ... -->
</resources>
```
3. 在 `AppLanguage` 中註冊新語言：
```kotlin
enum class AppLanguage(val label: String, val code: String) {
    // ... 現有語言 ...
    French("Français", "fr"),  // 新增這一行
}
```

特殊變體（彩蛋）：
- `values-zh/` — 簡體中文
- `values-zh-rTW/` — 繁體中文（台灣）
- `values-zh-rHK/` — 粵語（香港）
- `values-zh-rHD/` — 中文硬核模式（彩蛋）
- `values-ca/` — 貓貓語（彩蛋）

### 桌面端（Tauri）

- 位置：`tauri-app/src/shared/locales/*.json`（`en`、`zh`、`zh-hk`、`zh-tw`、`zh-ss`、`cat`、`lzh`）
- 來源語言：英文（`en.json`）與簡體中文（`zh.json`）
- 註冊方式：在 `tauri-app/src/main.ts` 中匯入 JSON 並加入 i18n `messages` 對映

新增語言：

1. 建立 `tauri-app/src/shared/locales/xx.json`，鍵結構與 `zh.json` 一致。
2. 在 `tauri-app/src/main.ts` 中匯入並註冊到 `messages` 對映。

### 測試翻譯

- Android：構建 APK（`./gradlew :composeApp:assembleDebug`），然後在 **設定 → 語言** 中檢查。
- 桌面端：執行 `npm run tauri dev`，然後在 **設定 → 語言** 中檢查。
- 確認所有字串顯示正確，版面沒有裁切或溢位。

## 提交變更

所有提交訊息與 PR 標題使用 [Conventional Commits](https://www.conventionalcommits.org/) 格式：

- `feat(i18n): add fr (French) localization`
- `fix: resolve audio crash on Android 14`
- `docs: update build instructions`

在發起拉取請求前：

- 確保您修改的平台的所有語言檔案鍵集合一致。
- 確保 Android 偵錯構建（`./gradlew :composeApp:assembleDebug`）與桌面端構建（`cd tauri-app && npm run build`）通過。
- CI（`.github/workflows/development.yml`）會在每次推送與拉取請求時構建 Android 偵錯 APK 以及 Windows、macOS、Linux 的 Tauri 安裝套件。

參與貢獻即表示您同意遵守專案的[行為準則](./CODE_OF_CONDUCT.md)。
