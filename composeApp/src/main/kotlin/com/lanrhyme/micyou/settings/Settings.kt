package com.lanrhyme.micyou.settings

import android.content.Context
import android.content.SharedPreferences
import android.provider.Settings as AndroidSecureSettings
import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import com.lanrhyme.micyou.util.ContextHelper
import com.lanrhyme.micyou.util.getString
import com.lanrhyme.micyou.util.Logger

object AppSettings {
    private val prefs: SharedPreferences by lazy {
        val ctx = ContextHelper.getContext() ?: throw IllegalStateException(
            "ContextHelper not initialized. Call ContextHelper.init(context) in MainActivity."
        )
        ctx.getSharedPreferences("android_mic_prefs", Context.MODE_PRIVATE)
    }

    private const val MIRROR_CDK_KEY = "mirror_cdk"
    private const val ENC_PREFIX = "enc:v1:"

    private val mirrorKeyBytes: ByteArray by lazy {
        val ctx = ContextHelper.getContext() ?: throw IllegalStateException(
            "ContextHelper not initialized."
        )
        val androidId = AndroidSecureSettings.Secure.getString(
            ctx.contentResolver,
            AndroidSecureSettings.Secure.ANDROID_ID
        ).orEmpty()
        val seed = "${ctx.packageName}:$androidId:mirror_cdk_v1"
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
        Logger.e("AppSettings", "Failed to encrypt mirror CDK", it)
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
        Logger.e("AppSettings", "Failed to decrypt mirror CDK", it)
        null
    }

    fun getString(key: String, defaultValue: String): String {
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

    fun putString(key: String, value: String) {
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

    fun getLong(key: String, defaultValue: Long): Long = prefs.getLong(key, defaultValue)

    fun putLong(key: String, value: Long) {
        prefs.edit().putLong(key, value).apply()
    }

    fun getBoolean(key: String, defaultValue: Boolean): Boolean = prefs.getBoolean(key, defaultValue)

    fun putBoolean(key: String, value: Boolean) {
        prefs.edit().putBoolean(key, value).apply()
    }

    fun getInt(key: String, defaultValue: Int): Int = prefs.getInt(key, defaultValue)

    fun putInt(key: String, value: Int) {
        prefs.edit().putInt(key, value).apply()
    }

    fun getFloat(key: String, defaultValue: Float): Float = prefs.getFloat(key, defaultValue)

    fun putFloat(key: String, value: Float) {
        prefs.edit().putFloat(key, value).apply()
    }
}
