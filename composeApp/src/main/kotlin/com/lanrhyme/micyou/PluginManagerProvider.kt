package com.lanrhyme.micyou

import com.lanrhyme.micyou.plugin.PluginHost
import com.lanrhyme.micyou.plugin.PluginInfo
import com.lanrhyme.micyou.plugin.PluginUIProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

interface PluginManagerProvider {
    val plugins: StateFlow<List<PluginInfo>>
    fun scanPlugins()
    fun importPlugin(pluginFilePath: String): Result<PluginInfo>
    fun enablePlugin(pluginId: String): Result<Unit>
    fun disablePlugin(pluginId: String): Result<Unit>
    fun deletePlugin(pluginId: String): Result<Unit>
    fun getPlugin(pluginId: String): Any?
    fun getPluginSettingsProvider(pluginId: String): Any?
    fun getPluginUIProvider(pluginId: String): PluginUIProvider?
}

class StubPluginManagerProvider : PluginManagerProvider {
    private val _plugins = MutableStateFlow<List<PluginInfo>>(emptyList())
    override val plugins: StateFlow<List<PluginInfo>> = _plugins.asStateFlow()

    override fun scanPlugins() {}
    override fun importPlugin(pluginFilePath: String): Result<PluginInfo> = 
        Result.failure(UnsupportedOperationException("Plugin system not yet implemented"))
    override fun enablePlugin(pluginId: String): Result<Unit> = Result.success(Unit)
    override fun disablePlugin(pluginId: String): Result<Unit> = Result.success(Unit)
    override fun deletePlugin(pluginId: String): Result<Unit> = Result.success(Unit)
    override fun getPlugin(pluginId: String): Any? = null
    override fun getPluginSettingsProvider(pluginId: String): Any? = null
    override fun getPluginUIProvider(pluginId: String): PluginUIProvider? = null
}

fun createPluginManager(
    pluginsDirPath: String,
    pluginHost: PluginHost,
    appLanguageProvider: () -> String = { "en" },
    appStringProvider: ((String) -> String)? = null
): PluginManagerProvider = StubPluginManagerProvider()

fun getPluginsDirPath(): String {
    val context = ContextHelper.getContext()
    return context?.filesDir?.resolve("plugins")?.absolutePath ?: "/data/plugins"
}
