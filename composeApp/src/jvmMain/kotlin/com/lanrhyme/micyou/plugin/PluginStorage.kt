package com.lanrhyme.micyou.plugin

import com.lanrhyme.micyou.Logger
import com.lanrhyme.micyou.util.FileSettings
import java.io.File

class PluginStorage(
    override val pluginId: String,
    private val dataDir: File,
    private val pluginInstallDir: File,
    override val host: PluginHost,
    private val appLanguageProvider: () -> String = { "en" },
    private val appStringProvider: ((String) -> String)? = null
) : PluginContext {
    private val pluginsDir = pluginInstallDir.parentFile
    private val configFile = File(pluginsDir, "$pluginId.conf")
    private val fileSettings = FileSettings(configFile)

    override val pluginDataDir: String get() = dataDir.absolutePath

    override val localization: PluginLocalization by lazy {
        PluginLocalizationImpl(pluginId, pluginInstallDir, appLanguageProvider)
    }

    override val appLocalization: PluginLocalization by lazy {
        if (appStringProvider != null) {
            AppPluginLocalization(appStringProvider, appLanguageProvider)
        } else {
            localization
        }
    }

    override fun getString(key: String, defaultValue: String): String = fileSettings.getString(key, defaultValue)
    override fun putString(key: String, value: String) = fileSettings.putString(key, value)
    override fun getBoolean(key: String, defaultValue: Boolean): Boolean = fileSettings.getBoolean(key, defaultValue)
    override fun putBoolean(key: String, value: Boolean) = fileSettings.putBoolean(key, value)
    override fun getInt(key: String, defaultValue: Int): Int = fileSettings.getInt(key, defaultValue)
    override fun putInt(key: String, value: Int) = fileSettings.putInt(key, value)
    override fun getFloat(key: String, defaultValue: Float): Float = fileSettings.getFloat(key, defaultValue)
    override fun putFloat(key: String, value: Float) = fileSettings.putFloat(key, value)

    override fun log(message: String) {
        Logger.i("Plugin-$pluginId", message)
    }

    override fun logError(message: String, throwable: Throwable?) {
        Logger.e("Plugin-$pluginId", message, throwable)
    }
}
