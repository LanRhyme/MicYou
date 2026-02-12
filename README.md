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
  - **Desktop Server**: Receive audio on Windows.
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

### Desktop
1. Run the desktop application.
2. Configure the connection mode to match the Android app.

> [!TIP]
> Having issues? Check out the [FAQ](./docs/FAQ.md)

## Building from Source

This project is built using Kotlin Multiplatform.

**Android:**
```bash
./gradlew :composeApp:assembleDebug
```

**Desktop:**
```bash
./gradlew :composeApp:run
```

## Star History

[![Star History Chart](https://api.star-history.com/svg?repos=lanrhyme/MicYou&type=Date)](https://star-history.com/#lanrhyme/MicYou&Date)

