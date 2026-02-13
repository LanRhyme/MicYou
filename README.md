# MicYou

<p align="center">
  <img src="./img/app_icon.png" width="128" height="128" />
</p>

<p align="center">
  <a href="./README_ZH.md">简体中文</a> | <b>English</b>
</p>

MicYou is a powerful tool that turns your Android device into a high-quality wireless microphone for your PC. Built with Kotlin Multiplatform and Jetpack Compose/Material 3.

Based on the [AndroidMic](https://github.com/teamclouday/AndroidMic) project.

## Features

- **Multiple Connection Modes**: Support for Wi-Fi, USB (ADB/AOA), and Bluetooth.
- **Audio Processing**: Built-in Noise Suppression, Auto Gain Control (AGC), and Dereverberation.
- **Cross-Platform**:
  - **Android Client**: Modern Material 3 interface, dark/light theme support.
  - **Desktop Server**: Receive audio on Windows/Linux.
- **Virtual Microphone**: Works seamlessly with VB-Cable to act as a system microphone input.
- **Customizable**: Adjust sample rate, channel count, and audio format.

## Screenshots

### Android App
|                        Main Screen                        |                           Settings                            |
|:---------------------------------------------------------:|:-------------------------------------------------------------:|
| <img src="img/android_screenshot_main.jpg" width="300" /> | <img src="img/android_screenshot_settings.jpg" width="300" /> |

### Desktop App
<img src="img/pc_screenshot.png" width="600" />

## Getting Started

### Android
1. Download and install the APK on your Android device.
2. Ensure your device is on the same network as your PC (for Wi-Fi) or connected via USB.

### Windows
1. Run the desktop application.
2. Configure the connection mode to match the Android app.

### Linux

#### Using pre-built packages (recommended)
Pre-built packages are available in [GitHub Releases](https://github.com/LanRhyme/MicYou/releases).

**DEB package (Debian/Ubuntu/Mint etc.):**
```bash
# Download the .deb package from GitHub Releases
sudo dpkg -i MicYou-*.deb
# If dependencies are missing:
sudo apt install -f
```

**RPM package (Fedora/RHEL/openSUSE etc.):**
```bash
# Download the .rpm package from GitHub Releases
sudo rpm -i MicYou-*.rpm
# Or using dnf/yum:
sudo dnf install MicYou-*.rpm
```

**Run the application:**
```bash
# After installation, you can run MicYou from your application menu
# Or from terminal:
MicYou
```

> [!TIP]
> Having issues? Check out the [FAQ](./docs/FAQ.md)

## Building from Source

This project is built using Kotlin Multiplatform.

**Android app (APK):**
```bash
./gradlew :composeApp:assembleDebug
```

**Desktop application (run directly):**
```bash
./gradlew :composeApp:run
```

**Build packages for distribution:**

**Windows installer (NSIS):**
```bash
./gradlew :composeApp:packageWindowsNsis
```

**Windows ZIP archive:**
```bash
./gradlew :composeApp:packageWindowsZip
```

**Linux DEB package:**
```bash
./gradlew :composeApp:packageDeb
```

**Linux RPM package:**
```bash
./gradlew :composeApp:packageRpm
```

## Star History

[![Star History Chart](https://api.star-history.com/svg?repos=lanrhyme/MicYou&type=Date)](https://star-history.com/#lanrhyme/MicYou&Date)
