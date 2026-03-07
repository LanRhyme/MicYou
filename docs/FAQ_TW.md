# 常見問題

<p align="center">
  <a href="./FAQ_ZH.md">简体中文</a> | <b>繁體中文</b> | <a href="./FAQ.md">English</a>
</p>

## 快速開始

### 1. 下載 ADB
- 從 [Android Developers](https://developer.android.com/tools/releases/platform-tools?hl=zh_cn) 下載
- 使用套件管理工具安裝
   - `winget install -e --id Google.PlatformTools`
   - `sudo apt install android-tools-adb`
   - `sudo pacman -S android-tools`
   - ...

大多數情況下 ADB 會自動加入系統環境變數，如未加入請自行設定。

### 2. 啟用 USB 偵錯
以 OneUI 8 為例

1. 進入「設定」，點選「關於手機」
2. 點選「軟體資訊」，找到「編譯編號」，連點 7 次以啟用開發者選項
3. 返回「設定」>「開發者選項」，啟用「USB 偵錯」

### 3. 使用 USB 連線
請使用品質良好且穩定的傳輸線，並在桌面端與 Android 應用同時將連線模式切換為 `USB`。

### 4. 使用 Wi‑Fi 連線
請確保 Android 裝置與電腦位於同一網路，並在桌面端與 Android 應用同時切換成 `Wi‑Fi` 模式。

### Android
1. 下載並安裝 APK 到您的 Android 裝置
2. 確保您的裝置與 PC 位於同一網路（Wi-Fi 模式），或透過 USB 連線

### Windows
1. 執行桌面端應用程式
2. 設定連線模式以匹配 Android 應用


### macOS

> [!IMPORTANT]
> 若您使用 Apple Silicon（如 M1/M2）Mac，未安裝或未啟用 Rosetta 2 時，藍牙模式可能無法正常使用。

建議透過 Homebrew 安裝以下套件：

```bash
brew install blackhole-2ch --cask
brew install switchaudio-osx --formulae
```

BlackHole 為必要軟體（virtual audio driver）。若尚未安裝 Homebrew，可至 https://existential.audio/blackhole/download/ 下載安裝程式。安裝完成後請重新啟動電腦。

從 [GitHub Releases](https://github.com/LanRhyme/MicYou/releases) 下載並安裝到「應用程式」資料夾後，首次開啟可能會被 Gatekeeper 攔截。

若系統提示「不受信任的開發者」，請至「系統設定」或「系統偏好設定」→「隱私權與安全性」允許應用程式。

若顯示「應用程式已損毀」訊息，可在終端執行：
```bash
sudo xattr -r -d com.apple.quarantine /Applications/MicYou.app
```

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

## 無法連線裝置

### Wi-Fi 模式

1. **確認防火牆設定**
   Windows 系統可能會攔截入站連線。可以嘗試依照以下方法手動放行連接埠：
   1. 按下`Win+R`輸入`cmd`，同時按住`Ctrl+Shift`鍵，點選「確定」以系統管理員身分執行命令提示字元
   2. 輸入以下命令並按下 Enter：

      ```
      netsh advfirewall firewall add rule name="Allow 6666" dir=in action=allow protocol=TCP localport=6000
      ```

      MicYou 預設使用`6000`連接埠建立連線，如有需要可自行更改

      若未出現任何訊息，表示操作成功，可以重新嘗試連線

2. **檢查裝置是否在同一子網路**
   - 確保 Android 手機與 PC 連接的是**同一個**路由器的 Wi-Fi
   - 確保路由器後台中已關閉 **AP 隔離 / 網路裝置隔離** 或類似功能（如何進入路由器後台請自行查閱路由器說明）

> [!TIP]
> 進階使用者可自行嘗試使用 Nmap 或 ping 等工具檢查連線性
>
> ~雖然說進階使用者大概也看不到這裡~

### USB (ADB) 模式

1. **開啟開發者選項**
> 此處列出的方案不一定適用於所有裝置，**請善用搜尋工具**取得為自己的裝置開啟 ADB 模式的教學
   - 在手機設定中找到「關於本機」，連續點擊 7 次「系統版本號」以開啟開發者選項
   - 進入開發者選項，開啟 **USB 偵錯**
2. **確認 ADB 連線**
   > 電腦端需要安裝 ADB 工具

   執行 `adb devices` 確認有且僅有一個裝置已連線

   若此處列出了多個裝置，則需要指定埠轉發的裝置，用法：

   ```
   adb -s <裝置序列號> reverse tcp:6000 tcp:6000
   ```
   裝置序列號可在 adb devices 中找到

## 連線裝置後無聲音輸出

請確保您的 VB-Audio 驅動已正確安裝，且以下裝置未被停用：

- Windows 輸出裝置：CABLE Input (VB-Audio Virtual Cable)
- Windows 輸入裝置：CABLE Output (VB-Audio Virtual Cable)

檢查方式：設定 > 聲音

需要確保以下兩項均處於**已啟用**狀態：

![輸入裝置](https://github.com/user-attachments/assets/1cf5f97f-1647-4fb0-a152-85be2697df39)
![輸出裝置](https://github.com/user-attachments/assets/9e9ef42d-186f-42a6-ba4d-7b1a3815f860)
