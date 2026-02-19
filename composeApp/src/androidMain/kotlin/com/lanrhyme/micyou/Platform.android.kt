package com.lanrhyme.micyou

import android.os.Build
import android.content.Intent
import android.net.Uri
import android.Manifest
import android.content.pm.PackageManager
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.ActivityCompat

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

actual fun validateStreamingPrerequisites(mode: ConnectionMode): String? {
    val context = ContextHelper.getContext() ?: return "Context unavailable"
    if (ActivityCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
        return "Microphone permission is required. Please grant RECORD_AUDIO and retry."
    }
    if (mode == ConnectionMode.Bluetooth && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            return "Bluetooth permission is required for Bluetooth mode. Please grant BLUETOOTH_CONNECT and retry."
        }
    }
    return null
}

actual fun validateVideoPrerequisites(mode: ConnectionMode): String? {
    val context = ContextHelper.getContext() ?: return "Context unavailable"
    if (ActivityCompat.checkSelfPermission(context, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
        return "Camera permission is required. Please grant CAMERA and retry."
    }
    if (mode == ConnectionMode.Bluetooth) {
        return "Video over Bluetooth is not supported yet. Please use Wi-Fi or USB."
    }
    return null
}

@Composable
actual fun getDynamicColorScheme(isDark: Boolean): ColorScheme? {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val context = LocalContext.current
        return if (isDark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    }
    return null
}
