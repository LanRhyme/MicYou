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

actual fun getAppVersion(): String = BuildConfig.VERSION_NAME

actual fun openUrl(url: String) {
    ContextHelper.getContext()?.let { context ->
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }
}

actual fun copyToClipboard(text: String) {
    ContextHelper.getContext()?.let { context ->
        val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val clip = android.content.ClipData.newPlainText("MicYou", text)
        clipboard.setPrimaryClip(clip)
        Logger.d("Platform", "Copied to clipboard: ${text.take(50)}...")
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
    return null
}

actual fun getAudioSourceOptions(): List<AudioSourceOption> {
    return AndroidAudioSource.entries.map { AudioSourceOption(it.name, it.label) }
}

actual fun isVirtualDeviceInstalled(): Boolean = false

actual suspend fun installVBCable() {
    // No-op on Android
}

actual fun getVBCableInstallProgress(): kotlinx.coroutines.flow.Flow<String?> = kotlinx.coroutines.flow.flowOf(null)

actual fun isWindowsPlatform(): Boolean = false

