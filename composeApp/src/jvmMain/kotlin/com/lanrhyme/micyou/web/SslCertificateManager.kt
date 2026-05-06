package com.lanrhyme.micyou.web

import com.lanrhyme.micyou.Logger
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.X509CertificateHolder
import org.bouncycastle.cert.X509v3CertificateBuilder
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.SecureRandom
import java.security.Security
import java.util.Date

object SslCertificateManager {
    private const val TAG = "SslCertificateManager"

    init {
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(BouncyCastleProvider())
        }
    }

    fun generateSelfSignedKeyStore(
        password: CharArray,
        commonName: String = "MicYou Web Server"
    ): KeyStore {
        val keyPairGenerator = KeyPairGenerator.getInstance("RSA")
        keyPairGenerator.initialize(2048, SecureRandom())
        val keyPair = keyPairGenerator.generateKeyPair()

        val notBefore = Date()
        val notAfter = Date(notBefore.time + 365L * 24 * 60 * 60 * 1000)

        val issuer = X500Name("CN=$commonName, O=MicYou, C=CN")
        val subject = issuer
        val serial = BigInteger.valueOf(System.currentTimeMillis())

        val certBuilder: X509v3CertificateBuilder = JcaX509v3CertificateBuilder(
            issuer, serial, notBefore, notAfter, subject, keyPair.public
        )

        val signer = JcaContentSignerBuilder("SHA256WithRSA").build(keyPair.private)

        val certHolder: X509CertificateHolder = certBuilder.build(signer)
        val cert = JcaX509CertificateConverter()
            .setProvider(BouncyCastleProvider.PROVIDER_NAME)
            .getCertificate(certHolder)

        val keyStore = KeyStore.getInstance("PKCS12")
        keyStore.load(null, password)
        keyStore.setKeyEntry("micyou", keyPair.private, password, arrayOf(cert))

        Logger.i(TAG, "Generated self-signed certificate for: $commonName")
        return keyStore
    }
}
