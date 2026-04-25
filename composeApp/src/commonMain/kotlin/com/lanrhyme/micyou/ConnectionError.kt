package com.lanrhyme.micyou

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
    UdpConnectionFailed,     // UDP 连接失败

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
                if (message.contains("firewall", ignoreCase = true) || message.contains("blocked", ignoreCase = true))
                    ConnectionErrorType.UdpPortBlocked
                else
                    ConnectionErrorType.UdpConnectionFailed
            
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
    fun generateErrorDetails(
        type: ConnectionErrorType,
        originalMessage: String,
        errors: ErrorStrings,
        mode: ConnectionMode,
        port: Int? = null,
        ip: String? = null
    ): ConnectionErrorDetails {
        return when (type) {
            ConnectionErrorType.NetworkTimeout -> ConnectionErrorDetails(
                type = type,
                originalMessage = originalMessage,
                localizedTitle = errors.errorNetworkTimeoutTitle,
                localizedMessage = errors.errorNetworkTimeoutMessage,
                recoverySuggestions = listOf(
                    errors.errorSuggestionCheckNetwork,
                    errors.errorSuggestionCheckTargetRunning,
                    errors.errorSuggestionTryDifferentPort
                )
            )
            
            ConnectionErrorType.PortInUse -> ConnectionErrorDetails(
                type = type,
                originalMessage = originalMessage,
                localizedTitle = errors.errorPortInUseTitle,
                localizedMessage = errors.errorPortInUseMessage.replace("%d", port?.toString() ?: "6000"),
                recoverySuggestions = listOf(
                    errors.errorSuggestionChangePort,
                    errors.errorSuggestionCheckOtherApps
                )
            )
            
            ConnectionErrorType.ConnectionRefused -> ConnectionErrorDetails(
                type = type,
                originalMessage = originalMessage,
                localizedTitle = errors.errorConnectionRefusedTitle,
                localizedMessage = if (mode == ConnectionMode.Wifi) 
                    errors.errorConnectionRefusedWifiMessage.replace("%s", ip ?: "")
                else errors.errorConnectionRefusedMessage,
                recoverySuggestions = listOf(
                    errors.errorSuggestionCheckServerRunning,
                    errors.errorSuggestionCheckServerConfig
                )
            )
            
            ConnectionErrorType.NetworkUnreachable -> ConnectionErrorDetails(
                type = type,
                originalMessage = originalMessage,
                localizedTitle = errors.errorNetworkUnreachableTitle,
                localizedMessage = errors.errorNetworkUnreachableMessage.replace("%s", ip ?: ""),
                recoverySuggestions = listOf(
                    errors.errorSuggestionCheckNetworkConnection,
                    errors.errorSuggestionVerifyIpAddress,
                    errors.errorSuggestionCheckWifiConnected
                )
            )
            
            ConnectionErrorType.FirewallBlocked -> ConnectionErrorDetails(
                type = type,
                originalMessage = originalMessage,
                localizedTitle = errors.errorFirewallBlockedTitle,
                localizedMessage = errors.errorFirewallBlockedMessage.replace("%d", port?.toString() ?: "6000"),
                recoverySuggestions = listOf(
                    errors.errorSuggestionAddFirewallRule,
                    errors.errorSuggestionRunAsAdmin
                ),
                showHelpButton = true,
                helpUrl = "https://github.com/LanRhyme/MicYou/blob/master/docs/FAQ.md#firewall"
            )
            
            ConnectionErrorType.PermissionDenied -> ConnectionErrorDetails(
                type = type,
                originalMessage = originalMessage,
                localizedTitle = errors.errorPermissionDeniedTitle,
                localizedMessage = errors.errorPermissionDeniedMessage,
                recoverySuggestions = listOf(
                    errors.errorSuggestionRunAsAdmin,
                    errors.errorSuggestionCheckAntivirus
                )
            )
            
            ConnectionErrorType.DeviceNotFound -> ConnectionErrorDetails(
                type = type,
                originalMessage = originalMessage,
                localizedTitle = errors.errorDeviceNotFoundTitle,
                localizedMessage = errors.errorDeviceNotFoundMessage,
                recoverySuggestions = listOf(
                    errors.errorSuggestionEnableBluetooth,
                    errors.errorSuggestionPairDevice
                )
            )
            
            ConnectionErrorType.BluetoothDisabled -> ConnectionErrorDetails(
                type = type,
                originalMessage = originalMessage,
                localizedTitle = errors.errorBluetoothDisabledTitle,
                localizedMessage = errors.errorBluetoothDisabledMessage,
                recoverySuggestions = listOf(
                    errors.errorSuggestionEnableBluetooth
                )
            )

            ConnectionErrorType.BluetoothServiceUnavailable -> ConnectionErrorDetails(
                type = type,
                originalMessage = originalMessage,
                localizedTitle = errors.errorBluetoothServiceUnavailableTitle,
                localizedMessage = errors.errorBluetoothServiceUnavailableMessage,
                recoverySuggestions = listOf(
                    errors.errorSuggestionStartBluetoothService,
                    errors.errorSuggestionCheckBluetoothDaemon
                )
            )

            ConnectionErrorType.BluetoothAdapterNotFound -> ConnectionErrorDetails(
                type = type,
                originalMessage = originalMessage,
                localizedTitle = errors.errorBluetoothAdapterNotFoundTitle,
                localizedMessage = errors.errorBluetoothAdapterNotFoundMessage,
                recoverySuggestions = listOf(
                    errors.errorSuggestionCheckBluetoothHardware,
                    errors.errorSuggestionCheckUsbBluetooth
                )
            )

            ConnectionErrorType.BlueZNotInstalled -> ConnectionErrorDetails(
                type = type,
                originalMessage = originalMessage,
                localizedTitle = errors.errorBlueZNotInstalledTitle,
                localizedMessage = errors.errorBlueZNotInstalledMessage,
                recoverySuggestions = listOf(
                    errors.errorSuggestionInstallBlueZ,
                    errors.errorSuggestionCheckBlueZService
                ),
                showHelpButton = true,
                helpUrl = "https://github.com/LanRhyme/MicYou/blob/master/docs/FAQ.md#bluetooth-linux"
            )

            ConnectionErrorType.RfcommBindFailed -> ConnectionErrorDetails(
                type = type,
                originalMessage = originalMessage,
                localizedTitle = errors.errorRfcommBindFailedTitle,
                localizedMessage = errors.errorRfcommBindFailedMessage,
                recoverySuggestions = listOf(
                    errors.errorSuggestionReleaseRfcomm,
                    errors.errorSuggestionRunAsAdmin
                )
            )
            
            ConnectionErrorType.UsbConnectionFailed -> {
                val command = extractAdbCommand(originalMessage)
                ConnectionErrorDetails(
                    type = type,
                    originalMessage = originalMessage,
                    localizedTitle = errors.errorUsbConnectionFailedTitle,
                    localizedMessage = errors.errorUsbConnectionFailedMessage,
                    recoverySuggestions = buildList {
                        add(errors.errorSuggestionCheckUsbCable)
                        add(errors.errorSuggestionEnableUsbDebugging)
                        if (command != null) {
                            add(errors.errorSuggestionRunAdbCommand.replace("%s", command))
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
                    localizedTitle = errors.errorAdbCommandFailedTitle,
                    localizedMessage = errors.errorAdbCommandFailedMessage,
                    recoverySuggestions = buildList {
                        add(errors.errorSuggestionCheckAdbInstalled)
                        if (command != null) {
                            add(errors.errorSuggestionRunAdbManually.replace("%s", command))
                        }
                    },
                    showHelpButton = true,
                    helpUrl = "https://github.com/LanRhyme/MicYou/blob/master/docs/FAQ.md#usb"
                )
            }
            
            ConnectionErrorType.HandshakeFailed -> ConnectionErrorDetails(
                type = type,
                originalMessage = originalMessage,
                localizedTitle = errors.errorHandshakeFailedTitle,
                localizedMessage = errors.errorHandshakeFailedMessage,
                recoverySuggestions = listOf(
                    errors.errorSuggestionVersionMatch,
                    errors.errorSuggestionRestartApp
                )
            )
            
            ConnectionErrorType.ProtocolError -> ConnectionErrorDetails(
                type = type,
                originalMessage = originalMessage,
                localizedTitle = errors.errorProtocolErrorTitle,
                localizedMessage = errors.errorProtocolErrorMessage,
                recoverySuggestions = listOf(
                    errors.errorSuggestionRestartApp,
                    errors.errorSuggestionCheckVersion
                )
            )
            
            ConnectionErrorType.AudioDeviceError -> ConnectionErrorDetails(
                type = type,
                originalMessage = originalMessage,
                localizedTitle = errors.errorAudioDeviceTitle,
                localizedMessage = errors.errorAudioDeviceMessage,
                recoverySuggestions = listOf(
                    errors.errorSuggestionCheckAudioDevice,
                    errors.errorSuggestionRestartApp
                )
            )
            
            ConnectionErrorType.AudioFormatError -> ConnectionErrorDetails(
                type = type,
                originalMessage = originalMessage,
                localizedTitle = errors.errorAudioFormatTitle,
                localizedMessage = errors.errorAudioFormatMessage,
                recoverySuggestions = listOf(
                    errors.errorSuggestionChangeAudioConfig,
                    errors.errorSuggestionUseDefaultConfig
                )
            )
            
            ConnectionErrorType.VersionMismatch -> ConnectionErrorDetails(
                type = type,
                originalMessage = originalMessage,
                localizedTitle = errors.errorVersionMismatchTitle,
                localizedMessage = errors.errorVersionMismatchMessage,
                recoverySuggestions = listOf(
                    errors.errorSuggestionUpdateApp,
                    errors.errorSuggestionCheckVersion
                ),
                showHelpButton = true,
                helpUrl = "https://github.com/LanRhyme/MicYou/releases"
            )
            
            ConnectionErrorType.AdminPrivilegeRequired -> ConnectionErrorDetails(
                type = type,
                originalMessage = originalMessage,
                localizedTitle = errors.errorAdminPrivilegeTitle,
                localizedMessage = errors.errorAdminPrivilegeMessage,
                recoverySuggestions = listOf(
                    errors.errorSuggestionRunAsAdmin
                )
            )
            
            ConnectionErrorType.UnknownError -> ConnectionErrorDetails(
                type = type,
                originalMessage = originalMessage,
                localizedTitle = errors.errorUnknownTitle,
                localizedMessage = errors.errorUnknownMessage.replace("%s", originalMessage),
                recoverySuggestions = listOf(
                    errors.errorSuggestionRestartApp,
                    errors.errorSuggestionCheckLogs
                ),
                showHelpButton = true,
                helpUrl = "https://github.com/LanRhyme/MicYou/issues"
            )
            
            ConnectionErrorType.UdpPortBlocked -> ConnectionErrorDetails(
                type = type,
                originalMessage = originalMessage,
                localizedTitle = errors.errorFirewallBlockedTitle,
                localizedMessage = "UDP 音频端口被防火墙阻止。请确保 UDP 端口 ${port?.plus(UDP_PORT_OFFSET) ?: "6001"} 已放行。",
                recoverySuggestions = listOf(
                    errors.errorSuggestionAddFirewallRule,
                    errors.errorSuggestionRunAsAdmin
                ),
                showHelpButton = true,
                helpUrl = "https://github.com/LanRhyme/MicYou/blob/master/docs/FAQ.md#firewall"
            )
            
            ConnectionErrorType.UdpConnectionFailed -> ConnectionErrorDetails(
                type = type,
                originalMessage = originalMessage,
                localizedTitle = "UDP 连接失败",
                localizedMessage = "无法建立 UDP 音频连接。请检查网络连接和防火墙设置。",
                recoverySuggestions = listOf(
                    errors.errorSuggestionCheckNetwork,
                    errors.errorSuggestionCheckTargetRunning,
                    "确保 UDP 端口 ${port?.plus(UDP_PORT_OFFSET) ?: "6001"} 未被阻止"
                )
            )
        }
    }
}