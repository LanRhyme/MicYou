package com.lanrhyme.micyou

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable
import com.lanrhyme.micyou.platform.FirewallManager
import com.lanrhyme.micyou.platform.PlatformInfo
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

actual fun uninstallVBCable() {
    VBCableManager.uninstallVBCable()
}

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

actual suspend fun isPortAllowed(port: Int, protocol: String): Boolean = FirewallManager.isPortAllowed(port)
actual suspend fun addFirewallRule(port: Int, protocol: String): Result<Unit> = 
    if (FirewallManager.addFirewallRule(port)) Result.success(Unit) else Result.failure(Exception("Failed to add firewall rule"))

@Composable
actual fun getDynamicColorScheme(isDark: Boolean): ColorScheme? {
    return null
}
