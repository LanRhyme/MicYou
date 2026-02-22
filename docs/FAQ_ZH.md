# 常见问题

<p align="center">
   <b>简体中文</b> | <a href="./FAQ_TW.md">繁體中文</a> | <a href="./FAQ.md">English</a>
</p>

## 无法连接设备

### Wi-Fi 模式

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

> [Tip]
> 高级用户可自行尝试使用 Nmap 或 ping 等工具检查连接性
>
> ~虽然说高级用户一般也看不到这里~

### USB (ADB) 模式

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

## 连接设备后无声音输出

请确保您的 VB-Audio 驱动已正确安装，并且以下设备未被禁用：

- Windows 输出设备：CABLE Input (VB-Audio Virtual Cable)
- Windows 输入设备：CABLE Output (VB-Audio Virtual Cable)

检查方式：设置 > 声音

需要确保以下两项均处于**已启用**状态：

![输入设备](https://github.com/user-attachments/assets/1cf5f97f-1647-4fb0-a152-85be2697df39)
![输出设备](https://github.com/user-attachments/assets/9e9ef42d-186f-42a6-ba4d-7b1a3815f860)
