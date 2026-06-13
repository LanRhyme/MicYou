package com.lanrhyme.micyou

import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.Locale as JavaLocale

enum class AppLanguage(val label: String, val code: String) {
    System("跟随系统", "system"),
    Chinese("简体中文", "zh"),
    ChineseTraditional("繁體中文", "zh-TW"),
    Cantonese("粤语", "zh-HK"),
    English("English", "en"),
    ChineseCat("中文（猫猫语）🐱", "ca"),
    ChineseHard("中文（坚硬）", "zh-rHD"),
}

data class PermissionState(
    val type: PermissionType,
    val manifestPermission: String,
    val isGranted: Boolean,
    val minSdkVersion: Int = 0
)

enum class PermissionType(val labelKey: String, val descKey: String, val isRequired: Boolean) {
    RECORD_AUDIO(
        labelKey = "permissionRecordAudioLabel",
        descKey = "permissionRecordAudioDesc",
        isRequired = true
    ),
    POST_NOTIFICATIONS(
        labelKey = "permissionPostNotificationsLabel",
        descKey = "permissionPostNotificationsDesc",
        isRequired = false
    )
}

/** Captured at app startup, never modified by setAppLocale. */
private val originalSystemLocale: JavaLocale = JavaLocale.getDefault()

fun setAppLocale(languageCode: String) {
    val locale = if (languageCode == "system") {
        originalSystemLocale
    } else {
        try {
            // e.g. "zh-rHD" -> values-zh-rHD
            val parts = if (languageCode.contains("-r")) {
                languageCode.split("-r", limit = 2)
            } else if (languageCode.contains("-")) {
                languageCode.split("-", limit = 2)
            } else {
                null
            }
            if (parts != null && parts.size == 2) {
                JavaLocale.Builder().setLanguage(parts[0]).setRegion(parts[1]).build()
            } else {
                JavaLocale.Builder().setLanguage(languageCode).build()
            }
        } catch (e: Exception) {
            Logger.e("Localization", "Failed to parse locale: $languageCode", e)
            originalSystemLocale
        }
    }
    JavaLocale.setDefault(locale)
    ContextHelper.setLocale(locale)
}

fun readResourceFile(path: String): String? {
    return try {
        val context = ContextHelper.getContext() ?: return null
        val assetManager = context.assets
        BufferedReader(InputStreamReader(assetManager.open(path), "UTF-8")).use { reader ->
            reader.readText()
        }
    } catch (e: Exception) {
        Logger.e("Localization", "Failed to read resource file: $path - ${e.message}")
        null
    }
}
