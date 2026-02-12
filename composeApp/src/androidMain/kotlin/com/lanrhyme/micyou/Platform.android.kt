package com.lanrhyme.micyou

import android.os.Build
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

@Composable
actual fun getDynamicColorScheme(isDark: Boolean): ColorScheme? {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val context = LocalContext.current
        return if (isDark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    }
    return null
}

