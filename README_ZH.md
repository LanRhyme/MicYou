# MicYou

<p align="center">
  <img src="./img/app_icon.png" width="128" height="128" />
</p>

<p align="center">
  <b>简体中文</b> | <a href="./README_TW.md">繁體中文</a> | <a href="./README.md">English</a>
</p>

MicYou 是一款强大的工具，可以将您的 Android 设备变成 PC 的高质量无线麦克风，于 Kotlin Multiplatform 和 Jetpack Compose/Material 3 构建

本项目基于 [AndroidMic](https://github.com/teamclouday/AndroidMic) 开发

## 主要功能

- **多种连接模式**：支持 Wi-Fi、USB (ADB/AOA) 和蓝牙连接
- **音频处理**：内置噪声抑制、自动增益控制 (AGC) 和去混响功能
- **跨平台支持**：
  - **Android 客户端**：现代 Material 3 界面，支持深色/浅色主题
  - **桌面端服务端**：支持 Windows/Linux 接收音频
- **虚拟麦克风**：配合 VB-Cable 可作为系统麦克风输入使用
- **高度可定制**：支持调整采样率、声道数和音频格式

## 软件截图

### Android 客户端
|                            主界面                            |                              设置                               |
|:---------------------------------------------------------:|:-------------------------------------------------------------:|
| <img src="img/android_screenshot_main.jpg" width="300" /> | <img src="img/android_screenshot_settings.jpg" width="300" /> |

### 桌面端
<img src="img/pc_screenshot.png" width="600" />

## 使用指南

### Android
1. 下载并安装 APK 到您的 Android 设备
2. 确保您的设备与 PC 处于同一网络（Wi-Fi 模式），或通过 USB 连接

### Windows
1. 运行桌面端应用程序
2. 配置连接模式以匹配 Android 应用

### Linux

#### 使用预编译包（推荐）
预编译包可在 [GitHub Releases](https://github.com/LanRhyme/MicYou/releases) 下载

**DEB 包（适用于 Debian/Ubuntu/Mint 等发行版）：**
```bash
# 从 GitHub Releases 下载 .deb 包
sudo dpkg -i MicYou-*.deb
# 如果缺少依赖：
sudo apt install -f
```

**RPM 包（适用于 Fedora/RHEL/openSUSE 等发行版）：**
```bash
# 从 GitHub Releases 下载 .rpm 包
sudo rpm -i MicYou-*.rpm
# 或者使用 dnf/yum：
sudo dnf install MicYou-*.rpm
```

**AUR 仓库（适用于 Arch Linux 及其衍生发行版）：**
```bash
# 克隆 AUR 仓库并自动安装软件包及其依赖
git clone https://aur.archlinux.org/micyou-bin.git
cd micyou-bin
makepkg -si

# 或者使用 paru 等 AUR helpers
paru -S micyou-bin
```

**运行应用：**
```bash
# 安装后可以从应用菜单运行 MicYou
# 或者从终端运行：
MicYou
```

> [!TIP]
> 遇到问题？请查看：[常见问题](./docs/FAQ_ZH.md)

## 源码构建

本项目使用 Kotlin Multiplatform 构建

**Android 应用（APK）：**
```bash
./gradlew :composeApp:assembleDebug
```

**桌面应用（直接运行）：**
```bash
./gradlew :composeApp:run
```

**构建发布包：**

**Windows 安装包（NSIS）：**
```bash
./gradlew :composeApp:packageWindowsNsis
```

**Windows ZIP 归档：**
```bash
./gradlew :composeApp:packageWindowsZip
```

**Linux DEB 包：**
```bash
./gradlew :composeApp:packageDeb
```

**Linux RPM 包：**
```bash
./gradlew :composeApp:packageRpm
```

## Star History

[![Star History Chart](https://api.star-history.com/svg?repos=lanrhyme/MicYou&type=Date)](https://star-history.com/#lanrhyme/MicYou&Date)
