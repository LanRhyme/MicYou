# 常見問題

<p align="center">
  <a href="./FAQ_ZH.md">简体中文</a> | <b>繁體中文</b> | <a href="./FAQ.md">English</a>
</p>

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

> [Tip]
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
