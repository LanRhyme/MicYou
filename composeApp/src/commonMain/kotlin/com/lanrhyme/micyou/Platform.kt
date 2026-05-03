package com.lanrhyme.micyou

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable
import com.lanrhyme.micyou.theme.PaletteStyle

enum class PlatformType {
    Android, Desktop
}

interface Platform {
    val name: String
    val type: PlatformType
    val ipAddress: String
    val ipAddresses: List<String>
}

expect fun getPlatform(): Platform

expect fun getAppVersion(): String
expect fun openUrl(url: String)

expect fun copyToClipboard(text: String)

expect suspend fun isPortAllowed(port: Int, protocol: String): Boolean
expect suspend fun addFirewallRule(port: Int, protocol: String): Result<Unit>

/**
 * 日志级别
 */
enum class LogLevel {
    DEBUG, INFO, WARN, ERROR
}

/**
 * 跨平台日志记录器
 */
object Logger {
    private var loggerImpl: LoggerImpl? = null

    fun init(impl: LoggerImpl) {
        loggerImpl = impl
    }

    fun d(tag: String, message: String) = log(LogLevel.DEBUG, tag, message)
    fun i(tag: String, message: String) = log(LogLevel.INFO, tag, message)
    fun w(tag: String, message: String) = log(LogLevel.WARN, tag, message)
    fun e(tag: String, message: String, throwable: Throwable? = null) = log(LogLevel.ERROR, tag, message, throwable)

    private fun log(level: LogLevel, tag: String, message: String, throwable: Throwable? = null) {
        loggerImpl?.log(level, tag, message, throwable)
        if (level == LogLevel.ERROR) {
            println("[$level][$tag] $message")
            throwable?.printStackTrace()
        }
    }

    /**
     * 获取日志文件路径（用于分享/导出）
     */
    fun getLogFilePath(): String? = loggerImpl?.getLogFilePath()
}

interface LoggerImpl {
    fun log(level: LogLevel, tag: String, message: String, throwable: Throwable? = null)
    fun getLogFilePath(): String?
}

@Composable
expect fun getDynamicColorScheme(isDark: Boolean, paletteStyle: PaletteStyle): ColorScheme?

// 检查当前平台是否支持动态取色
expect fun isDynamicColorSupported(): Boolean

// 获取动态种子色（用于启动时初始化）
expect fun getDynamicSeedColor(): Long?

data class AudioSourceOption(val name: String, val labelRes: org.jetbrains.compose.resources.StringResource)

expect fun getAudioSourceOptions(): List<AudioSourceOption>

/**
 * Check if virtual audio device (VB-Cable on Windows) is installed.
 * Returns true if installed, false otherwise.
 */
expect fun isVirtualDeviceInstalled(): Boolean

/**
 * Install virtual audio device (VB-Cable on Windows).
 */
expect suspend fun installVBCable()

/**
 * Get installation progress flow. Emits status messages during installation.
 */
expect fun getVBCableInstallProgress(): kotlinx.coroutines.flow.Flow<String?>

/**
 * Check if running on Windows platform.
 */
expect fun isWindowsPlatform(): Boolean

/**
 * Check if running on macOS platform.
 */
expect fun isMacOSPlatform(): Boolean

