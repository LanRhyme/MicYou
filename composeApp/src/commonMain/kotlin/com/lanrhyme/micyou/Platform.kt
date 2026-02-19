package com.lanrhyme.micyou

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable

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

expect fun uninstallVBCable()

expect fun getAppVersion(): String
expect fun openUrl(url: String)

expect suspend fun isPortAllowed(port: Int, protocol: String): Boolean
expect suspend fun addFirewallRule(port: Int, protocol: String): Result<Unit>
expect fun validateStreamingPrerequisites(mode: ConnectionMode): String?
expect fun validateVideoPrerequisites(mode: ConnectionMode): String?

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
expect fun getDynamicColorScheme(isDark: Boolean): ColorScheme?
