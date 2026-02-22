# FAQ

<p align="center">
   <a href="./FAQ_ZH.md">简体中文</a> | <a href="./FAQ_TW.md">繁體中文</a> | <b>English</b>
</p>

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

> [Tip]
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
