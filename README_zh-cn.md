# MicYou

<p align="center">
  <img src="./img/app_icon.png" width="128" height="128" />
</p>

<p align="center">
  <b>简体中文</b> | <a href="./README_zh-tw.md">繁體中文</a> | <a href="./README.md">English</a>
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

<h6 align="center">技术栈</h6>
<p align="center">
  <img alt="Kotlin" src="https://img.shields.io/badge/kotlin-%237F52FF.svg?style=for-the-badge&logo=kotlin&logoColor=white" />
  <img alt="Jetpack Compose" src="https://img.shields.io/badge/Jetpack_Compose-4285F4?style=for-the-badge&logo=jetpackcompose&logoColor=white">
</p>

<h6 align="center">受支持的平台</h6>

<p align="center">
  <img alt="Android" src="https://img.shields.io/badge/Android-3DDC84?style=for-the-badge&logo=android&logoColor=white" />
  <img alt="Windows" src="https://custom-icon-badges.demolab.com/badge/Windows-0078D6?&style=for-the-badge&logo=windows11&logoColor=white" />
  <img alt="Linux" src="https://img.shields.io/badge/Linux-FCC624?style=for-the-badge&logo=linux&logoColor=black" />
  <img alt="macOS" src="https://img.shields.io/badge/mac%20os-000000?style=for-the-badge&logo=apple&logoColor=F0F0F0" />
</p>

<h6 align="center">赞助我</h6>

<p align="center">
<a href="https://afdian.com/a/LanRhyme" target="_blank" rel="noopener noreferrer">
  <img src="https://img.shields.io/badge/%E7%88%B1%E5%8F%91%E7%94%B5-@LanRhyme-946ce6?style=for-the-badge&logo=afdian&logoColor=white" alt="爱发电"></a>
</p>

MicYou 是一款强大的工具，能够将您的 Android 设备转变为 PC 的高质量麦克风。它采用 Kotlin Multiplatform 和 Jetpack Compose/Material 3 构建。

本项目基于 [AndroidMic](https://github.com/teamclouday/AndroidMic) 开发而成。

## 主要功能

- **多种连接模式**：支持 Wi-Fi、USB (ADB/AOA) 和蓝牙连接
- **音频处理**：内置噪声抑制、自动增益控制 (AGC) 和去混响功能
- **跨平台支持**：
  - **Android 客户端**：采用现代 Material 3 设计，支持深色与浅色主题
  - **桌面端服务端**：可在 Windows、Linux 和 macOS 上接收音频
- **虚拟麦克风**：配合 VB-Cable 可作为系统麦克风输入使用
- **高度可定制**：支持调整采样率、声道数和音频格式

## 软件截图

### Android 客户端
|                            主界面                            |                              设置                               |
|:---------------------------------------------------------:|:-------------------------------------------------------------:|
| <img src="img/android_screenshot_main_zh-cn.jpg" width="300" /> | <img src="img/android_screenshot_settings_zh-cn.jpg" width="300" /> |

### 桌面端
<img src="img/desktop_screenshot_zh-cn.png" width="600" />

## 使用指南
快速开始指南及各平台安装说明已移至常见问题文档：

- [快速开始](./docs/FAQ_ZH.md#快速开始)
- [常见问题](./docs/FAQ_ZH.md#常见问题)

## 贡献指南

我们欢迎各种形式的贡献！无论是报告 Bug、提出功能建议、协助翻译还是贡献代码，都请参阅我们的 [贡献指南](./CONTRIBUTING_zh-cn.md) 以开始参与。

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
