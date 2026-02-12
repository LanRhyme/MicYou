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

@Composable
expect fun getDynamicColorScheme(isDark: Boolean): ColorScheme?

