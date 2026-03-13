package com.lanrhyme.micyou

import com.lanrhyme.micyou.plugin.PluginInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class AndroidPluginManagerProvider : PluginManagerProvider {
    private val _plugins = MutableStateFlow<List<PluginInfo>>(emptyList())
    override val plugins: StateFlow<List<PluginInfo>> = _plugins
    
    override fun scanPlugins() {}
    
    override fun importPlugin(pluginFilePath: String): Result<PluginInfo> = 
        Result.failure(Exception("Plugins are not supported on Android"))
    
    override fun enablePlugin(pluginId: String): Result<Unit> = 
        Result.failure(Exception("Plugins are not supported on Android"))
    
    override fun disablePlugin(pluginId: String): Result<Unit> = 
        Result.failure(Exception("Plugins are not supported on Android"))
    
    override fun deletePlugin(pluginId: String): Result<Unit> = 
        Result.failure(Exception("Plugins are not supported on Android"))
    
    override fun getPlugin(pluginId: String): Any? = null
    
    override fun getPluginSettingsProvider(pluginId: String): Any? = null
    
    override fun getPluginUIProvider(pluginId: String): Any? = null
}

actual fun createPluginManager(pluginsDirPath: String): PluginManagerProvider? {
    return null
}

actual fun getPluginsDirPath(): String {
    return ""
}
