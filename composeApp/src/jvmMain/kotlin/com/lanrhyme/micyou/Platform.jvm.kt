package com.lanrhyme.micyou

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable
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

                // Exclude virtual interfaces common in Clash/VPNs/VirtualBox if identifiable by name
                // Note: Names vary by OS, but "tun", "tap", "docker", "vbox" are common hints.
                // However, relying solely on IP filtering is often more robust.
                
                val addresses = iface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val addr = addresses.nextElement()
                    if (addr is java.net.Inet4Address) {
                        candidates.add(addr)
                    }
                }
            }

            // Priority:
            // 1. 192.168.x.x (Common Home LAN)
            // 2. 172.16.x.x - 172.31.x.x (Private)
            // 3. 10.x.x.x (Private, but often used by VPNs/Docker too)
            // 4. Exclude 198.18.x.x (Clash/Benchmarking)
            // 5. Exclude 169.254.x.x (APIPA)

            val sortedCandidates = candidates.sortedByDescending { addr ->
                val ip = addr.hostAddress
                when {
                    ip.startsWith("192.168.") -> 100
                    ip.startsWith("172.") && (ip.split(".")[1].toIntOrNull() in 16..31) -> 80
                    ip.startsWith("10.") -> 50
                    ip.startsWith("198.18.") -> -10 // Clash
                    ip.startsWith("169.254.") -> -20 // APIPA
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
        val os = System.getProperty("os.name").lowercase()
        val rt = Runtime.getRuntime()
        if (os.contains("win")) {
            rt.exec("rundll32 url.dll,FileProtocolHandler $url")
        } else if (os.contains("mac")) {
            rt.exec("open $url")
        } else if (os.contains("nix") || os.contains("nux")) {
            rt.exec("xdg-open $url")
        } else {
            java.awt.Desktop.getDesktop().browse(java.net.URI(url))
        }
    } catch (e: Exception) {
        Logger.e("Platform", "Failed to open URL: $url", e)
    }
}

actual suspend fun isPortAllowed(port: Int, protocol: String): Boolean = FirewallManager.isPortAllowed(port, protocol)
actual suspend fun addFirewallRule(port: Int, protocol: String): Result<Unit> = FirewallManager.addFirewallRule(port, protocol)
actual fun validateStreamingPrerequisites(mode: ConnectionMode): String? = null
actual fun validateVideoPrerequisites(mode: ConnectionMode): String? {
    return if (mode == ConnectionMode.Bluetooth) {
        "Video over Bluetooth is not supported yet. Please use Wi-Fi or USB."
    } else {
        null
    }
}

@Composable
actual fun getDynamicColorScheme(isDark: Boolean): ColorScheme? {
    return null
}
