package com.lanrhyme.micyou.util

import com.lanrhyme.micyou.Logger
import com.lanrhyme.micyou.Settings
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

object JvmSettings : Settings {
    private val appDir: File = File(System.getProperty("user.dir"))
    private val configFile: File = File(appDir, "micyou.conf")
    private val fileSettings: FileSettings = FileSettings(configFile)

    private const val MIRROR_CDK_KEY = "mirror_cdk"
    private const val ENC_PREFIX = "enc:v1:"
    private const val MISSING_MARKER = "__micyou_missing_5f2e2f2a__"

    private val mirrorKeyBytes: ByteArray by lazy {
        val seed = listOf(
            System.getProperty("user.name", ""),
            System.getProperty("user.home", ""),
            System.getProperty("os.name", ""),
            System.getProperty("os.arch", ""),
            "mirror_cdk_v1"
        ).joinToString("|")
        MessageDigest.getInstance("SHA-256").digest(seed.toByteArray(Charsets.UTF_8))
    }

    private fun encryptMirrorCdk(value: String): String? = runCatching {
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val key = SecretKeySpec(mirrorKeyBytes, "AES")
        val gcmSpec = GCMParameterSpec(128, iv)
        cipher.init(Cipher.ENCRYPT_MODE, key, gcmSpec)
        val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        val ivEncoded = Base64.getEncoder().encodeToString(iv)
        val dataEncoded = Base64.getEncoder().encodeToString(encrypted)
        "$ENC_PREFIX$ivEncoded:$dataEncoded"
    }.getOrElse {
        Logger.e("JvmSettings", "Failed to encrypt mirror CDK", it)
        null
    }

    private fun decryptMirrorCdk(value: String): String? = runCatching {
        val payload = value.removePrefix(ENC_PREFIX)
        val parts = payload.split(":")
        if (parts.size != 2) return null

        val iv = Base64.getDecoder().decode(parts[0])
        val encrypted = Base64.getDecoder().decode(parts[1])
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val key = SecretKeySpec(mirrorKeyBytes, "AES")
        val gcmSpec = GCMParameterSpec(128, iv)
        cipher.init(Cipher.DECRYPT_MODE, key, gcmSpec)
        val plain = cipher.doFinal(encrypted)
        plain.toString(Charsets.UTF_8)
    }.getOrElse {
        Logger.e("JvmSettings", "Failed to decrypt mirror CDK", it)
        null
    }
    
    override fun getString(key: String, defaultValue: String): String {
        if (key != MIRROR_CDK_KEY) {
            return fileSettings.getString(key, defaultValue)
        }

        val raw = fileSettings.getString(key, MISSING_MARKER)
        if (raw == MISSING_MARKER || raw.isBlank()) {
            return defaultValue
        }

        if (raw.startsWith(ENC_PREFIX)) {
            return decryptMirrorCdk(raw) ?: defaultValue
        }

        // Migrate legacy plaintext value to encrypted storage.
        encryptMirrorCdk(raw)?.let { encrypted ->
            fileSettings.putString(key, encrypted)
        }
        return raw
    }
    
    override fun putString(key: String, value: String) {
        if (key == MIRROR_CDK_KEY) {
            if (value.isBlank()) {
                fileSettings.putString(key, value)
                return
            }

            val encrypted = encryptMirrorCdk(value)
            if (encrypted != null) {
                fileSettings.putString(key, encrypted)
            }
            return
        }

        fileSettings.putString(key, value)
    }
    
    override fun getLong(key: String, defaultValue: Long): Long {
        return fileSettings.getLong(key, defaultValue)
    }
    
    override fun putLong(key: String, value: Long) {
        fileSettings.putLong(key, value)
    }
    
    override fun getBoolean(key: String, defaultValue: Boolean): Boolean {
        return fileSettings.getBoolean(key, defaultValue)
    }
    
    override fun putBoolean(key: String, value: Boolean) {
        fileSettings.putBoolean(key, value)
    }
    
    override fun getInt(key: String, defaultValue: Int): Int {
        return fileSettings.getInt(key, defaultValue)
    }
    
    override fun putInt(key: String, value: Int) {
        fileSettings.putInt(key, value)
    }
    
    override fun getFloat(key: String, defaultValue: Float): Float {
        return fileSettings.getFloat(key, defaultValue)
    }
    
    override fun putFloat(key: String, value: Float) {
        fileSettings.putFloat(key, value)
    }
    
    fun remove(key: String) {
        fileSettings.remove(key)
    }
    
    fun clear() {
        fileSettings.clear()
    }
}
