package com.lanrhyme.micyou.network

import com.lanrhyme.micyou.Logger
import io.ktor.network.tls.certificates.buildKeyStore
import java.io.File
import java.security.KeyStore

object SelfSignedCertificate {
    private const val KEYSTORE_PASSWORD = "micyou"
    private const val CERT_ALIAS = "micyou"

    private var cachedKeyStore: KeyStore? = null

    fun generate(): KeyStore {
        cachedKeyStore?.let { return it }

        val certFile = File(System.getProperty("java.io.tmpdir"), "micyou_web.jks")

        if (certFile.exists()) {
            try {
                val ks = KeyStore.getInstance("JKS")
                ks.load(certFile.inputStream(), KEYSTORE_PASSWORD.toCharArray())
                cachedKeyStore = ks
                Logger.i("SelfSignedCertificate", "Loaded cached SSL certificate from ${certFile.absolutePath}")
                return ks
            } catch (e: Exception) {
                Logger.w("SelfSignedCertificate", "Failed to load cached cert, regenerating: ${e.message}")
            }
        }

        try {
            val keystore = buildKeyStore {
                certificate(CERT_ALIAS) {
                    password = KEYSTORE_PASSWORD
                    domains = listOf("localhost", "127.0.0.1")
                }
            }

            certFile.parentFile?.mkdirs()
            keystore.store(certFile.outputStream(), KEYSTORE_PASSWORD.toCharArray())

            cachedKeyStore = keystore
            Logger.i("SelfSignedCertificate", "Generated new SSL certificate saved to ${certFile.absolutePath}")
            return keystore
        } catch (e: Exception) {
            Logger.e("SelfSignedCertificate", "Failed to generate self-signed certificate", e)
            throw e
        }
    }

    fun getKeyStorePassword(): String = KEYSTORE_PASSWORD
    fun getCertAlias(): String = CERT_ALIAS
}
