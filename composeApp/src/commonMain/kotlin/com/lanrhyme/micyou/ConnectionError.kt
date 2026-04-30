package com.lanrhyme.micyou

import micyou.composeapp.generated.resources.Res
import micyou.composeapp.generated.resources.*
import org.jetbrains.compose.resources.getString

/**
 * 连接错误类型枚举
 * 用于分类不同的连接失败原因，提供更精确的错误信息和恢复建议
 */
enum class ConnectionErrorType {
    // 网络连接相关错误
    NetworkTimeout,          // 网络连接超时
    NetworkUnreachable,      // 网络不可达（IP 错误或网络断开）
    PortInUse,               // 端口已被占用
    ConnectionRefused,       // 连接被拒绝（服务未启动）

    // 权限相关错误
    PermissionDenied,        // 权限不足（防火墙、管理员权限）
    FirewallBlocked,         // 防火墙阻止连接
    AdminPrivilegeRequired,  // 需要管理员权限

    // 设备相关错误
    DeviceNotFound,          // 设备未找到（蓝牙设备）
    BluetoothDisabled,       // 蓝牙功能被禁用
    BluetoothServiceUnavailable, // 蓝牙服务不可用 (BlueZ未运行)
    BluetoothAdapterNotFound,     // 蓝牙适配器未找到
    UsbConnectionFailed,     // USB 连接失败
    AdbCommandFailed,        // ADB 命令执行失败

    // 协议相关错误
    HandshakeFailed,         // 握手失败（协议不匹配）
    ProtocolError,           // 协议错误
    VersionMismatch,         // 版本不匹配

    // UDP 相关错误
    UdpPortBlocked,          // UDP 端口被防火墙阻止

    // 音频相关错误
    AudioDeviceError,        // 音频设备错误
    AudioFormatError,        // 音频格式不支持

    // Linux 特有错误
    BlueZNotInstalled,       // BlueZ 未安装
    RfcommBindFailed,        // RFCOMM 绑定失败

    // 通用错误
    UnknownError             // 未知的错误类型
}

/**
 * 连接错误详情
 * 包含错误类型、原始错误消息、恢复建议等详细信息
 */
data class ConnectionErrorDetails(
    val type: ConnectionErrorType,
    val originalMessage: String,
    val localizedTitle: String,
    val localizedMessage: String,
    val recoverySuggestions: List<String> = emptyList(),
    val showRetryButton: Boolean = true,
    val showHelpButton: Boolean = false,
    val helpUrl: String? = null
)

/**
 * 连接错误助手类
 * 用于分析和生成详细的错误信息
 */
object ConnectionErrorHelper {
    
    /**
     * 根据异常分析错误类型
     */
    fun analyzeError(exception: Exception, mode: ConnectionMode): ConnectionErrorType {
        val message = exception.message ?: ""
        
        return when {
            // 网络超时
            message.contains("timeout", ignoreCase = true) ||
            message.contains("Timeout", ignoreCase = true) ->
                ConnectionErrorType.NetworkTimeout
            
            // 端口占用
            message.contains("Bind", ignoreCase = true) ||
            message.contains("port is already in use", ignoreCase = true) ||
            message.contains("Address already in use", ignoreCase = true) ->
                ConnectionErrorType.PortInUse
            
            // 连接被拒绝
            message.contains("Connection refused", ignoreCase = true) ||
            message.contains("refused", ignoreCase = true) ->
                ConnectionErrorType.ConnectionRefused
            
            // 网络不可达
            message.contains("unreachable", ignoreCase = true) ||
            message.contains("No route to host", ignoreCase = true) ||
            message.contains("Network is unreachable", ignoreCase = true) ->
                ConnectionErrorType.NetworkUnreachable
            
            // 防火墙阻止
            message.contains("firewall", ignoreCase = true) ||
            message.contains("blocked", ignoreCase = true) ->
                ConnectionErrorType.FirewallBlocked
            
            // 权限不足
            message.contains("permission", ignoreCase = true) ||
            message.contains("access denied", ignoreCase = true) ||
            message.contains("privilege", ignoreCase = true) ->
                ConnectionErrorType.PermissionDenied
            
            // ADB 相关
            message.contains("adb", ignoreCase = true) ->
                ConnectionErrorType.AdbCommandFailed
            
            // 蓝牙相关
            message.contains("bluetooth", ignoreCase = true) ||
            message.contains("Bluetooth", ignoreCase = true) ->
                if (mode == ConnectionMode.Bluetooth) ConnectionErrorType.BluetoothDisabled
                else ConnectionErrorType.DeviceNotFound

            // BlueZ 相关 (Linux 蓝牙)
            message.contains("bluez", ignoreCase = true) ||
            message.contains("BlueZ", ignoreCase = true) ||
            message.contains("hciconfig", ignoreCase = true) ->
                ConnectionErrorType.BlueZNotInstalled

            // RFCOMM 相关
            message.contains("rfcomm", ignoreCase = true) ||
            message.contains("RFCOMM", ignoreCase = true) ->
                ConnectionErrorType.RfcommBindFailed

            // 蓝牙适配器未找到
            message.contains("hci", ignoreCase = true) ||
            message.contains("adapter", ignoreCase = true) && message.contains("not found", ignoreCase = true) ->
                ConnectionErrorType.BluetoothAdapterNotFound
            
            // USB 相关
            message.contains("usb", ignoreCase = true) ||
            message.contains("USB", ignoreCase = true) ->
                ConnectionErrorType.UsbConnectionFailed
            
            // 握手失败
            message.contains("handshake", ignoreCase = true) ||
            message.contains("握手", ignoreCase = true) ->
                ConnectionErrorType.HandshakeFailed
            
            // 音频相关
            message.contains("audio", ignoreCase = true) ||
            message.contains("Audio", ignoreCase = true) ->
                ConnectionErrorType.AudioDeviceError
            
            // UDP 相关
            message.contains("udp", ignoreCase = true) ||
            message.contains("UDP", ignoreCase = true) ->
                ConnectionErrorType.UdpPortBlocked
            
            // 其他
            else -> ConnectionErrorType.UnknownError
        }
    }
    
