package com.lanrhyme.micyou

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lanrhyme.micyou.plugin.PluginHost
import com.lanrhyme.micyou.plugin.PluginInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class PluginUiState(
    val plugins: List<PluginInfo> = emptyList(),
    val showPluginSyncWarning: Boolean = false,
    val missingPlugins: List<MissingPluginInfo> = emptyList()
)

class PluginViewModel : ViewModel() {
    private val _uiState = MutableStateFlow(PluginUiState())
    val uiState: StateFlow<PluginUiState> = _uiState.asStateFlow()
    private var pluginManager: PluginManagerProvider? = null
    private lateinit var pluginHost: PluginHost

    fun initialize(
        audioEngine: AudioEngine,
        showSnackbarCallback: (String) -> Unit,
        appLanguageProvider: () -> String,
        appStringProvider: (String) -> String
    ) {
        // Create plugin host
        pluginHost = createPluginHost(
            audioEngine = audioEngine,
            showSnackbarCallback = showSnackbarCallback,
            showNotificationCallback = { title, message ->
                Logger.i("PluginHost", "Notification: $title - $message")
            }
        )
        
        // Create plugin manager with language provider
        pluginManager = createPluginManager(
            pluginsDirPath = getPluginsDirPath(),
            pluginHost = pluginHost,
            appLanguageProvider = appLanguageProvider,
            appStringProvider = appStringProvider
        )
        
        pluginManager?.let { pm ->
            viewModelScope.launch {
                pm.plugins.collect { pluginList ->
                    _uiState.update { it.copy(plugins = pluginList) }
                }
            }
        }
    }

    fun importPlugin(filePath: String, onResult: (Result<PluginInfo>) -> Unit) {
        viewModelScope.launch {
            val result = pluginManager?.importPlugin(filePath) 
                ?: Result.failure(Exception("Plugins not supported on this platform"))
            onResult(result)
        }
    }
    
    fun enablePlugin(pluginId: String) {
        viewModelScope.launch {
            pluginManager?.enablePlugin(pluginId)
        }
    }
    
    fun disablePlugin(pluginId: String) {
        viewModelScope.launch {
            pluginManager?.disablePlugin(pluginId)
        }
    }
    
    fun deletePlugin(pluginId: String) {
        viewModelScope.launch {
            pluginManager?.deletePlugin(pluginId)
        }
    }
    
    fun getPluginUIProvider(pluginId: String): Any? {
        return pluginManager?.getPluginUIProvider(pluginId)
    }
    
    fun getPluginSettingsProvider(pluginId: String): Any? {
        return pluginManager?.getPluginSettingsProvider(pluginId)
    }

    fun showPluginSyncWarning(missingPlugins: List<MissingPluginInfo>) {
        _uiState.update { it.copy(showPluginSyncWarning = true, missingPlugins = missingPlugins) }
    }

    fun dismissPluginSyncWarning() {
        _uiState.update { it.copy(showPluginSyncWarning = false, missingPlugins = emptyList()) }
    }
}
