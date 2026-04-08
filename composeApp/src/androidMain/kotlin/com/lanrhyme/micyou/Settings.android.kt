package com.lanrhyme.micyou

import android.content.Context
import android.content.SharedPreferences
import android.provider.Settings as AndroidSecureSettings
import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

object AndroidContext {
    var context: Context? = null
    
    fun init(ctx: Context) {
        context = ctx.applicationContext
    }
}

actual object SettingsFactory {
    actual fun getSettings(): Settings {
        val ctx = AndroidContext.context
            ?: throw IllegalStateException("AndroidContext not initialized. Call AndroidContext.init(context) in MainActivity.")
        return AndroidSettings(ctx)
    }
}

class AndroidSettings(private val context: Context) : Settings {
    private val prefs: SharedPreferences = context.getSharedPreferences("android_mic_prefs", Context.MODE_PRIVATE)

    private companion object {
        const val MIRROR_CDK_KEY = "mirror_cdk"
        const val ENC_PREFIX = "enc:v1:"
    }

    private val mirrorKeyBytes: ByteArray by lazy {
        val androidId = AndroidSecureSettings.Secure.getString(
            context.contentResolver,
            AndroidSecureSettings.Secure.ANDROID_ID
        ).orEmpty()
        val seed = "${context.packageName}:$androidId:mirror_cdk_v1"
        MessageDigest.getInstance("SHA-256").digest(seed.toByteArray(Charsets.UTF_8))
    }

    private fun encryptMirrorCdk(value: String): String? = runCatching {
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val key = SecretKeySpec(mirrorKeyBytes, "AES")
        val gcmSpec = GCMParameterSpec(128, iv)
        cipher.init(Cipher.ENCRYPT_MODE, key, gcmSpec)
        val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        val ivEncoded = Base64.encodeToString(iv, Base64.NO_WRAP)
        val dataEncoded = Base64.encodeToString(encrypted, Base64.NO_WRAP)
        "$ENC_PREFIX$ivEncoded:$dataEncoded"
    }.getOrElse {
        Logger.e("AndroidSettings", "Failed to encrypt mirror CDK", it)
        null
    }

    private fun decryptMirrorCdk(value: String): String? = runCatching {
        val payload = value.removePrefix(ENC_PREFIX)
        val parts = payload.split(":")
        if (parts.size != 2) return null

        val iv = Base64.decode(parts[0], Base64.NO_WRAP)
        val encrypted = Base64.decode(parts[1], Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val key = SecretKeySpec(mirrorKeyBytes, "AES")
        val gcmSpec = GCMParameterSpec(128, iv)
        cipher.init(Cipher.DECRYPT_MODE, key, gcmSpec)
        val plain = cipher.doFinal(encrypted)
        plain.toString(Charsets.UTF_8)
    }.getOrElse {
        Logger.e("AndroidSettings", "Failed to decrypt mirror CDK", it)
        null
    }

    override fun getString(key: String, defaultValue: String): String {
        val raw = prefs.getString(key, null) ?: return defaultValue
        if (key != MIRROR_CDK_KEY || raw.isBlank()) {
            return raw
        }

        if (raw.startsWith(ENC_PREFIX)) {
            return decryptMirrorCdk(raw) ?: defaultValue
        }

        // Migrate legacy plaintext value to encrypted storage.
        encryptMirrorCdk(raw)?.let { encrypted ->
            prefs.edit().putString(key, encrypted).apply()
        }
        return raw
    }

    override fun putString(key: String, value: String) {
        if (key == MIRROR_CDK_KEY) {
            if (value.isBlank()) {
                prefs.edit().putString(key, value).apply()
                return
            }

            val encrypted = encryptMirrorCdk(value)
            if (encrypted != null) {
                prefs.edit().putString(key, encrypted).apply()
            }
            return
        }

        prefs.edit().putString(key, value).apply()
    }

    override fun getLong(key: String, defaultValue: Long): Long {
        return prefs.getLong(key, defaultValue)
    }

    override fun putLong(key: String, value: Long) {
        prefs.edit().putLong(key, value).apply()
    }

    override fun getBoolean(key: String, defaultValue: Boolean): Boolean {
        return prefs.getBoolean(key, defaultValue)
    }

    override fun putBoolean(key: String, value: Boolean) {
        prefs.edit().putBoolean(key, value).apply()
    }

    override fun getInt(key: String, defaultValue: Int): Int {
        return prefs.getInt(key, defaultValue)
    }

    override fun putInt(key: String, value: Int) {
        prefs.edit().putInt(key, value).apply()
    }

    override fun getFloat(key: String, defaultValue: Float): Float {
        return prefs.getFloat(key, defaultValue)
    }

    override fun putFloat(key: String, value: Float) {
        prefs.edit().putFloat(key, value).apply()
    }
}

