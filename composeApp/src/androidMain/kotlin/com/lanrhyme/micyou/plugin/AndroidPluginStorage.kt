package com.lanrhyme.micyou.plugin

import android.content.Context
import android.content.SharedPreferences
import com.lanrhyme.micyou.ContextHelper
import com.lanrhyme.micyou.Logger
import java.io.File
import org.jetbrains.compose.resources.getString

class AndroidPluginStorage(
    override val pluginId: String,
    private val dataDir: File,
    private val pluginInstallDir: File,
    override val host: PluginHost,
    private val appLanguageProvider: () -> String = { "en" },
    private val appStringProvider: ((String) -> String)? = null
) : PluginContext {
    private val prefs: SharedPreferences by lazy {
        val context = ContextHelper.getContext()
            ?: throw IllegalStateException("Context not available")
        context.getSharedPreferences("micyou_plugin_$pluginId", Context.MODE_PRIVATE)
    }

    override val pluginDataDir: String get() = dataDir.absolutePath

    override val localization: PluginLocalization by lazy {
        AndroidPluginLocalization(pluginId, pluginInstallDir, appLanguageProvider)
    }

    override val appLocalization: PluginLocalization by lazy {
        if (appStringProvider != null) {
            AndroidAppPluginLocalization(appStringProvider, appLanguageProvider)
        } else {
            localization
        }
    }

    override fun getString(key: String, defaultValue: String): String = prefs.getString(key, defaultValue) ?: defaultValue
    override fun putString(key: String, value: String) = prefs.edit().putString(key, value).apply()
    override fun getBoolean(key: String, defaultValue: Boolean): Boolean = prefs.getBoolean(key, defaultValue)
    override fun putBoolean(key: String, value: Boolean) = prefs.edit().putBoolean(key, value).apply()
    override fun getInt(key: String, defaultValue: Int): Int = prefs.getInt(key, defaultValue)
    override fun putInt(key: String, value: Int) = prefs.edit().putInt(key, value).apply()
    override fun getFloat(key: String, defaultValue: Float): Float = prefs.getFloat(key, defaultValue)
    override fun putFloat(key: String, value: Float) = prefs.edit().putFloat(key, value).apply()

    override fun log(message: String) {
        Logger.i("Plugin-$pluginId", message)
    }

    override fun logError(message: String, throwable: Throwable?) {
        Logger.e("Plugin-$pluginId", message, throwable)
    }
}
