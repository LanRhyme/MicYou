package com.lanrhyme.micyou.network

import com.lanrhyme.micyou.R
import com.lanrhyme.micyou.util.Constants
import com.lanrhyme.micyou.util.getString
import com.lanrhyme.micyou.viewmodel.ConnectionMode

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
    PermissionDenied,        // 权限不足

    // 设备相关错误
    DeviceNotFound,          // 设备未找到
    UsbConnectionFailed,     // USB 连接失败
    AdbCommandFailed,        // ADB 命令执行失败

    // 协议相关错误
    HandshakeFailed,         // 握手失败（协议不匹配）
    ProtocolError,           // 协议错误
    VersionMismatch,         // 版本不匹配

    // UDP 相关错误
    UdpPortBlocked,          // UDP 端口被阻止

    // 音频相关错误
    AudioDeviceError,        // 音频设备错误
    AudioFormatError,        // 音频格式不支持

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
            
            // 权限不足
            message.contains("permission", ignoreCase = true) ||
            message.contains("access denied", ignoreCase = true) ||
            message.contains("privilege", ignoreCase = true) ->
                ConnectionErrorType.PermissionDenied
            
            // ADB 相关
            message.contains("adb", ignoreCase = true) ->
                ConnectionErrorType.AdbCommandFailed
            
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
                localizedTitle = getString(R.string.errorNetworkTimeoutTitle),
                localizedMessage = getString(R.string.errorNetworkTimeoutMessage),
                recoverySuggestions = listOf(
                    getString(R.string.errorSuggestionCheckNetwork),
                    getString(R.string.errorSuggestionCheckTargetRunning),
                    getString(R.string.errorSuggestionTryDifferentPort)
                )
            )
            
            ConnectionErrorType.PortInUse -> ConnectionErrorDetails(
                type = type,
                originalMessage = originalMessage,
                localizedTitle = getString(R.string.errorPortInUseTitle),
                localizedMessage = String.format(getString(R.string.errorPortInUseMessage), port?.toString() ?: Constants.DEFAULT_TCP_PORT.toString()),
                recoverySuggestions = listOf(
                    getString(R.string.errorSuggestionChangePort),
                    getString(R.string.errorSuggestionCheckOtherApps)
                )
            )
            
            ConnectionErrorType.ConnectionRefused -> ConnectionErrorDetails(
                type = type,
                originalMessage = originalMessage,
                localizedTitle = getString(R.string.errorConnectionRefusedTitle),
                localizedMessage = if (mode == ConnectionMode.Wifi) 
                    String.format(getString(R.string.errorConnectionRefusedWifiMessage), ip ?: "")
                else getString(R.string.errorConnectionRefusedMessage),
                recoverySuggestions = listOf(
                    getString(R.string.errorSuggestionCheckServerRunning),
                    getString(R.string.errorSuggestionCheckServerConfig)
                )
            )
            
            ConnectionErrorType.NetworkUnreachable -> ConnectionErrorDetails(
                type = type,
                originalMessage = originalMessage,
                localizedTitle = getString(R.string.errorNetworkUnreachableTitle),
                localizedMessage = String.format(getString(R.string.errorNetworkUnreachableMessage), ip ?: ""),
                recoverySuggestions = listOf(
                    getString(R.string.errorSuggestionCheckNetworkConnection),
                    getString(R.string.errorSuggestionVerifyIpAddress),
                    getString(R.string.errorSuggestionCheckWifiConnected)
                )
            )
            
            ConnectionErrorType.PermissionDenied -> ConnectionErrorDetails(
                type = type,
                originalMessage = originalMessage,
                localizedTitle = getString(R.string.errorPermissionDeniedTitle),
                localizedMessage = getString(R.string.errorPermissionDeniedMessage),
                recoverySuggestions = listOf(
                    getString(R.string.errorSuggestionCheckSettings)
                )
            )
            
            ConnectionErrorType.DeviceNotFound -> ConnectionErrorDetails(
                type = type,
                originalMessage = originalMessage,
                localizedTitle = getString(R.string.errorDeviceNotFoundTitle),
                localizedMessage = getString(R.string.errorDeviceNotFoundMessage),
                recoverySuggestions = listOf(
                    getString(R.string.errorSuggestionCheckNetworkConnection)
                )
            )

            ConnectionErrorType.UsbConnectionFailed -> {
                val command = extractAdbCommand(originalMessage)
                ConnectionErrorDetails(
                    type = type,
                    originalMessage = originalMessage,
                    localizedTitle = getString(R.string.errorUsbConnectionFailedTitle),
                    localizedMessage = getString(R.string.errorUsbConnectionFailedMessage),
                    recoverySuggestions = buildList {
                        add(getString(R.string.errorSuggestionCheckUsbCable))
                        add(getString(R.string.errorSuggestionEnableUsbDebugging))
                        if (command != null) {
                            add(String.format(getString(R.string.errorSuggestionRunAdbCommand), command))
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
                    localizedTitle = getString(R.string.errorAdbCommandFailedTitle),
                    localizedMessage = getString(R.string.errorAdbCommandFailedMessage),
                    recoverySuggestions = buildList {
                        add(getString(R.string.errorSuggestionCheckAdbInstalled))
                        if (command != null) {
                            add(String.format(getString(R.string.errorSuggestionRunAdbManually), command))
                        }
                    },
                    showHelpButton = true,
                    helpUrl = "https://github.com/LanRhyme/MicYou/blob/master/docs/FAQ.md#usb"
                )
            }
            
            ConnectionErrorType.HandshakeFailed -> ConnectionErrorDetails(
                type = type,
                originalMessage = originalMessage,
                localizedTitle = getString(R.string.errorHandshakeFailedTitle),
                localizedMessage = getString(R.string.errorHandshakeFailedMessage),
                recoverySuggestions = listOf(
                    getString(R.string.errorSuggestionVersionMatch),
                    getString(R.string.errorSuggestionRestartApp)
                )
            )
            
            ConnectionErrorType.ProtocolError -> ConnectionErrorDetails(
                type = type,
                originalMessage = originalMessage,
                localizedTitle = getString(R.string.errorProtocolErrorTitle),
                localizedMessage = getString(R.string.errorProtocolErrorMessage),
                recoverySuggestions = listOf(
                    getString(R.string.errorSuggestionRestartApp),
                    getString(R.string.errorSuggestionCheckVersion)
                )
            )
            
            ConnectionErrorType.AudioDeviceError -> ConnectionErrorDetails(
                type = type,
                originalMessage = originalMessage,
                localizedTitle = getString(R.string.errorAudioDeviceTitle),
                localizedMessage = getString(R.string.errorAudioDeviceMessage),
                recoverySuggestions = listOf(
                    getString(R.string.errorSuggestionCheckAudioDevice),
                    getString(R.string.errorSuggestionRestartApp)
                )
            )
            
            ConnectionErrorType.AudioFormatError -> ConnectionErrorDetails(
                type = type,
                originalMessage = originalMessage,
                localizedTitle = getString(R.string.errorAudioFormatTitle),
                localizedMessage = getString(R.string.errorAudioFormatMessage),
                recoverySuggestions = listOf(
                    getString(R.string.errorSuggestionChangeAudioConfig),
                    getString(R.string.errorSuggestionUseDefaultConfig)
                )
            )
            
            ConnectionErrorType.VersionMismatch -> ConnectionErrorDetails(
                type = type,
                originalMessage = originalMessage,
                localizedTitle = getString(R.string.errorVersionMismatchTitle),
                localizedMessage = getString(R.string.errorVersionMismatchMessage),
                recoverySuggestions = listOf(
                    getString(R.string.errorSuggestionUpdateApp),
                    getString(R.string.errorSuggestionCheckVersion)
                ),
                showHelpButton = true,
                helpUrl = "https://github.com/LanRhyme/MicYou/releases"
            )
            
            ConnectionErrorType.UnknownError -> ConnectionErrorDetails(
                type = type,
                originalMessage = originalMessage,
                localizedTitle = getString(R.string.errorUnknownTitle),
                localizedMessage = String.format(getString(R.string.errorUnknownMessage), originalMessage),
                recoverySuggestions = listOf(
                    getString(R.string.errorSuggestionRestartApp),
                    getString(R.string.errorSuggestionCheckLogs)
                ),
                showHelpButton = true,
                helpUrl = "https://github.com/LanRhyme/MicYou/issues"
            )
            
            ConnectionErrorType.UdpPortBlocked -> ConnectionErrorDetails(
                type = type,
                originalMessage = originalMessage,
                localizedTitle = getString(R.string.errorUdpPortBlockedTitle),
                localizedMessage = getString(R.string.errorUdpPortBlockedMessage, port?.let { calculateUdpPort(it) } ?: Constants.DEFAULT_UDP_PORT),
                recoverySuggestions = listOf(
                    getString(R.string.errorSuggestionCheckNetwork),
                    getString(R.string.errorSuggestionRestartApp)
                ),
                showHelpButton = true,
                helpUrl = "https://github.com/LanRhyme/MicYou/blob/master/docs/FAQ.md"
            )
        }
    }
}