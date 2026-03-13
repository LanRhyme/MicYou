# MicYou

<p align="center">
  <img src="./img/app_icon.png" width="128" height="128" />
</p>

<p align="center">
  <a href="./README_zh-cn.md">简体中文</a> | <b>繁體中文</b> | <a href="./README.md">English</a>
</p>

<p align="center">
  <a href="https://github.com/LanRhyme/MicYou/blob/master/LICENSE">
    <img alt="LICENSE" src="https://img.shields.io/badge/license-GPLv3-green"></a>
  <a href="https://github.com/LanRhyme/MicYou/stargazers">
    <img alt="Github Stars" src="https://img.shields.io/github/stars/LanRhyme/MicYou?style=flat&logo=github&logoColor=white"><a>
  <a href="https://github.com/LanRhyme/MicYou/releases/latest">
    <img alt="GitHub Release" src="https://img.shields.io/github/v/release/LanRhyme/MicYou?logo=github"></a>
  <a href="https://qm.qq.com/q/V16hPpWPKO">
    <img alt="QQ" src="https://img.shields.io/badge/QQ%E7%BE%A4-995452107-12B7F5?style=flat&logo=qq&logoColor=white"><a>
  <a href="https://aur.archlinux.org/packages/micyou-bin">
    <img alt="AUR Version" src="https://img.shields.io/aur/version/micyou-bin?logo=archlinux&label=micyou-bin"></a>
  <a href="https://crowdin.com/project/micyou" target="_blank" rel="noopener noreferrer">
    <img alt="Crowdin" src="https://badges.crowdin.net/micyou/localized.svg"></a>
</p>

<h6 align="center">技術棧</h6>

<p align="center">
  <img alt="Kotlin" src="https://img.shields.io/badge/kotlin-%237F52FF.svg?style=for-the-badge&logo=kotlin&logoColor=white" />
  <img alt="Jetpack Compose" src="https://img.shields.io/badge/Jetpack_Compose-4285F4?style=for-the-badge&logo=jetpackcompose&logoColor=white">
</p>

<h6 align="center">受支援的平臺</h6>

<p align="center">
  <img alt="Android" src="https://img.shields.io/badge/Android-3DDC84?style=for-the-badge&logo=android&logoColor=white" />
  <img alt="Windows" src="https://custom-icon-badges.demolab.com/badge/Windows-0078D6?&style=for-the-badge&logo=windows11&logoColor=white" />
  <img alt="Linux" src="https://img.shields.io/badge/Linux-FCC624?style=for-the-badge&logo=linux&logoColor=black" />
  <img alt="macOS" src="https://img.shields.io/badge/mac%20os-000000?style=for-the-badge&logo=apple&logoColor=F0F0F0" />
</p>

<h6 align="center">贊助我</h6>

<p align="center">
<a href="https://afdian.com/a/LanRhyme" target="_blank" rel="noopener noreferrer">
  <img src="https://img.shields.io/badge/愛發電-@LanRhyme-946ce6?style=for-the-badge&logo=afdian&logoColor=white" alt="愛發電"></a>
</p>

MicYou 是一款強大的工具，能夠將您的 Android 裝置轉變為 PC 的高品質麥克風。它採用 Kotlin Multiplatform 與 Jetpack Compose/Material 3 構建。

本專案基於 [AndroidMic](https://github.com/teamclouday/AndroidMic) 開發而成。

## 主要功能

- **多種連線模式**：支援 Wi-Fi、USB (ADB/AOA) 與藍牙連線
- **音訊處理**：內建噪聲抑制、自動增益控制 (AGC) 與去混響功能
- **跨平台支援**：
  - **Android 客戶端**：採用現代 Material 3 設計，支援深色與淺色主題
  - **桌面端服務端**：可在 Windows、Linux 與 macOS 上接收音訊
- **虛擬麥克風**：搭配 VB-Cable 可作為系統麥克風輸入使用
- **高度可自訂**：支援調整取樣率、聲道數與音訊格式

## 軟體截圖

### Android 客戶端
|                            主畫面                             |                           設定                               |
|:-----------------------------------------------------------:|:-------------------------------------------------------------:|
| <img src="img/android_screenshot_main_zh-tw.png" width="300" /> | <img src="img/android_screenshot_settings_zh-tw.png" width="300" /> |

### 桌面端
<img src="img/desktop_screenshot_zh-tw.png" width="600" />

## 使用說明
快速開始指南及各平台安裝說明已移至常見問題文件：

- [快速開始](./docs/FAQ_TW.md#快速開始)
- [常見問題 (FAQ)](./docs/FAQ_TW.md#常見問題)

## 貢獻指南

我們歡迎各種形式的貢獻！無論是回報 Bug、提出功能建議、協助翻譯還是貢獻程式碼，都請參閱我們的 [貢獻指南](./CONTRIBUTING_zh-tw.md) 以開始參與。

## Contributors
<a href="https://github.com/LanRhyme/MicYou/graphs/contributors">
  <img src="https://contrib.rocks/image?repo=LanRhyme/MicYou" />
</a>


Made with [contrib.rocks](https://contrib.rocks).

## Star History

<a href="https://www.star-history.com/#LanRhyme/MicYou&type=date&legend=top-left">
 <picture>
   <source media="(prefers-color-scheme: dark)" srcset="https://api.star-history.com/svg?repos=LanRhyme/MicYou&type=date&theme=dark&legend=top-left" />
   <source media="(prefers-color-scheme: light)" srcset="https://api.star-history.com/svg?repos=LanRhyme/MicYou&type=date&legend=top-left" />
   <img alt="Star History Chart" src="https://api.star-history.com/svg?repos=LanRhyme/MicYou&type=date&legend=top-left" />
 </picture>
</a>
