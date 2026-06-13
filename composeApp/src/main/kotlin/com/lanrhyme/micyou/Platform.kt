package com.lanrhyme.micyou

import android.os.Build
import android.content.Intent
import android.net.Uri
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import com.lanrhyme.micyou.theme.PaletteStyle
import com.lanrhyme.micyou.theme.dynamicColorScheme

enum class PlatformType {
    Android
}

data class IpAddressInfo(
    val ip: String,
    val interfaceName: String
)

interface Platform {
    val name: String
    val type: PlatformType
    val ipAddress: String
    val ipAddresses: List<String>
    val ipAddressDetails: List<IpAddressInfo>
}

class AndroidPlatform : Platform {
    override val name: String = "Android ${Build.VERSION.SDK_INT}"
    override val type: PlatformType = PlatformType.Android
    override val ipAddress: String = "Client"
    override val ipAddresses: List<String> = listOf("Client")
    override val ipAddressDetails: List<IpAddressInfo> = emptyList()
}

fun getPlatform(): Platform = AndroidPlatform()
suspend fun getPreferredLocalIpAddress(): String = "Client"
suspend fun refreshLocalIpAddressDetails(): List<IpAddressInfo> = emptyList()

fun getAppVersion(): String = BuildConfig.VERSION_NAME

fun openUrl(url: String) {
    ContextHelper.getContext()?.let { context ->
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }
}

fun copyToClipboard(text: String) {
    ContextHelper.getContext()?.let { context ->
        val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val clip = android.content.ClipData.newPlainText("MicYou", text)
        clipboard.setPrimaryClip(clip)
    }
}

suspend fun isPortAllowed(port: Int, protocol: String): Boolean = true
suspend fun addFirewallRule(port: Int, protocol: String): Result<Unit> = Result.success(Unit)

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
fun getDynamicColorScheme(isDark: Boolean, paletteStyle: PaletteStyle): ColorScheme? {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val context = LocalContext.current
        val nativeScheme = if (isDark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)

        return remember(nativeScheme, isDark, paletteStyle) {
            val seedColor = nativeScheme.primary
            dynamicColorScheme(seedColor, isDark, paletteStyle)
        }
    }
    return null
}

fun isDynamicColorSupported(): Boolean {
    return Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
}

fun getDynamicSeedColor(): Long? {
    return null
}

fun getString(@androidx.annotation.StringRes resId: Int, vararg formatArgs: Any): String {
    return ContextHelper.getContext()?.getString(resId, *formatArgs) ?: ""
}

fun formatBytes(bytes: Long): String {
    return when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
        bytes < 1024 * 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024))
        else -> "%.1f GB".format(bytes / (1024.0 * 1024 * 1024))
    }
}

data class AudioSourceOption(
    val name: String,
    @androidx.annotation.StringRes val labelRes: Int? = null,
    val label: String? = null
)

fun getAudioSourceOptions(): List<AudioSourceOption> {
    return AndroidAudioSource.entries.map { AudioSourceOption(it.name, it.labelRes) }
}

fun isVirtualDeviceInstalled(): Boolean = false

suspend fun installVBCable() {
    // No-op on Android
}

fun getVBCableInstallProgress(): kotlinx.coroutines.flow.Flow<String?> = kotlinx.coroutines.flow.flowOf(null)

fun isWindowsPlatform(): Boolean = false

fun isMacOSPlatform(): Boolean = false

fun getAifadianApiToken(): String = BuildConfig.AIFADIAN_API_TOKEN
fun getAifadianUserId(): String = BuildConfig.AIFADIAN_USER_ID

fun md5(input: String): String {
    val md = java.security.MessageDigest.getInstance("MD5")
    val digest = md.digest(input.toByteArray())
    return digest.joinToString("") { "%02x".format(it) }
}

fun currentTimeSeconds(): Long = System.currentTimeMillis() / 1000

@Composable
fun QrCodeImage(content: String, modifier: Modifier, sizeDp: Int) {
    // Web mode not supported on Android
}
