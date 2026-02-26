# MicYou

<p align="center">
  <img src="./img/app_icon.png" width="128" height="128" />
</p>

<p align="center">
  <a href="./README_ZH.md">简体中文</a> | <b>繁體中文</b> | <a href="./README.md">English</a>
</p>

<p align="center">
  <a href="https://github.com/LanRhyme/MicYou/blob/master/LICENSE">
    <img alt="LICENSE" src="https://img.shields.io/badge/license-MIT-green"></a>
  <a href="https://github.com/LanRhyme/MicYou/commits/master">
    <img alt="GitHub commit activity" src="https://img.shields.io/github/commit-activity/t/LanRhyme/MicYou?logo=github"></a>
  <a href="https://github.com/LanRhyme/MicYou/releases/latest">
    <img alt="GitHub Release" src="https://img.shields.io/github/v/release/LanRhyme/MicYou?logo=github"></a>
  <a href="https://aur.archlinux.org/packages/micyou-bin">
    <img alt="AUR Version" src="https://img.shields.io/aur/version/micyou-bin?logo=archlinux&label=micyou-bin"></a>
  <a href="https://crowdin.com/project/micyou" target="_blank" rel="noopener noreferrer">
    <img alt="Crowdin" src="https://badges.crowdin.net/micyou/localized.svg"></a>
</p>

<p align="center">
<img alt="Kotlin" src="https://img.shields.io/badge/kotlin-%237F52FF.svg?style=for-the-badge&logo=kotlin&logoColor=white" />
<img alt="Android" src="https://img.shields.io/badge/Android-3DDC84?style=for-the-badge&logo=android&logoColor=white" />
<img alt="Windows" src="https://img.shields.io/badge/Windows-0078D6?style=for-the-badge&logo=windows&logoColor=white" />
<img alt="Linux" src="https://img.shields.io/badge/Linux-FCC624?style=for-the-badge&logo=linux&logoColor=black" />
<img alt="macOS" src="https://img.shields.io/badge/mac%20os-000000?style=for-the-badge&logo=macos&logoColor=F0F0F0" />
</p>

MicYou 是一款強大的工具，可以將您的 Android 裝置變成 PC 的高品質無線麥克風，由 Kotlin Multiplatform 與 Jetpack Compose/Material 3 構建

本專案基於 [AndroidMic](https://github.com/teamclouday/AndroidMic) 開發

## 主要功能

- **多種連線模式**：支援 Wi-Fi、USB (ADB/AOA) 與藍牙連線
- **音訊處理**：內建噪聲抑制、自動增益控制 (AGC) 與去混響功能
- **跨平台支援**：
  - **Android 客戶端**：現代 Material 3 介面，支援深色/淺色主題
  - **桌面端服務端**：支援 Windows/Linux/macOS 接收音訊
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

### 1. 下載 ADB
- 從 [Android Developers](https://developer.android.com/tools/releases/platform-tools?hl=zh_cn) 下載
- 使用套件管理工具下載
  - `winget install -e --id Google.PlatformTools`
  - `sudo apt install android-tools-adb`
  - `sudo pacman -S android-tools`
  - ...
  
大部分情況下會自動將 ADB 加入環境變數。如果沒有，請自行設定

### 2. 啟用 USB 偵錯
以 OneUI 8 為例

1. 進入設定，點擊`關於手機`
2. 點擊`軟體資訊`，找到`編譯編號`，點擊 **7** 下，當見到 `不需要，開發者模式已啟用`，即開啟成功
3. 返回設定，點擊`開發者選項`，找到`USB 偵錯`，開啟即可

### 3. 使用 USB 連線
請使用一條**穩定**的傳輸線，並**同時**在桌面端與 Android 應用將連線模式切換為 `USB`。

### 4. 使用 Wi-Fi 連線
請確保您的 Android 裝置與 PC 位於**同一網路環境**，並**同時**在桌面端與 Android 應用將連線模式切換為 `Wi-Fi`。

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

## Contributors
<a href="https://github.com/LanRhyme/MicYou/graphs/contributors">
  <img src="https://contrib.rocks/image?repo=LanRhyme/MicYou" />
</a>


Made with [contrib.rocks](https://contrib.rocks).

## Star History

[![Star History Chart](https://api.star-history.com/svg?repos=lanrhyme/MicYou&type=Date)](https://star-history.com/#lanrhyme/MicYou&Date)
