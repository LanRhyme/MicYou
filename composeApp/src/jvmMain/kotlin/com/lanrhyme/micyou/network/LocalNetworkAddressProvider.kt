package com.lanrhyme.micyou.network

import com.lanrhyme.micyou.IpAddressInfo
import com.lanrhyme.micyou.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.concurrent.atomic.AtomicBoolean

internal object LocalNetworkAddressProvider {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val refreshInProgress = AtomicBoolean(false)

    @Volatile
    private var cachedDetails: List<IpAddressInfo> = emptyList()

    private val virtualKeywords = listOf(
        "vmware", "virtualbox", "hyper-v", "vethernet", "wsl", "docker",
        "tunnel", "teredo", "isatap", "vpn"
    )

    init {
        refreshAsync()
    }

    fun getCachedIpAddressDetails(): List<IpAddressInfo> {
        return cachedDetails
    }

    fun getCachedOrRefreshNow(): List<IpAddressInfo> {
        val cached = cachedDetails
        if (cached.isNotEmpty()) {
            refreshAsync()
            return cached
        }
        return runCatching { refreshNow() }
            .getOrElse { listOf(IpAddressInfo("Unknown", "Unknown")) }
    }

    fun refreshAsync() {
        if (!refreshInProgress.compareAndSet(false, true)) return
        scope.launch {
            try {
                cachedDetails = queryIpAddressDetails()
            } catch (e: Exception) {
                Logger.w("LocalNetworkAddressProvider", "Failed to refresh IP addresses: ${e.message}")
            } finally {
                refreshInProgress.set(false)
            }
        }
    }

    fun refreshNow(): List<IpAddressInfo> {
        val details = queryIpAddressDetails()
        cachedDetails = details
        return details
    }

    fun getPreferredIpAddress(): String {
        return cachedDetails.firstOrNull()?.ip ?: "Unknown"
    }

    private fun queryIpAddressDetails(): List<IpAddressInfo> {
        val interfaces = NetworkInterface.getNetworkInterfaces()
        val candidates = mutableListOf<Pair<InetAddress, String>>()

        while (interfaces?.hasMoreElements() == true) {
            val iface = interfaces.nextElement()
            if (iface.isLoopback || !iface.isUp || iface.isVirtual) continue
            val name = iface.name.lowercase()
            val displayName = iface.displayName?.lowercase() ?: ""
            if (virtualKeywords.any { name.contains(it) || displayName.contains(it) }) continue

            val addresses = iface.inetAddresses
            while (addresses.hasMoreElements()) {
                val addr = addresses.nextElement()
                if (addr is Inet4Address && !addr.isLoopbackAddress) {
                    candidates.add(addr to (iface.displayName ?: iface.name))
                }
            }
        }

        val result = candidates
            .sortedByDescending { (addr, _) -> scoreIpAddress(addr.hostAddress) }
            .map { (addr, ifaceName) -> IpAddressInfo(addr.hostAddress, ifaceName) }

        return result.ifEmpty {
            listOf(IpAddressInfo(InetAddress.getLocalHost().hostAddress, "Default"))
        }
    }

    private fun scoreIpAddress(ip: String): Int {
        return when {
            ip.startsWith("192.168.") -> 100
            ip.startsWith("172.") && (ip.split(".")[1].toIntOrNull() in 16..31) -> 80
            ip.startsWith("10.") -> 50
            ip.startsWith("198.18.") -> -10
            ip.startsWith("169.254.") -> -20
            else -> 0
        }
    }
}
