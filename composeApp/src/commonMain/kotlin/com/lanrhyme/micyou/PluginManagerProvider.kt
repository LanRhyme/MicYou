package com.lanrhyme.micyou

import com.lanrhyme.micyou.plugin.PluginInfo
import kotlinx.coroutines.flow.StateFlow

interface PluginManagerProvider {
    val plugins: StateFlow<List<PluginInfo>>
    fun scanPlugins()
    fun importPlugin(pluginFilePath: String): Result<PluginInfo>
    fun enablePlugin(pluginId: String): Result<Unit>
    fun disablePlugin(pluginId: String): Result<Unit>
    fun deletePlugin(pluginId: String): Result<Unit>
    fun getPlugin(pluginId: String): Any?
    fun getPluginSettingsProvider(pluginId: String): Any?
    fun getPluginUIProvider(pluginId: String): Any?
}

expect fun createPluginManager(pluginsDirPath: String): PluginManagerProvider?

expect fun getPluginsDirPath(): String
