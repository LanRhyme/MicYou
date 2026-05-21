package com.lanrhyme.micyou.network

import com.lanrhyme.micyou.IpAddressInfo
import com.lanrhyme.micyou.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Monitors local network address changes and notifies registered listeners.
 */
class NetworkAddressMonitor {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var monitorJob: Job? = null

    private val _currentIpAddresses = MutableStateFlow<List<IpAddressInfo>>(emptyList())
    val currentIpAddresses: StateFlow<List<IpAddressInfo>> = _currentIpAddresses.asStateFlow()

    private val _primaryIp = MutableStateFlow<String?>(null)
    val primaryIp: StateFlow<String?> = _primaryIp.asStateFlow()

    private val listeners = CopyOnWriteArrayList<NetworkAddressChangeListener>()

    @Volatile
    private var isRunning = false

    companion object {
        private val VIRTUAL_KEYWORDS = listOf(
            "vmware", "virtualbox", "hyper-v", "vethernet", "wsl", "docker",
            "tunnel", "teredo", "isatap", "vpn"
        )
        private const val MONITOR_INTERVAL_MS = 10_000L
    }

    fun start() {
        if (isRunning) return
        isRunning = true

        val initialAddresses = getCurrentIpAddresses()
        _currentIpAddresses.value = initialAddresses
        _primaryIp.value = initialAddresses.firstOrNull()?.ip

        Logger.i("NetworkAddressMonitor", "Started monitoring. Primary IP: ${_primaryIp.value}")

        monitorJob = scope.launch {
            while (isActive) {
                delay(MONITOR_INTERVAL_MS)
                checkForChanges()
            }
        }
    }

    fun stop() {
        isRunning = false
        monitorJob?.cancel()
        monitorJob = null
        Logger.i("NetworkAddressMonitor", "Stopped monitoring")
    }

    fun addListener(listener: NetworkAddressChangeListener) {
        listeners.add(listener)
    }

    fun removeListener(listener: NetworkAddressChangeListener) {
        listeners.remove(listener)
    }

    private fun checkForChanges() {
        val newAddresses = getCurrentIpAddresses()
        val currentAddresses = _currentIpAddresses.value

        if (!areAddressesEqual(currentAddresses, newAddresses)) {
            val oldPrimary = _primaryIp.value
            val newPrimary = newAddresses.firstOrNull()?.ip

            _currentIpAddresses.value = newAddresses
            _primaryIp.value = newPrimary

            Logger.i("NetworkAddressMonitor", "IP addresses changed. Old primary: $oldPrimary, New primary: $newPrimary")
            Logger.d("NetworkAddressMonitor", "New addresses: ${newAddresses.map { "${it.ip}(${it.interfaceName})" }}")

            val changeEvent = NetworkAddressChangeEvent(
                oldAddresses = currentAddresses,
                newAddresses = newAddresses,
                oldPrimaryIp = oldPrimary,
                newPrimaryIp = newPrimary
            )

            listeners.forEach { listener ->
                try {
                    listener.onAddressChanged(changeEvent)
                } catch (e: Exception) {
                    Logger.e("NetworkAddressMonitor", "Listener notification failed", e)
                }
            }
        }
    }

    private fun getCurrentIpAddresses(): List<IpAddressInfo> {
        return try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            val candidates = mutableListOf<Pair<InetAddress, String>>()

            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                if (iface.isLoopback || !iface.isUp || iface.isVirtual) continue
                val name = iface.name.lowercase()
                val displayName = iface.displayName?.lowercase() ?: ""
                if (VIRTUAL_KEYWORDS.any { name.contains(it) || displayName.contains(it) }) continue

                val addresses = iface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val addr = addresses.nextElement()
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        candidates.add(addr to (iface.displayName ?: iface.name))
                    }
                }
            }

            val sortedCandidates = candidates.sortedByDescending { (addr, _) ->
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

            val result = sortedCandidates.map { (addr, ifaceName) ->
                IpAddressInfo(addr.hostAddress, ifaceName)
            }

            if (result.isNotEmpty()) return result

            listOf(IpAddressInfo(InetAddress.getLocalHost().hostAddress, "Default"))
        } catch (e: Exception) {
            Logger.w("NetworkAddressMonitor", "Failed to get IP addresses: ${e.message}")
            emptyList()
        }
    }

    private fun areAddressesEqual(old: List<IpAddressInfo>, new: List<IpAddressInfo>): Boolean {
        return old.map { it.ip to it.interfaceName } == new.map { it.ip to it.interfaceName }
    }
}

/**
 * Network address change event.
 */
data class NetworkAddressChangeEvent(
    val oldAddresses: List<IpAddressInfo>,
    val newAddresses: List<IpAddressInfo>,
    val oldPrimaryIp: String?,
    val newPrimaryIp: String?
)

/**
 * Listener for network address changes.
 */
interface NetworkAddressChangeListener {
    fun onAddressChanged(event: NetworkAddressChangeEvent)
}
