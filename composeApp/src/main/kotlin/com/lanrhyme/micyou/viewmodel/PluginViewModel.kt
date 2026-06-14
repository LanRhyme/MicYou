package com.lanrhyme.micyou.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lanrhyme.micyou.audio.AudioEngine
import com.lanrhyme.micyou.plugin.AndroidPluginHostImpl
import com.lanrhyme.micyou.plugin.AndroidPluginManager
import com.lanrhyme.micyou.plugin.PluginHost
import com.lanrhyme.micyou.plugin.PluginInfo
import com.lanrhyme.micyou.plugin.PluginUIProvider
import com.lanrhyme.micyou.settings.SettingsFactory
import com.lanrhyme.micyou.ui.dialog.MissingPluginInfo
import com.lanrhyme.micyou.util.ContextHelper
import com.lanrhyme.micyou.util.Logger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

data class PluginUiState(
    val plugins: List<PluginInfo> = emptyList(),
    val showPluginSyncWarning: Boolean = false,
    val missingPlugins: List<MissingPluginInfo> = emptyList()
)

class PluginViewModel : ViewModel() {
    private val _uiState = MutableStateFlow(PluginUiState())
    val uiState: StateFlow<PluginUiState> = _uiState.asStateFlow()
    private var pluginManager: AndroidPluginManager? = null
    private lateinit var pluginHost: PluginHost

    fun initialize(
        audioEngine: AudioEngine,
        showSnackbarCallback: (String) -> Unit,
        appLanguageProvider: () -> String,
        appStringProvider: (String) -> String
    ) {
        // Create plugin host
        pluginHost = AndroidPluginHostImpl(
            audioEngine = audioEngine,
            settings = SettingsFactory.getSettings(),
            showSnackbarCallback = showSnackbarCallback,
            showNotificationCallback = { title, message ->
                Logger.i("PluginHost", "Notification: $title - $message")
            }
        )
        
        // Create plugin manager with language provider
        val pluginsDir = File(
            ContextHelper.getContext()?.filesDir ?: File("/data/data/com.lanrhyme.micyou/files"),
            "plugins"
        )
        val pm = AndroidPluginManager(
            pluginsDir = pluginsDir,
            pluginHost = pluginHost,
            appLanguageProvider = appLanguageProvider,
            appStringProvider = appStringProvider
        )
        pluginManager = pm
        
        viewModelScope.launch {
            pm.plugins.collect { pluginList ->
                _uiState.update { it.copy(plugins = pluginList) }
            }
        }
    }

    fun importPlugin(filePath: String, onResult: (Result<PluginInfo>) -> Unit) {
        viewModelScope.launch {
            val result = pluginManager?.importPlugin(File(filePath)) 
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