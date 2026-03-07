# MicYou 文档

<p align="center">
   <b>简体中文</b> | <a href="./FAQ_TW.md">繁體中文</a> | <a href="./FAQ.md">English</a>
</p>

## 快速开始

### 1. 下载 ADB
- 从 [Android Developers](https://developer.android.com/tools/releases/platform-tools?hl=zh_cn) 下载
- 使用包管理器下载
   - `winget install -e --id Google.PlatformTools`
   - `sudo apt install android-tools-adb`
   - `sudo pacman -S android-tools`
   - ...

大部分情况下会自动将 ADB 加入环境变量。如果没有，请自行配置

### 2. 启用 USB 调试
以 OneUI 8 为例

1. 进入设置，点击`关于手机`
2. 点击`软件信息`，找到`编译编号`，点击 **7** 下，当见到 `不需要，开发者模式已启用`，即开启成功
3. 返回设置，点击`开发者选项`，找到`USB 调试`，开启即可

### 3. 使用 USB 连接
确保您在使用一根**稳定**的数据线，并**同时**在桌面端和 Android 客户端将连接模式切换为 `USB`

### 4. 使用 Wi-Fi 连接
确保您的 Android 设备与 PC 处于**同一网络环境**下，并**同时**在桌面端和 Android 客户端将连接模式切换为 `Wi-Fi`。

### Android
1. 下载并安装 APK 到您的 Android 设备
2. 确保您的设备与 PC 处于同一网络（Wi-Fi 模式），或通过 USB 连接

### Windows
1. 运行桌面端应用程序
2. 配置连接模式以匹配 Android 应用

### macOS

> [!IMPORTANT]
> 如果您使用的是 Apple Silicon Mac，在不使用 Rosetta 2 转译的前提下无法使用蓝牙模式

为了保证您的使用体验，需要通过 Homebrew 安装一些依赖

```bash
brew install blackhole-2ch --cask
brew install switchaudio-osx --formulae
```

**BlackHole 必须安装**，若没有 Homebrew 则前往 https://existential.audio/blackhole/download/ 下载安装包。无论您是通过 Homebrew 还是手动安装，安装后请务必重启

在 [GitHub Releases](https://github.com/LanRhyme/MicYou/releases) 下载应用并安装到应用程序目录后，第一次使用可能会被 Gatekeeper 拦截

若提示不受信任的开发者，您可以前往 **"系统设置"/"系统偏好设置" -> "隐私与安全"** 中允许应用运行

若提示“应用已损坏”，执行以下指令解决：
```bash
sudo xattr -r -d com.apple.quarantine /Applications/MicYou.app
```

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

## 常见问题

### 无法连接设备

#### Wi-Fi 模式

1. **确认防火墙设置**
   Windows 系统可能会拦截入站连接。可以尝试按照以下方法手动放行端口：
   1. 按下`Win+R`输入`cmd`，同时按住`Ctrl+Shift`键，点击“确定”以管理员身份运行命令提示符
   2. 输入以下命令并回车：
      ```
      netsh advfirewall firewall add rule name="Allow 6666" dir=in action=allow protocol=TCP localport=6000
      ```
      MicYou 默认使用`6000`端口建立连接，如有需要可自行更改

      如果什么都没弹出来说明操作成功，可以重新试试看能不能连接上

2. **检查设备是否在同一子网**
   - 确保 Android 手机和 PC 连接的是**同一个**路由器的 Wi-Fi
   - 确保路由器后台中已关闭 **AP 隔离 / 网络设备隔离** 或类似字样的功能（如何进入路由器后台请自行查阅路由器说明）

> [!TIP]
> 高级用户可自行尝试使用 Nmap 或 ping 等工具检查连接性
>
> ~虽然说高级用户一般也看不到这里~

#### USB (ADB) 模式

1. **开启开发者选项**
> 此处列出的方案不一定适用于所有设备，**请善用搜索工具**获取为自己的设备开启 ADB 模式的教程
   - 在手机设置中找到“关于本机”，连续点击 7 次“系统版本号”开启开发者选项
   - 进入开发者选项，开启 **USB 调试**
2. **确认 ADB 连接**
   > 电脑端需要安装 ADB 工具

   运行 `adb devices` 确认有且仅有一个设备已连接

   如果此处列出了多个设备，则需要指定端口转发的设备，用法：

   ```
   adb -s <设备序列号> reverse tcp:6000 tcp:6000
   ```
   设备序列号可在 adb devices 中找到

### 连接设备后无声音输出

请确保您的 VB-Audio 驱动已正确安装，并且以下设备未被禁用：

- Windows 输出设备：CABLE Input (VB-Audio Virtual Cable)
- Windows 输入设备：CABLE Output (VB-Audio Virtual Cable)

检查方式：设置 > 声音

需要确保以下两项均处于**已启用**状态：

![输入设备](https://github.com/user-attachments/assets/1cf5f97f-1647-4fb0-a152-85be2697df39)
![输出设备](https://github.com/user-attachments/assets/9e9ef42d-186f-42a6-ba4d-7b1a3815f860)
