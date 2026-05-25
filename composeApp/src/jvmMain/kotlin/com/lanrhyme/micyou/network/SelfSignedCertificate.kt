package com.lanrhyme.micyou.network

import com.lanrhyme.micyou.Logger
import io.ktor.network.tls.certificates.buildKeyStore
import java.io.File
import java.security.KeyStore

object SelfSignedCertificate {
    private const val KEYSTORE_PASSWORD = "micyou"
    private const val CERT_ALIAS = "micyou"
    private const val CERT_FILENAME = "micyou_web.jks"
    private const val IPS_FILENAME = "micyou_web.jks.ips"

    private var cachedKeyStore: KeyStore? = null
    private var cachedLanIps: Set<String>? = null

    internal fun getLanIpAddresses(): List<String> {
        return try {
            val details = LocalNetworkAddressProvider.getCachedIpAddressDetails()
                .ifEmpty { LocalNetworkAddressProvider.getCachedOrRefreshNow() }
            details.map { it.ip }.filter { it.isNotBlank() && it != "Unknown" }
        } catch (e: Exception) {
            Logger.w("SelfSignedCertificate", "Failed to get LAN IP addresses: ${e.message}")
            emptyList()
        }
    }

    fun generate(): KeyStore {
        val tmpDir = System.getProperty("java.io.tmpdir")
        val certFile = File(tmpDir, CERT_FILENAME)
        val ipsFile = File(tmpDir, IPS_FILENAME)

        val currentLanIps = getLanIpAddresses()
        val currentIpSet = currentLanIps.toSet()
        if (cachedLanIps == currentIpSet) {
            cachedKeyStore?.let { return it }
        }

        val domainList = mutableListOf("localhost", "127.0.0.1")
        domainList.addAll(currentLanIps)

        if (certFile.exists()) {
            val savedIps = if (ipsFile.exists()) {
                ipsFile.readText().lines().filter { it.isNotBlank() }.toSet()
            } else {
                emptySet()
            }
            if (savedIps == currentIpSet) {
                try {
                    val ks = KeyStore.getInstance("JKS")
                    ks.load(certFile.inputStream(), KEYSTORE_PASSWORD.toCharArray())
                    cachedKeyStore = ks
                    cachedLanIps = currentIpSet
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
            cachedLanIps = currentIpSet
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
