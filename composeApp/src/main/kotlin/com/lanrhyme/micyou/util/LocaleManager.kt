package com.lanrhyme.micyou.util

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import java.util.Locale as JavaLocale

/**
 * Manages app-wide locale configuration.
 *
 * Provides locale wrapping for Context and locale persistence via SharedPreferences.
 * Extracted from MainActivity to reduce its responsibilities.
 */
object LocaleManager {

    private const val PREFS_NAME = "android_mic_prefs"
    private const val KEY_LANGUAGE = "language"

    /**
     * Wraps a base context with the saved locale, if one is configured.
     * Should be called from Activity.attachBaseContext().
     */
    fun attachBaseContext(base: Context): Context {
        val languageCode = getSavedLanguageCode(base)
        return if (languageCode != "system") {
            wrapContextWithLocale(base, languageCode)
        } else {
            base
        }
    }

    /**
     * Reads the saved language code from SharedPreferences,
     * converting from AppLanguage.name to AppLanguage.code.
     */
    fun getSavedLanguageCode(context: Context): String {
        return try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val raw = prefs.getString(KEY_LANGUAGE, null)
            if (raw != null) {
                try {
                    AppLanguage.valueOf(raw).code
                } catch (_: Exception) {
                    raw
                }
            } else {
                "system"
            }
        } catch (e: Exception) {
            "system"
        }
    }

    /**
     * Creates a ConfigurationContext with the given language code applied.
     */
    fun wrapContextWithLocale(context: Context, languageCode: String): Context {
        val locale = parseLocale(languageCode)
        val config = Configuration(context.resources.configuration)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
            config.setLocale(locale)
        } else {
            @Suppress("DEPRECATION")
            config.locale = locale
        }
        return context.createConfigurationContext(config)
    }

    private fun parseLocale(languageCode: String): JavaLocale {
        return try {
            val parts = if (languageCode.contains("-r")) {
                languageCode.split("-r", limit = 2)
            } else if (languageCode.contains("-")) {
                languageCode.split("-", limit = 2)
            } else {
                null
            }
            if (parts != null && parts.size == 2) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    JavaLocale.Builder().setLanguage(parts[0]).setRegion(parts[1]).build()
                } else {
                    @Suppress("DEPRECATION")
                    JavaLocale(parts[0], parts[1])
                }
            } else {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    JavaLocale.Builder().setLanguage(languageCode).build()
                } else {
                    @Suppress("DEPRECATION")
                    JavaLocale(languageCode)
                }
            }
        } catch (e: Exception) {
            JavaLocale.getDefault()
        }
    }
}
