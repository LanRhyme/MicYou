package com.lanrhyme.micyou.network

import com.lanrhyme.micyou.Logger
import javax.jmdns.JmDNS
import javax.jmdns.ServiceInfo
import java.net.InetAddress

class MdnsAdvertiser {
    private var jmdns: JmDNS? = null
    private var serviceInfo: ServiceInfo? = null
    private var currentPort: Int = -1
    private var currentBindAddress: String = "0.0.0.0"

    fun advertise(port: Int, bindAddress: String = "0.0.0.0") {
        advertiseInternal(port, bindAddress, force = false)
    }

    private fun advertiseInternal(port: Int, bindAddress: String, force: Boolean) {
        if (!force && jmdns != null && currentPort == port && currentBindAddress == bindAddress) {
            Logger.d("MdnsAdvertiser", "mDNS already advertising on $bindAddress:$port")
            return
        }

        close(resetPort = false)

        try {
            val localHost = resolveAdvertiseAddress(bindAddress)
            val hostName = "MicYou (${InetAddress.getLocalHost().hostName})"
            jmdns = JmDNS.create(localHost, hostName)

            serviceInfo = ServiceInfo.create(
                "_micyou._tcp.local.",
                hostName,
                port,
                "MicYou audio streaming server"
            )
            jmdns?.registerService(serviceInfo)
            currentPort = port
            currentBindAddress = bindAddress
            Logger.i("MdnsAdvertiser", "mDNS service advertised: $hostName on ${localHost?.hostAddress} port $port")
        } catch (e: Exception) {
            Logger.w("MdnsAdvertiser", "Failed to advertise mDNS service: ${e.message}")
            close(resetPort = true)
            throw e
        }
    }

    /**
     * 重新广播 mDNS 服务（IP 变化时调用）
     */
    fun reAdvertise(port: Int = currentPort, bindAddress: String = currentBindAddress) {
        if (port <= 0) {
            Logger.w("MdnsAdvertiser", "Cannot re-advertise: invalid port $port")
            return
        }
        Logger.i("MdnsAdvertiser", "Re-advertising mDNS service due to IP change")
        advertiseInternal(port, bindAddress, force = true)
    }

    private fun resolveAdvertiseAddress(bindAddress: String): InetAddress? {
        return if (bindAddress == "0.0.0.0") {
            findLanAddress()
        } else {
            InetAddress.getByName(bindAddress)
        }
    }

    private fun findLanAddress(): InetAddress? {
        return try {
            val preferredIp = LocalNetworkAddressProvider.getPreferredIpAddress()
            if (preferredIp != "Unknown") InetAddress.getByName(preferredIp) else null
        } catch (e: Exception) {
            Logger.w("MdnsAdvertiser", "Failed to find LAN address: ${e.message}")
            null
        }
    }

    fun close() {
        close(resetPort = true)
    }

    private fun close(resetPort: Boolean) {
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
            if (resetPort) {
                currentPort = -1
                currentBindAddress = "0.0.0.0"
            }
        }
    }
}
