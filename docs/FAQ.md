# FAQ

<p align="center">
   <a href="./FAQ_ZH.md">简体中文</a> | <a href="./FAQ_TW.md">繁體中文</a> | <b>English</b>
</p>

## Quick Start

### 1. Download ADB
- Download from [Android Developers](https://developer.android.com/tools/releases/platform-tools?hl=zh_cn)
- Install via package manager
   - `winget install -e --id Google.PlatformTools`
   - `sudo apt install android-tools-adb`
   - `sudo pacman -S android-tools`
   - ...

In most cases ADB will be added to your environment variables automatically. If not, please add it manually.

### 2. Enable USB Debugging
Using OneUI 8 as an example

1. Go to Settings, tap `About phone`
2. Tap `Software information`, find `Build number`, tap it **7** times. When you see "No need, developer mode has been enabled", it means the developer mode has been successfully enabled.
3. Go back to Settings, tap `Developer options`, find `USB debugging`, and enable it.

### 3. USB connection
Use a stable data cable, and set the connection mode to `USB` on both the desktop app and the Android app.

### 4. Wi-Fi connection
Ensure your Android device and PC are on the same network, and set the connection mode to `Wi-Fi` on both the desktop app and the Android app.

### Android
1. Download and install the APK on your Android device.
2. Ensure your device is on the same network as your PC (for Wi-Fi) or connected via USB.

### Windows
1. Run the desktop application.
2. Configure the connection mode to match the Android app.

### macOS

> [!IMPORTANT]
> If you are using an Apple Silicon Mac, Bluetooth mode cannot be used without Rosetta 2 translation.

To ensure your experience, you need to install some dependencies via Homebrew:

```bash
brew install blackhole-2ch --cask
brew install switchaudio-osx --formulae
```

**BlackHole must be installed**. If you don't have Homebrew, go to https://existential.audio/blackhole/download/ to download the installer. Regardless of whether you install via Homebrew or the installer, please restart after installation.

After downloading the app from [GitHub Releases](https://github.com/LanRhyme/MicYou/releases) and installing it in your Applications folder, Gatekeeper may block it during first use.

If prompted with “Untrusted Developer,” navigate to **System Settings/System Preferences -> Privacy & Security** to allow the app to run.

If prompted with “The application is damaged,” resolve it by executing the following command:
```bash
sudo xattr -r -d com.apple.quarantine /Applications/MicYou.app
```

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
# Or use dnf/yum:
sudo dnf install MicYou-*.rpm
```

**AUR (Arch Linux and derivatives):**
```bash
# Clone the AUR repo and install the package
git clone https://aur.archlinux.org/micyou-bin.git
cd micyou-bin
makepkg -si

# Or use an AUR helper like paru/yay
paru -S micyou-bin
```

**Run the application:**
```bash
# After installation, you can run MicYou from your application menu
# Or from terminal:
MicYou
```

## Cannot connect to device

### Wi-Fi Mode

1. **Check Firewall Settings**
   Windows may block inbound connections. You can try to manually allow the port using the following method:
   1. Press `Win+R`, type `cmd`, then hold `Ctrl+Shift` and click "OK" to run Command Prompt as administrator.
   2. Enter the following command and press Enter:

      ```
      netsh advfirewall firewall add rule name="Allow 6666" dir=in action=allow protocol=TCP localport=6000
      ```

      MicYou uses port `6000` for connection by default; you can change it if needed.

      If no message pops up, the operation was successful. Try connecting again.

2. **Check if devices are on the same subnet**
   - Ensure the Android phone and PC are connected to the **same** router's Wi-Fi.
   - Ensure that **AP Isolation / Network Device Isolation** or similar features are disabled in the router settings (refer to your router's manual on how to access the settings).

> [!TIP]
> Advanced users can try using tools like Nmap or ping to check connectivity.
>
> ~Though advanced users probably won't be reading this anyway~

### USB (ADB) Mode

1. **Enable Developer Options**
   > The steps listed here may not apply to all devices. **Please use search engines** to find tutorials on how to enable ADB mode for your specific device.
   - Find "About phone" in phone settings, and tap "Build number" 7 times to enable Developer Options.
   - Enter Developer Options and enable **USB debugging**.
2. **Confirm ADB connection**

   > ADB tools must be installed on the computer.

   Run `adb devices` to confirm that one and only one device is connected.

   If multiple devices are listed, you need to specify the device for port forwarding:

   ```
   adb -s <serial_number> reverse tcp:6000 tcp:6000
   ```

   The device serial number can be found in the output of `adb devices`.

## No audio output after connecting

Please ensure that your VB-Audio driver is correctly installed and that the following devices are not disabled:

- Windows Output Device: CABLE Input (VB-Audio Virtual Cable)
- Windows Input Device: CABLE Output (VB-Audio Virtual Cable)

How to check: Settings > Sound

Ensure both of the following are **Enabled**:

![Input device](https://github.com/user-attachments/assets/1cf5f97f-1647-4fb0-a152-85be2697df39)
![Output device](https://github.com/user-attachments/assets/9e9ef42d-186f-42a6-ba4d-7b1a3815f860)