    private fun extractAdbCommand(message: String): String? {
        val delimiters = listOf("：", ":")
        for (delimiter in delimiters) {
            val afterDelimiter = message.substringAfter(delimiter).trim()
            if (afterDelimiter.isNotBlank() && afterDelimiter != message) {
                return afterDelimiter
            }
        }
        return null
    }
    
    /**
     * 生成详细的错误信息（需要配合 Localization）
     */
    suspend fun generateErrorDetails(
        type: ConnectionErrorType,
        originalMessage: String,
        mode: ConnectionMode,
        port: Int? = null,
        ip: String? = null
    ): ConnectionErrorDetails {
        return when (type) {
            ConnectionErrorType.NetworkTimeout -> ConnectionErrorDetails(
                type = type,
                originalMessage = originalMessage,
                localizedTitle = getString(Res.string.errorNetworkTimeoutTitle),
                localizedMessage = getString(Res.string.errorNetworkTimeoutMessage),
                recoverySuggestions = listOf(
                    getString(Res.string.errorSuggestionCheckNetwork),
                    getString(Res.string.errorSuggestionCheckTargetRunning),
                    getString(Res.string.errorSuggestionTryDifferentPort)
                )
            )
            
            ConnectionErrorType.PortInUse -> ConnectionErrorDetails(
                type = type,
                originalMessage = originalMessage,
                localizedTitle = getString(Res.string.errorPortInUseTitle),
                localizedMessage = String.format(getString(Res.string.errorPortInUseMessage), port?.toString() ?: "6000"),
                recoverySuggestions = listOf(
                    getString(Res.string.errorSuggestionChangePort),
                    getString(Res.string.errorSuggestionCheckOtherApps)
                )
            )
            
            ConnectionErrorType.ConnectionRefused -> ConnectionErrorDetails(
                type = type,
                originalMessage = originalMessage,
                localizedTitle = getString(Res.string.errorConnectionRefusedTitle),
                localizedMessage = if (mode == ConnectionMode.Wifi) 
                    String.format(getString(Res.string.errorConnectionRefusedWifiMessage), ip ?: "")
                else getString(Res.string.errorConnectionRefusedMessage),
                recoverySuggestions = listOf(
                    getString(Res.string.errorSuggestionCheckServerRunning),
                    getString(Res.string.errorSuggestionCheckServerConfig)
                )
            )
            
            ConnectionErrorType.NetworkUnreachable -> ConnectionErrorDetails(
                type = type,
                originalMessage = originalMessage,
                localizedTitle = getString(Res.string.errorNetworkUnreachableTitle),
                localizedMessage = String.format(getString(Res.string.errorNetworkUnreachableMessage), ip ?: ""),
                recoverySuggestions = listOf(
                    getString(Res.string.errorSuggestionCheckNetworkConnection),
                    getString(Res.string.errorSuggestionVerifyIpAddress),
                    getString(Res.string.errorSuggestionCheckWifiConnected)
                )
            )
            
            ConnectionErrorType.FirewallBlocked -> ConnectionErrorDetails(
                type = type,
                originalMessage = originalMessage,
                localizedTitle = getString(Res.string.errorFirewallBlockedTitle),
                localizedMessage = String.format(getString(Res.string.errorFirewallBlockedMessage), port?.toString() ?: "6000"),
                recoverySuggestions = listOf(
                    getString(Res.string.errorSuggestionAddFirewallRule),
                    getString(Res.string.errorSuggestionRunAsAdmin)
                ),
                showHelpButton = true,
                helpUrl = "https://github.com/LanRhyme/MicYou/blob/master/docs/FAQ.md#firewall"
            )
            
            ConnectionErrorType.PermissionDenied -> ConnectionErrorDetails(
                type = type,
                originalMessage = originalMessage,
                localizedTitle = getString(Res.string.errorPermissionDeniedTitle),
                localizedMessage = getString(Res.string.errorPermissionDeniedMessage),
                recoverySuggestions = listOf(
                    getString(Res.string.errorSuggestionRunAsAdmin),
                    getString(Res.string.errorSuggestionCheckAntivirus)
                )
            )
            
            ConnectionErrorType.DeviceNotFound -> ConnectionErrorDetails(
                type = type,
                originalMessage = originalMessage,
                localizedTitle = getString(Res.string.errorDeviceNotFoundTitle),
                localizedMessage = getString(Res.string.errorDeviceNotFoundMessage),
                recoverySuggestions = listOf(
                    getString(Res.string.errorSuggestionEnableBluetooth),
                    getString(Res.string.errorSuggestionPairDevice)
                )
            )
            
            ConnectionErrorType.BluetoothDisabled -> ConnectionErrorDetails(
                type = type,
                originalMessage = originalMessage,
                localizedTitle = getString(Res.string.errorBluetoothDisabledTitle),
                localizedMessage = getString(Res.string.errorBluetoothDisabledMessage),
                recoverySuggestions = listOf(
                    getString(Res.string.errorSuggestionEnableBluetooth)
                )
            )

            ConnectionErrorType.BluetoothServiceUnavailable -> ConnectionErrorDetails(
                type = type,
                originalMessage = originalMessage,
                localizedTitle = getString(Res.string.errorBluetoothServiceUnavailableTitle),
                localizedMessage = getString(Res.string.errorBluetoothServiceUnavailableMessage),
                recoverySuggestions = listOf(
                    getString(Res.string.errorSuggestionStartBluetoothService),
                    getString(Res.string.errorSuggestionCheckBluetoothDaemon)
                )
            )

            ConnectionErrorType.BluetoothAdapterNotFound -> ConnectionErrorDetails(
                type = type,
                originalMessage = originalMessage,
                localizedTitle = getString(Res.string.errorBluetoothAdapterNotFoundTitle),
                localizedMessage = getString(Res.string.errorBluetoothAdapterNotFoundMessage),
                recoverySuggestions = listOf(
                    getString(Res.string.errorSuggestionCheckBluetoothHardware),
                    getString(Res.string.errorSuggestionCheckUsbBluetooth)
                )
            )

            ConnectionErrorType.BlueZNotInstalled -> ConnectionErrorDetails(
                type = type,
                originalMessage = originalMessage,
                localizedTitle = getString(Res.string.errorBlueZNotInstalledTitle),
                localizedMessage = getString(Res.string.errorBlueZNotInstalledMessage),
                recoverySuggestions = listOf(
                    getString(Res.string.errorSuggestionInstallBlueZ),
                    getString(Res.string.errorSuggestionCheckBlueZService)
                ),
                showHelpButton = true,
                helpUrl = "https://github.com/LanRhyme/MicYou/blob/master/docs/FAQ.md#bluetooth-linux"
            )

            ConnectionErrorType.RfcommBindFailed -> ConnectionErrorDetails(
                type = type,
                originalMessage = originalMessage,
                localizedTitle = getString(Res.string.errorRfcommBindFailedTitle),
                localizedMessage = getString(Res.string.errorRfcommBindFailedMessage),
                recoverySuggestions = listOf(
                    getString(Res.string.errorSuggestionReleaseRfcomm),
                    getString(Res.string.errorSuggestionRunAsAdmin)
                )
            )
            
            ConnectionErrorType.UsbConnectionFailed -> {
                val command = extractAdbCommand(originalMessage)
                ConnectionErrorDetails(
                    type = type,
                    originalMessage = originalMessage,
                    localizedTitle = getString(Res.string.errorUsbConnectionFailedTitle),
                    localizedMessage = getString(Res.string.errorUsbConnectionFailedMessage),
                    recoverySuggestions = buildList {
                        add(getString(Res.string.errorSuggestionCheckUsbCable))
                        add(getString(Res.string.errorSuggestionEnableUsbDebugging))
                        if (command != null) {
                            add(String.format(getString(Res.string.errorSuggestionRunAdbCommand), command))
                        }
                    },
                    showHelpButton = true,
                    helpUrl = "https://github.com/LanRhyme/MicYou/blob/master/docs/FAQ.md#usb"
                )
            }
            
            ConnectionErrorType.AdbCommandFailed -> {
                val command = extractAdbCommand(originalMessage)
                ConnectionErrorDetails(
                    type = type,
                    originalMessage = originalMessage,
                    localizedTitle = getString(Res.string.errorAdbCommandFailedTitle),
                    localizedMessage = getString(Res.string.errorAdbCommandFailedMessage),
                    recoverySuggestions = buildList {
                        add(getString(Res.string.errorSuggestionCheckAdbInstalled))
                        if (command != null) {
                            add(String.format(getString(Res.string.errorSuggestionRunAdbManually), command))
                        }
                    },
                    showHelpButton = true,
                    helpUrl = "https://github.com/LanRhyme/MicYou/blob/master/docs/FAQ.md#usb"
                )
            }
            
            ConnectionErrorType.HandshakeFailed -> ConnectionErrorDetails(
                type = type,
                originalMessage = originalMessage,
                localizedTitle = getString(Res.string.errorHandshakeFailedTitle),
                localizedMessage = getString(Res.string.errorHandshakeFailedMessage),
                recoverySuggestions = listOf(
                    getString(Res.string.errorSuggestionVersionMatch),
                    getString(Res.string.errorSuggestionRestartApp)
                )
            )
            
            ConnectionErrorType.ProtocolError -> ConnectionErrorDetails(
                type = type,
                originalMessage = originalMessage,
                localizedTitle = getString(Res.string.errorProtocolErrorTitle),
                localizedMessage = getString(Res.string.errorProtocolErrorMessage),
                recoverySuggestions = listOf(
                    getString(Res.string.errorSuggestionRestartApp),
                    getString(Res.string.errorSuggestionCheckVersion)
                )
            )
            
            ConnectionErrorType.AudioDeviceError -> ConnectionErrorDetails(
                type = type,
                originalMessage = originalMessage,
                localizedTitle = getString(Res.string.errorAudioDeviceTitle),
                localizedMessage = getString(Res.string.errorAudioDeviceMessage),
                recoverySuggestions = listOf(
                    getString(Res.string.errorSuggestionCheckAudioDevice),
                    getString(Res.string.errorSuggestionRestartApp)
                )
            )
            
            ConnectionErrorType.AudioFormatError -> ConnectionErrorDetails(
                type = type,
                originalMessage = originalMessage,
                localizedTitle = getString(Res.string.errorAudioFormatTitle),
                localizedMessage = getString(Res.string.errorAudioFormatMessage),
                recoverySuggestions = listOf(
                    getString(Res.string.errorSuggestionChangeAudioConfig),
                    getString(Res.string.errorSuggestionUseDefaultConfig)
                )
            )
            
            ConnectionErrorType.VersionMismatch -> ConnectionErrorDetails(
                type = type,
                originalMessage = originalMessage,
                localizedTitle = getString(Res.string.errorVersionMismatchTitle),
                localizedMessage = getString(Res.string.errorVersionMismatchMessage),
                recoverySuggestions = listOf(
                    getString(Res.string.errorSuggestionUpdateApp),
                    getString(Res.string.errorSuggestionCheckVersion)
                ),
                showHelpButton = true,
                helpUrl = "https://github.com/LanRhyme/MicYou/releases"
            )
            
            ConnectionErrorType.AdminPrivilegeRequired -> ConnectionErrorDetails(
                type = type,
                originalMessage = originalMessage,
                localizedTitle = getString(Res.string.errorAdminPrivilegeTitle),
                localizedMessage = getString(Res.string.errorAdminPrivilegeMessage),
                recoverySuggestions = listOf(
                    getString(Res.string.errorSuggestionRunAsAdmin)
                )
            )
            
            ConnectionErrorType.UnknownError -> ConnectionErrorDetails(
                type = type,
                originalMessage = originalMessage,
                localizedTitle = getString(Res.string.errorUnknownTitle),
                localizedMessage = String.format(getString(Res.string.errorUnknownMessage), originalMessage),
                recoverySuggestions = listOf(
                    getString(Res.string.errorSuggestionRestartApp),
                    getString(Res.string.errorSuggestionCheckLogs)
                ),
                showHelpButton = true,
                helpUrl = "https://github.com/LanRhyme/MicYou/issues"
            )
            
            ConnectionErrorType.UdpPortBlocked -> ConnectionErrorDetails(
                type = type,
                originalMessage = originalMessage,
                localizedTitle = getString(Res.string.errorFirewallBlockedTitle),
                localizedMessage = "UDP 音频端口被防火墙阻止。请确保 UDP 端口 ${port?.let { calculateUdpPort(it) } ?: "6001"} 已放行。",
                recoverySuggestions = listOf(
                    getString(Res.string.errorSuggestionAddFirewallRule),
                    getString(Res.string.errorSuggestionRunAsAdmin)
                ),
                showHelpButton = true,
                helpUrl = "https://github.com/LanRhyme/MicYou/blob/master/docs/FAQ.md#firewall"
            )
        }
    }
}
