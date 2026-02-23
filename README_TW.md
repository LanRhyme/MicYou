# MicYou

<p align="center">
  <img src="./img/app_icon.png" width="128" height="128" />
</p>

<p align="center">
  <a href="./README_ZH.md">简体中文</a> | <b>繁體中文</b> | <a href="./README.md">English</a>
</p>

MicYou 是一款強大的工具，可以將您的 Android 裝置變成 PC 的高品質無線麥克風，由 Kotlin Multiplatform 與 Jetpack Compose/Material 3 構建

本專案基於 [AndroidMic](https://github.com/teamclouday/AndroidMic) 開發

## 主要功能

- **多種連線模式**：支援 Wi-Fi、USB (ADB/AOA) 與藍牙連線
- **音訊處理**：內建噪聲抑制、自動增益控制 (AGC) 與去混響功能
- **跨平台支援**：
  - **Android 客戶端**：現代 Material 3 介面，支援深色/淺色主題
  - **桌面端服務端**：支援 Windows/Linux 接收音訊
- **虛擬麥克風**：搭配 VB-Cable 可作為系統麥克風輸入使用
- **高度可自訂**：支援調整取樣率、聲道數與音訊格式

## 軟體截圖

### Android 客戶端
|                            主畫面                             |                           設定                               |
|:-----------------------------------------------------------:|:-------------------------------------------------------------:|
| <img src="img/android_screenshot_main.jpg" width="300" /> | <img src="img/android_screenshot_settings.jpg" width="300" /> |

### 桌面端
<img src="img/pc_screenshot.png" width="600" />

## 使用指南

### Android
1. 下載並安裝 APK 到您的 Android 裝置
2. 確保您的裝置與 PC 位於同一網路（Wi-Fi 模式），或透過 USB 連線

### Windows
1. 執行桌面端應用程式
2. 設定連線模式以匹配 Android 應用

### Linux

#### 使用預編譯套件（推薦）
預編譯套件可在 [GitHub Releases](https://github.com/LanRhyme/MicYou/releases) 下載

**DEB 套件（適用於 Debian/Ubuntu/Mint 等發行版）：**
```bash
# 從 GitHub Releases 下載 .deb 套件
sudo dpkg -i MicYou-*.deb
# 如果缺少依賴：
sudo apt install -f
```

**RPM 套件（適用於 Fedora/RHEL/openSUSE 等發行版）：**
```bash
# 從 GitHub Releases 下載 .rpm 套件
sudo rpm -i MicYou-*.rpm
# 或者使用 dnf/yum：
sudo dnf install MicYou-*.rpm
```

**AUR 倉庫（適用於 Arch Linux 及其衍生發行版）：**
```bash
# 克隆 AUR 倉庫並自動安裝軟體包及其依賴
git clone https://aur.archlinux.org/micyou-bin.git
cd micyou-bin
makepkg -si

# 或者使用 paru 等 AUR helpers
paru -S micyou-bin
```

**執行應用：**
```bash
# 安裝後可以從應用程式選單執行 MicYou
# 或者從終端執行：
MicYou
```

> [!TIP]
> 遇到問題？請查看：[常見問題](./docs/FAQ_TW.md)

## 原始碼建置

本專案使用 Kotlin Multiplatform 建置

**Android 應用（APK）：**
```bash
./gradlew :composeApp:assembleDebug
```

**桌面應用（直接執行）：**
```bash
./gradlew :composeApp:run
```

**建置發佈套件：**

**Windows 安裝程式（NSIS）：**
```bash
./gradlew :composeApp:packageWindowsNsis
```

**Windows ZIP 封存：**
```bash
./gradlew :composeApp:packageWindowsZip
```

**Linux DEB 套件：**
```bash
./gradlew :composeApp:packageDeb
```

**Linux RPM 套件：**
```bash
./gradlew :composeApp:packageRpm
```

## 國際化（i18n）

MicYou 支援多種語言，擁有完善的翻譯系統。我們歡迎您為 MicYou 貢獻翻譯！

### 透過 Crowdin 貢獻翻譯（推薦）

最便捷的翻譯方式是透過 [Crowdin](https://crowdin.com/project/micyou)。無需本地開發環境設置：

1. 訪問 [MicYou on Crowdin](https://crowdin.com/project/micyou)
2. 使用 GitHub 帳戶登入或註冊
3. 從語言列表中選擇您的語言
4. 在網頁介面中直接翻譯字符串
5. 提交翻譯以供審核

當翻譯被合併時，將透過 GitHub Actions 自動同步到儲存庫。

### 手動新增語言

要手動新增語言：

1. 複製儲存庫：
```bash
git clone https://github.com/LanRhyme/MicYou.git
cd MicYou
```

2. 將英文翻譯檔案複製為範本：
```bash
cp composeApp/src/commonMain/composeResources/files/i18n/strings_en.json \
   composeApp/src/commonMain/composeResources/files/i18n/strings_xx.json
```
將 `xx` 取代為您的語言代碼（例如 `fr` 表示法文，`es` 表示西班牙文）。

3. 編輯新建的 JSON 檔案，翻譯所有字符串值（保持鍵不變）：
```json
{
  "appName": "MicYou",
  "ipLabel": "IP: ",
  ...
}
```

4. 在 [Localization.kt](composeApp/src/commonMain/kotlin/com/lanrhyme/micyou/Localization.kt) 中註冊新語言：

找到 `AppLanguage` 列舉並新增您的語言：
```kotlin
enum class AppLanguage(val label: String, val code: String) {
    // ... 現有語言 ...
    French("Français", "fr"),  // 新增此行
}
```

同時在 `getStrings()` 函數中處理您的語言：
```kotlin
fun getStrings(language: AppLanguage): AppStrings {
    val langCode = when (language) {
        // ... 現有語言 ...
        AppLanguage.French -> "fr"
        // ...
    }
    // ...
}
```

### 測試翻譯

本地測試翻譯：

1. 建置並執行桌面應用：
```bash
./gradlew :composeApp:run
```

2. 進入 **設定 → 外觀 → 語言** 並選擇您新建的語言

3. 驗證所有字符串已正確翻譯，版面顯示正常

4. 對於 Android 應用，建置 APK：
```bash
./gradlew :composeApp:assembleDebug
```

### 翻譯工作流程

- **源語言**：英文（`strings_en.json`）
- **位置**：`composeApp/src/commonMain/composeResources/files/i18n/`
- **檔案格式**：JSON
- **目前支援**：30+ 種語言，包括簡體中文、繁體中文、粵語

### 特殊語言變體

某些語言有特殊變體：
- `strings_zh.json` - 簡體中文
- `strings_zh_tw.json` - 繁體中文（台灣）
- `strings_zh_hk.json` - 粵語（香港）
- `strings_zh_hard.json` - 中文（生硬 - 彩蛋）
- `strings_cat.json` - 貓貓語言（彩蛋）

### 貢獻翻譯

1. **透過 Crowdin**（推薦）：加入我們的 Crowdin 專案進行協作翻譯
2. **透過 GitHub**：提交包含新增/更新翻譯檔案的 Pull Request
3. 在 PR 標題中包含英文和本地語言的語言名稱 例如：添加 xx（語言代碼）在地化

## Star History

[![Star History Chart](https://api.star-history.com/svg?repos=lanrhyme/MicYou&type=Date)](https://star-history.com/#lanrhyme/MicYou&Date)
