package com.lanrhyme.micyou.network

import com.lanrhyme.micyou.Logger
import javax.jmdns.JmDNS
import javax.jmdns.ServiceInfo
import java.net.InetAddress

class MdnsAdvertiser {
    private var jmdns: JmDNS? = null
    private var serviceInfo: ServiceInfo? = null

    fun advertise(port: Int) {
        try {
            val localHost = findLanAddress()
            val hostName = "MicYou (${InetAddress.getLocalHost().hostName})"
            jmdns = JmDNS.create(localHost, hostName)

            serviceInfo = ServiceInfo.create(
                "_micyou._tcp.local.",
                hostName,
                port,
                "MicYou audio streaming server"
            )
            jmdns?.registerService(serviceInfo)
            Logger.i("MdnsAdvertiser", "mDNS service advertised: $hostName on ${localHost?.hostAddress} port $port")
        } catch (e: Exception) {
            Logger.w("MdnsAdvertiser", "Failed to advertise mDNS service: ${e.message}")
            close()
            throw e
        }
    }

    private fun findLanAddress(): InetAddress? {
        val virtualKeywords = listOf("vmware", "virtualbox", "hyper-v", "vethernet", "wsl", "docker", "tunnel", "teredo", "isatap")
        try {
            val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
            val candidates = mutableListOf<Pair<java.net.NetworkInterface, java.net.Inet4Address>>()
            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()
                if (networkInterface.isLoopback || !networkInterface.isUp || networkInterface.isVirtual) continue
                val name = networkInterface.displayName?.lowercase() ?: ""
                if (virtualKeywords.any { name.contains(it) }) continue
                val addresses = networkInterface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val address = addresses.nextElement()
                    if (address is java.net.Inet4Address && !address.isLoopbackAddress) {
                        candidates.add(networkInterface to address)
                    }
                }
            }
            // Prefer interfaces with common LAN prefixes (192.168.x.x, 10.x.x.x)
            val lanPreferred = candidates.firstOrNull { it.second.hostAddress?.startsWith("192.168.") == true }
                ?: candidates.firstOrNull { it.second.hostAddress?.startsWith("10.") == true }
                ?: candidates.firstOrNull()
            return lanPreferred?.second
        } catch (e: Exception) {
            Logger.w("MdnsAdvertiser", "Failed to find LAN address: ${e.message}")
        }
        return null
    }

    fun close() {
        try {
            val j = jmdns
            val si = serviceInfo
            if (j != null && si != null) {
                j.unregisterService(si)
            }
            jmdns?.close()
        } catch (e: Exception) {
            Logger.w("MdnsAdvertiser", "Error closing mDNS: ${e.message}")
        } finally {
            serviceInfo = null
            jmdns = null
        }
    }
}
