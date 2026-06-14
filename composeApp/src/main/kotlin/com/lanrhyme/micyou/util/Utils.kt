package com.lanrhyme.micyou.util

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.lanrhyme.micyou.BuildConfig
import java.security.MessageDigest

fun getAppVersion(): String = BuildConfig.VERSION_NAME

fun openUrl(url: String) {
    ContextHelper.getContext()?.let { context ->
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }
}

fun copyToClipboard(text: String) {
    ContextHelper.getContext()?.let { context ->
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("MicYou", text)
        clipboard.setPrimaryClip(clip)
    }
}

fun getString(@androidx.annotation.StringRes resId: Int, vararg formatArgs: Any): String {
    return ContextHelper.getContext()?.getString(resId, *formatArgs) ?: ""
}

fun formatBytes(bytes: Long): String {
    return when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
        bytes < 1024 * 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024))
        else -> "%.1f GB".format(bytes / (1024.0 * 1024 * 1024))
    }
}

fun md5(input: String): String {
    val md = MessageDigest.getInstance("MD5")
    val digest = md.digest(input.toByteArray())
    return digest.joinToString("") { "%02x".format(it) }
}

fun currentTimeSeconds(): Long = System.currentTimeMillis() / 1000

fun getAifadianApiToken(): String = BuildConfig.AIFADIAN_API_TOKEN
fun getAifadianUserId(): String = BuildConfig.AIFADIAN_USER_ID

