package com.lanrhyme.micyou.network

import com.lanrhyme.micyou.Logger
import io.ktor.network.tls.certificates.buildKeyStore
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface
import java.security.KeyStore

object SelfSignedCertificate {
    private const val KEYSTORE_PASSWORD = "micyou"
    private const val CERT_ALIAS = "micyou"
    private const val CERT_FILENAME = "micyou_web.jks"
    private const val IPS_FILENAME = "micyou_web.jks.ips"

    private var cachedKeyStore: KeyStore? = null

    private val virtualKeywords = listOf(
        "vmware", "virtualbox", "hyper-v", "vethernet", "wsl", "docker", "tunnel", "teredo", "isatap", "vpn"
    )

    internal fun getLanIpAddresses(): List<String> {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            val candidates = mutableListOf<Pair<NetworkInterface, Inet4Address>>()
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                if (iface.isLoopback || !iface.isUp || iface.isVirtual) continue
                val name = iface.name.lowercase()
                val displayName = iface.displayName?.lowercase() ?: ""
                if (virtualKeywords.any { name.contains(it) || displayName.contains(it) }) continue
                val addresses = iface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val addr = addresses.nextElement()
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        candidates.add(iface to addr)
                    }
                }
            }
            return candidates
                .sortedByDescending { (_, addr) ->
                    val ip = addr.hostAddress
                    when {
                        ip.startsWith("192.168.") -> 100
                        ip.startsWith("172.") && ip.split(".").getOrNull(1)?.toIntOrNull() in 16..31 -> 80
                        ip.startsWith("10.") -> 50
                        ip.startsWith("198.18.") -> -10
                        ip.startsWith("169.254.") -> -20
                        else -> 0
                    }
                }
                .map { it.second.hostAddress }
        } catch (e: Exception) {
            Logger.w("SelfSignedCertificate", "Failed to get LAN IP addresses: ${e.message}")
        }
        return emptyList()
    }

    fun generate(): KeyStore {
        cachedKeyStore?.let { return it }

        val tmpDir = System.getProperty("java.io.tmpdir")
        val certFile = File(tmpDir, CERT_FILENAME)
        val ipsFile = File(tmpDir, IPS_FILENAME)

        val currentLanIps = getLanIpAddresses()
        val domainList = mutableListOf("localhost", "127.0.0.1")
        domainList.addAll(currentLanIps)

        if (certFile.exists()) {
            val savedIps = if (ipsFile.exists()) {
                ipsFile.readText().lines().filter { it.isNotBlank() }.toSet()
            } else {
                emptySet()
            }
            val currentIpSet = currentLanIps.toSet()
            if (savedIps == currentIpSet) {
                try {
                    val ks = KeyStore.getInstance("JKS")
                    ks.load(certFile.inputStream(), KEYSTORE_PASSWORD.toCharArray())
                    cachedKeyStore = ks
                    Logger.i("SelfSignedCertificate", "Loaded cached SSL certificate from ${certFile.absolutePath}")
                    return ks
                } catch (e: Exception) {
                    Logger.w("SelfSignedCertificate", "Failed to load cached cert, regenerating: ${e.message}")
                }
            } else {
                Logger.i("SelfSignedCertificate", "LAN IPs changed (saved=$savedIps, current=$currentIpSet), regenerating certificate")
            }
        }

        try {
            val keystore = buildKeyStore {
                certificate(CERT_ALIAS) {
                    password = KEYSTORE_PASSWORD
                    domains = domainList
                }
            }

            certFile.parentFile?.mkdirs()
            keystore.store(certFile.outputStream(), KEYSTORE_PASSWORD.toCharArray())
            ipsFile.writeText(currentLanIps.joinToString("\n"))

            cachedKeyStore = keystore
            Logger.i("SelfSignedCertificate", "Generated new SSL certificate with domains: $domainList saved to ${certFile.absolutePath}")
            return keystore
        } catch (e: Exception) {
            Logger.e("SelfSignedCertificate", "Failed to generate self-signed certificate", e)
            throw e
        }
    }

    fun getKeyStorePassword(): String = KEYSTORE_PASSWORD
    fun getCertAlias(): String = CERT_ALIAS
}
