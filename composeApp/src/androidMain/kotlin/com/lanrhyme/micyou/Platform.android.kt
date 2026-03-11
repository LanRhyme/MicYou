package com.lanrhyme.micyou

import android.os.Build
import android.content.Intent
import android.net.Uri
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

class AndroidPlatform : Platform {
    override val name: String = "Android ${Build.VERSION.SDK_INT}"
    override val type: PlatformType = PlatformType.Android
    override val ipAddress: String = "Client"
    override val ipAddresses: List<String> = listOf("Client")
}

actual fun getPlatform(): Platform = AndroidPlatform()

actual fun uninstallVBCable() {
    // No-op on Android
}

actual fun getAppVersion(): String = BuildConfig.VERSION_NAME

actual fun openUrl(url: String) {
    ContextHelper.getContext()?.let { context ->
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }
}

actual suspend fun isPortAllowed(port: Int, protocol: String): Boolean = true
actual suspend fun addFirewallRule(port: Int, protocol: String): Result<Unit> = Result.success(Unit)

@Composable
actual fun getDynamicColorScheme(isDark: Boolean): ColorScheme? {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val context = LocalContext.current
        return if (isDark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    }
    return null
}

actual fun isDynamicColorSupported(): Boolean {
    return Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
}

actual fun getDynamicSeedColor(): Long? {
    // Android 使用系统动态配色方案，不需要单独获取种子色
    return null
}

