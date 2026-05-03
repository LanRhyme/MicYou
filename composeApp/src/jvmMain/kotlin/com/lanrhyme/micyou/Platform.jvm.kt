package com.lanrhyme.micyou

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import com.lanrhyme.micyou.platform.FirewallManager
import com.lanrhyme.micyou.platform.PlatformInfo
import com.lanrhyme.micyou.platform.WindowsAccentColorExtractor
import com.lanrhyme.micyou.theme.PaletteStyle
import com.lanrhyme.micyou.theme.dynamicColorScheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetAddress

class JVMPlatform: Platform {
    override val name: String = "Java ${System.getProperty("java.version")}"
    override val type: PlatformType = PlatformType.Desktop
    override val ipAddress: String
        get() = ipAddresses.firstOrNull() ?: "Unknown"

    override val ipAddresses: List<String>
        get() = getLocalIpAddresses()

    private fun getLocalIpAddresses(): List<String> {
        try {
            val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
    val candidates = mutableListOf<java.net.InetAddress>()

            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                if (iface.isLoopback || !iface.isUp) continue

                val addresses = iface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val addr = addresses.nextElement()
                    if (addr is java.net.Inet4Address) {
                        candidates.add(addr)
                    }
                }
            }
    val sortedCandidates = candidates.sortedByDescending { addr ->
                val ip = addr.hostAddress
                when {
                    ip.startsWith("192.168.") -> 100
                    ip.startsWith("172.") && (ip.split(".")[1].toIntOrNull() in 16..31) -> 80
                    ip.startsWith("10.") -> 50
                    ip.startsWith("198.18.") -> -10
                    ip.startsWith("169.254.") -> -20
                    else -> 0
                }
            }
    val result = sortedCandidates.map { it.hostAddress }
            if (result.isNotEmpty()) return result
            
            return listOf(java.net.InetAddress.getLocalHost().hostAddress)
        } catch (e: Exception) {
            return listOf("Unknown")
        }
    }
}

actual fun getPlatform(): Platform = JVMPlatform()

actual fun getAppVersion(): String {
    val fromManifest = object {}.javaClass.`package`?.implementationVersion
    if (!fromManifest.isNullOrBlank()) return fromManifest
    val fromProperty = System.getProperty("app.version")
    if (!fromProperty.isNullOrBlank()) return fromProperty
    return "dev"
}

actual fun openUrl(url: String) {
    try {
        when {
            PlatformInfo.isWindows -> Runtime.getRuntime().exec("rundll32 url.dll,FileProtocolHandler $url")
            PlatformInfo.isMacOS -> Runtime.getRuntime().exec("/usr/bin/open $url")
            PlatformInfo.isLinux -> Runtime.getRuntime().exec("xdg-open $url")
            else -> java.awt.Desktop.getDesktop().browse(java.net.URI(url))
        }
    } catch (e: Exception) {
        Logger.e("Platform", "Failed to open URL: $url", e)
    }
}

actual fun copyToClipboard(text: String) {
    try {
        val clipboard = java.awt.Toolkit.getDefaultToolkit().systemClipboard
        val stringSelection = java.awt.datatransfer.StringSelection(text)
        clipboard.setContents(stringSelection, null)
        Logger.d("Platform", "Copied to clipboard: ${text.take(50)}...")
    } catch (e: Exception) {
        Logger.e("Platform", "Failed to copy to clipboard", e)
    }
}

actual suspend fun isPortAllowed(port: Int, protocol: String): Boolean =
    withContext(Dispatchers.IO) {
        val fwProtocol = runCatching { FirewallManager.Protocol.valueOf(protocol.uppercase()) }.getOrDefault(FirewallManager.Protocol.TCP)
        FirewallManager.isPortAllowed(port, fwProtocol)
    }

actual suspend fun addFirewallRule(port: Int, protocol: String): Result<Unit> =
    withContext(Dispatchers.IO) {
        val fwProtocol = runCatching { FirewallManager.Protocol.valueOf(protocol.uppercase()) }.getOrDefault(FirewallManager.Protocol.TCP)
        if (FirewallManager.addFirewallRule(port, fwProtocol)) {
            Result.success(Unit)
        } else {
            Result.failure(Exception("Failed to add firewall rule"))
        }
    }

actual fun isDynamicColorSupported(): Boolean {
    // 目前只为 Windows 提供莫奈取色
    return PlatformInfo.isWindows
}

actual fun getDynamicSeedColor(): Long? {
    // 目前只为 Windows 提供动态种子色
    if (!PlatformInfo.isWindows) {
        return null
    }
    return WindowsAccentColorExtractor.getAccentColor()?.value?.toLong()
}

@Composable
actual fun getDynamicColorScheme(isDark: Boolean, paletteStyle: PaletteStyle): ColorScheme? {
    // 目前只为 Windows 提供莫奈取色
    if (!PlatformInfo.isWindows) {
        return null
    }

    // 使用 remember 缓存生成的配色方案，避免每次重组时重复获取系统颜色
    return remember(isDark, paletteStyle) {
        // 获取 Windows 系统主题色
        val seedColor = WindowsAccentColorExtractor.getAccentColor()

        // 如果无法获取系统主题色，返回 null 使用默认主题
        val color = seedColor ?: return@remember null

        Logger.d("Platform", "Using Windows accent color: $color")

        // 使用用户选择的 paletteStyle 生成配色方案
        dynamicColorScheme(color, isDark, paletteStyle)
    }
}

actual fun getAudioSourceOptions(): List<AudioSourceOption> = emptyList()

actual fun isVirtualDeviceInstalled(): Boolean = VirtualAudioDeviceManager.isVirtualDeviceInstalled()

actual suspend fun installVBCable() = VirtualAudioDeviceManager.installVirtualDevice()

actual fun getVBCableInstallProgress(): kotlinx.coroutines.flow.Flow<String?> = VirtualAudioDeviceManager.installProgress

actual fun isWindowsPlatform(): Boolean = PlatformInfo.isWindows

actual fun isMacOSPlatform(): Boolean = PlatformInfo.isMacOS
