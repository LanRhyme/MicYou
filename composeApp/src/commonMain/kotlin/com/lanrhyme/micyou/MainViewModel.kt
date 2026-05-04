package com.lanrhyme.micyou

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lanrhyme.micyou.plugin.PluginInfo
import com.lanrhyme.micyou.theme.PaletteStyle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import micyou.composeapp.generated.resources.*
import micyou.composeapp.generated.resources.Res
import org.jetbrains.compose.resources.getString

enum class ConnectionMode(val label: String) {
    Wifi("Wi-Fi"),
    Usb("USB (ADB)"),
    Web("Web")
}

enum class StreamState {
    Idle, Connecting, Streaming, Error
}

enum class NoiseReductionType(val label: String) {
    Ulunas("Ulunas (ONNX)"),
    RNNoise("RNNoise"),
    Speexdsp("Speexdsp"),
    None("None")
}

enum class VisualizerStyle(val label: String) {
    VolumeRing("VolumeRing"),
    Ripple("Ripple"),
    Bars("Bars"),
    Wave("Wave"),
    Glow("Glow"),
    Particles("Particles")
}

enum class UpdateDownloadState {
    Idle, Downloading, Downloaded, Installing, Failed
}

data class AppUiState(
    // Audio Stream State
    val mode: ConnectionMode = ConnectionMode.Wifi,
    val streamState: StreamState = StreamState.Idle,
    val ipAddress: String = "192.168.1.5",
    val port: String = "6000",
    val errorMessage: String? = null,
    val monitoringEnabled: Boolean = false,
    val sampleRate: SampleRate = SampleRate.Rate48000,
    val channelCount: ChannelCount = ChannelCount.Stereo,
    val audioFormat: AudioFormat = AudioFormat.PCM_FLOAT,
    val isMuted: Boolean = false,
    val isAutoConfig: Boolean = true,
    val showFirewallDialog: Boolean = false,
    val pendingFirewallPort: Int? = null,
    
    // Error Dialog State
    val showErrorDialog: Boolean = false,
    val errorDetails: ConnectionErrorDetails? = null,
    
    // Audio Processing Settings
    val enableNS: Boolean = false,
    val nsType: NoiseReductionType = NoiseReductionType.Ulunas,
    val enableAGC: Boolean = false,
    val agcTargetLevel: Int = 32000,
    val enableVAD: Boolean = false,
    val vadThreshold: Int = 10,
    val enableDereverb: Boolean = false,
    val dereverbLevel: Float = 0.5f,
    val amplification: Float = 15.0f,
    val androidAudioSourceName: String = "Unprocessed",
    val audioConfigRevision: Int = 0,

    // Web Mode State
    val webUrl: String? = null,
    val webServerRunning: Boolean = false,
    
    // Settings State
    val themeMode: ThemeMode = ThemeMode.System,
    val seedColor: Long = 0xFF1565C0,
    val useDynamicColor: Boolean = false,
    val oledPureBlack: Boolean = false,
    val paletteStyle: PaletteStyle = PaletteStyle.TonalSpot,
    val useExpressiveShapes: Boolean = true,
    val language: AppLanguage = AppLanguage.System,
    val autoStart: Boolean = false,
    val enableStreamingNotification: Boolean = true,
    val keepScreenOn: Boolean = false,
    val minimizeToTray: Boolean = true,
    val closeAction: CloseAction = CloseAction.Prompt,
    val showCloseConfirmDialog: Boolean = false,
    val rememberCloseAction: Boolean = false,
    val autoCheckUpdate: Boolean = true,
    val useMirrorDownload: Boolean = false,
    val mirrorCdk: String = "",
    val showMirrorCdkDialog: Boolean = false,
    val pocketMode: Boolean = true,
    val visualizerStyle: VisualizerStyle = VisualizerStyle.VolumeRing,
    val backgroundSettings: BackgroundSettings = BackgroundSettings(),
    val floatingWindowEnabled: Boolean = false,
    val useSystemTitleBar: Boolean = false,
    val showFirstLaunchDialog: Boolean = false,
    val showVBCableDialog: Boolean = false,
    val vbcableInstallProgress: String? = null,
    
    // Plugin State
    val plugins: List<PluginInfo> = emptyList(),
    val showPluginSyncWarning: Boolean = false,
    val missingPlugins: List<MissingPluginInfo> = emptyList(),
    
    // Update State
    val updateInfo: UpdateInfo? = null,
    val updateDownloadState: UpdateDownloadState = UpdateDownloadState.Idle,
    val updateDownloadProgress: Float = 0f,
    val updateDownloadedBytes: Long = 0,
    val updateTotalBytes: Long = 0,
    val updateErrorMessage: String? = null,

    // Performance State
    val performanceMode: String = "Default",
    val audioMetrics: AudioMetrics? = null,
    val metricsHistory: List<AudioMetrics> = emptyList(),
    val showMonitoringPanel: Boolean = false,

    // Discovery State
    val discoveredDevices: List<DiscoveredDevice> = emptyList(),
    val isDiscovering: Boolean = false,

    // UI State
    val installMessage: String? = null,
    val snackbarMessage: String? = null
)

enum class CloseAction(val label: String) {
    Prompt("prompt"),
    Minimize("minimize"),
    Exit("exit")
}

/**
 * Main ViewModel - Coordinates between specialized ViewModels
 * This ViewModel acts as a facade for the UI layer
 */
class MainViewModel : ViewModel() {
    // Specialized ViewModels
    private val audioStreamViewModel = AudioStreamViewModel()
    private val settingsViewModel = SettingsViewModel()
    private val pluginViewModel = PluginViewModel()
    private val updateViewModel = UpdateViewModel()
    
    private val _uiState = MutableStateFlow(AppUiState())
    val uiState: StateFlow<AppUiState> = _uiState.asStateFlow()
    
    // Expose audio levels from AudioStreamViewModel
    val audioLevels = audioStreamViewModel.audioLevels
    val audioLevelData = audioStreamViewModel.audioLevelData
    val audioMetricsFlow = audioStreamViewModel.audioMetrics
    val levelHistory = audioStreamViewModel.levelHistory
    
    private val settings = SettingsFactory.getSettings()
    
    // Track if auto-start has been checked to avoid multiple triggers
    private var autoStartChecked = false

    init {
        // Initialize Plugin ViewModel with shared AudioEngine
        val initialLanguage = try { 
            AppLanguage.valueOf(settings.getString("language", AppLanguage.System.name)) 
        } catch(e: Exception) { 
            AppLanguage.System 
        }
        
        pluginViewModel.initialize(
            audioEngine = audioStreamViewModel.audioEngine,
            showSnackbarCallback = { message ->
                _uiState.update { it.copy(snackbarMessage = message) }
            },
            appLanguageProvider = { 
                val lang = _uiState.value.language
                when (lang) {
                    AppLanguage.Chinese -> "zh"
                    AppLanguage.ChineseTraditional -> "zh-TW"
                    AppLanguage.Cantonese -> "zh-HK"
                    AppLanguage.English -> "en"
                    AppLanguage.ChineseCat -> "cat"
                    AppLanguage.ChineseHard -> "zh_hard"
                    AppLanguage.System -> {
                        val locale = java.util.Locale.getDefault().toLanguageTag()
                        when {
                            locale.startsWith("zh-HK") -> "zh-HK"
                            locale.startsWith("zh-TW") || locale.startsWith("zh-Hant") -> "zh-TW"
                            locale.startsWith("zh") -> "zh"
                            else -> "en"
                        }
                    }
                }
            },
            appStringProvider = { it }
        )
        
        // Observe and merge states from all ViewModels
        setupStateObservers()

        // Observe discovered devices
        viewModelScope.launch {
            audioStreamViewModel.discoveredDevices.collect { devices ->
                _uiState.update { it.copy(discoveredDevices = devices) }
            }
        }
        viewModelScope.launch {
            audioStreamViewModel.isDiscovering.collect { discovering ->
                _uiState.update { it.copy(isDiscovering = discovering) }
            }
        }
        
        // Auto-check for updates
        if (settings.getBoolean("auto_check_update", true)) {
            updateViewModel.checkUpdateAuto()
        }
        
        // Check VB-Cable on startup (Windows only)
        checkVBCableOnStartup()
        
        // Observe VB-Cable installation progress
        viewModelScope.launch {
            getVBCableInstallProgress().collect { progress ->
                _uiState.update { it.copy(vbcableInstallProgress = progress) }
            }
        }

        // Observe update check results for user feedback
        viewModelScope.launch {
            updateViewModel.checkResultFlow.collect { result ->
                result?.let {                    val message = when (it) {
                        is UpdateCheckResult.UpdateAvailable -> String.format(getString(Res.string.updateAvailableMsg), it.info.versionName)
                        is UpdateCheckResult.NoUpdate -> getString(Res.string.isLatestVersion)
                        is UpdateCheckResult.Error -> String.format(getString(Res.string.updateCheckFailed), it.message)
                    }
                    _uiState.update { state -> state.copy(snackbarMessage = message) }
                }
            }
        }
    }

    private fun setupStateObservers() {
        viewModelScope.launch {
            val audioDataFlow = combine(
                audioStreamViewModel.uiState,
                audioStreamViewModel.audioMetrics,
                audioStreamViewModel.metricsHistoryFlow
            ) { state, metrics, history ->
                // Return a data structure to hold the 3 audio-related states
                object {
                    val state = state
                    val metrics = metrics
                    val history = history
                }
            }

            combine(
                audioDataFlow,
                settingsViewModel.uiState,
                pluginViewModel.uiState,
                updateViewModel.uiState
            ) { audioData, settingsState, pluginState, updateState ->
                val audioState = audioData.state
                val currentMetrics = audioData.metrics
                val history = audioData.history
                
                _uiState.update { current ->
                    current.copy(
                        mode = audioState.mode,
                        streamState = audioState.streamState,
                        ipAddress = audioState.ipAddress,
                        port = audioState.port,
                        errorMessage = audioState.errorMessage,
                        monitoringEnabled = audioState.monitoringEnabled,
                        sampleRate = audioState.sampleRate,
                        channelCount = audioState.channelCount,
                        audioFormat = audioState.audioFormat,
                        isMuted = audioState.isMuted,
                        isAutoConfig = audioState.isAutoConfig,
                        showFirewallDialog = audioState.showFirewallDialog,
                        pendingFirewallPort = audioState.pendingFirewallPort,
                        showErrorDialog = audioState.showErrorDialog,
                        errorDetails = audioState.errorDetails,
                        enableNS = audioState.enableNS,
                        nsType = audioState.nsType,
                        enableAGC = audioState.enableAGC,
                        agcTargetLevel = audioState.agcTargetLevel,
                        enableVAD = audioState.enableVAD,
                        vadThreshold = audioState.vadThreshold,
                        enableDereverb = audioState.enableDereverb,
                        dereverbLevel = audioState.dereverbLevel,
                        amplification = audioState.amplification,
                        androidAudioSourceName = audioState.androidAudioSourceName,
                        audioConfigRevision = audioState.audioConfigRevision,
                        webUrl = audioState.webUrl,
                        webServerRunning = audioState.webServerRunning,
                        themeMode = settingsState.themeMode,
                        seedColor = settingsState.seedColor,
                        useDynamicColor = settingsState.useDynamicColor,
                        oledPureBlack = settingsState.oledPureBlack,
                        paletteStyle = settingsState.paletteStyle,
                        useExpressiveShapes = settingsState.useExpressiveShapes,
                        language = settingsState.language,
                        autoStart = settingsState.autoStart,
                        enableStreamingNotification = settingsState.enableStreamingNotification,
                        keepScreenOn = settingsState.keepScreenOn,
                        minimizeToTray = settingsState.minimizeToTray,
                        closeAction = settingsState.closeAction,
                        showCloseConfirmDialog = settingsState.showCloseConfirmDialog,
                        rememberCloseAction = settingsState.rememberCloseAction,
                        autoCheckUpdate = settingsState.autoCheckUpdate,
                        useMirrorDownload = settingsState.useMirrorDownload,
                        mirrorCdk = settingsState.mirrorCdk,
                        showMirrorCdkDialog = settingsState.showMirrorCdkDialog,
                        pocketMode = settingsState.pocketMode,
                        visualizerStyle = settingsState.visualizerStyle,
                        backgroundSettings = settingsState.backgroundSettings,
                        floatingWindowEnabled = settingsState.floatingWindowEnabled,
                        useSystemTitleBar = settingsState.useSystemTitleBar,
                        showFirstLaunchDialog = settingsState.showFirstLaunchDialog,
                        plugins = pluginState.plugins,
                        showPluginSyncWarning = pluginState.showPluginSyncWarning,
                        missingPlugins = pluginState.missingPlugins,
                        updateInfo = updateState.updateInfo,
                        updateDownloadState = updateState.updateDownloadState,
                        updateDownloadProgress = updateState.updateDownloadProgress,
                        updateDownloadedBytes = updateState.updateDownloadedBytes,
                        updateTotalBytes = updateState.updateTotalBytes,
                        updateErrorMessage = updateState.updateErrorMessage,
                        performanceMode = audioState.performanceMode,
                        audioMetrics = currentMetrics,
                        metricsHistory = history,
                        showMonitoringPanel = audioState.showMonitoringPanel,
                        snackbarMessage = settingsState.snackbarMessage
                    )
                }
            }.collect {
                // Auto-start streaming on Desktop if enabled (only check once)
                if (!autoStartChecked && getPlatform().type == PlatformType.Desktop) {
                    autoStartChecked = true
                    val state = _uiState.value
                    if (state.autoStart && state.streamState == StreamState.Idle) {
                        Logger.i("MainViewModel", "Auto-starting stream due to autoStart setting")
                        audioStreamViewModel.startStream()
                    }
                }
            }
        }
    }

    // Delegate methods to specialized ViewModels
    // Audio Stream methods
    fun toggleStream() = audioStreamViewModel.toggleStream()
    fun toggleMute() = audioStreamViewModel.toggleMute()
    fun startStream() = audioStreamViewModel.startStream()
    fun stopStream() = audioStreamViewModel.stopStream()
    fun setMode(mode: ConnectionMode) = audioStreamViewModel.setMode(mode)
    fun startDiscovery() = audioStreamViewModel.startDiscovery()
    fun stopDiscovery() = audioStreamViewModel.stopDiscovery()
    fun restartDiscovery() = audioStreamViewModel.restartDiscovery()
    fun selectDiscoveredDevice(device: DiscoveredDevice) {
        audioStreamViewModel.setIp(device.hostAddress)
        audioStreamViewModel.setPort(device.port.toString())
    }
    fun setIp(ip: String) = audioStreamViewModel.setIp(ip)
    fun setPort(port: String) = audioStreamViewModel.setPort(port)
    fun setMonitoringEnabled(enabled: Boolean) = audioStreamViewModel.setMonitoringEnabled(enabled)
    fun setSampleRate(rate: SampleRate) = audioStreamViewModel.setSampleRate(rate)
    fun setChannelCount(count: ChannelCount) = audioStreamViewModel.setChannelCount(count)
    fun setAudioFormat(format: AudioFormat) = audioStreamViewModel.setAudioFormat(format)
    fun setAndroidAudioProcessing(enabled: Boolean) = audioStreamViewModel.setAndroidAudioProcessing(enabled)
    fun setEnableNS(enabled: Boolean) = audioStreamViewModel.setEnableNS(enabled)
    fun setNsType(type: NoiseReductionType) = audioStreamViewModel.setNsType(type)
    fun setEnableAGC(enabled: Boolean) = audioStreamViewModel.setEnableAGC(enabled)
    fun setAgcTargetLevel(level: Int) = audioStreamViewModel.setAgcTargetLevel(level)
    fun setEnableVAD(enabled: Boolean) = audioStreamViewModel.setEnableVAD(enabled)
    fun setVadThreshold(threshold: Int) = audioStreamViewModel.setVadThreshold(threshold)
    fun setEnableDereverb(enabled: Boolean) = audioStreamViewModel.setEnableDereverb(enabled)
    fun setDereverbLevel(level: Float) = audioStreamViewModel.setDereverbLevel(level)
    fun setAmplification(amp: Float) = audioStreamViewModel.setAmplification(amp)
    fun setAndroidAudioSource(sourceName: String) = audioStreamViewModel.setAndroidAudioSource(sourceName)
    fun setAutoConfig(enabled: Boolean) = audioStreamViewModel.setAutoConfig(enabled)
    fun setMonitoringPanelVisible(visible: Boolean) = audioStreamViewModel.setMonitoringPanelVisible(visible)
    fun dismissFirewallDialog() = audioStreamViewModel.dismissFirewallDialog()
    fun confirmAddFirewallRule() = audioStreamViewModel.confirmAddFirewallRule()
    fun dismissErrorDialog() = audioStreamViewModel.dismissErrorDialog()
    fun retryAfterError() = audioStreamViewModel.retryAfterError()
    
    // Settings methods
    fun setThemeMode(mode: ThemeMode) = settingsViewModel.setThemeMode(mode)
    fun setSeedColor(color: Long) = settingsViewModel.setSeedColor(color)
    fun setUseDynamicColor(enable: Boolean) = settingsViewModel.setUseDynamicColor(enable)
    fun setOledPureBlack(enabled: Boolean) = settingsViewModel.setOledPureBlack(enabled)
    fun setPaletteStyle(style: PaletteStyle) = settingsViewModel.setPaletteStyle(style)
    fun setUseExpressiveShapes(enabled: Boolean) = settingsViewModel.setUseExpressiveShapes(enabled)
    fun setLanguage(language: AppLanguage) = settingsViewModel.setLanguage(language)
    fun setAutoStart(enabled: Boolean) = settingsViewModel.setAutoStart(enabled)
    fun setEnableStreamingNotification(enabled: Boolean) {
        settingsViewModel.setEnableStreamingNotification(enabled)
        audioStreamViewModel.audioEngine.setStreamingNotificationEnabled(enabled)
    }
    fun setKeepScreenOn(enabled: Boolean) = settingsViewModel.setKeepScreenOn(enabled)
    fun setMinimizeToTray(enabled: Boolean) = settingsViewModel.setMinimizeToTray(enabled)
    fun setCloseAction(action: CloseAction) = settingsViewModel.setCloseAction(action)
    fun setShowCloseConfirmDialog(show: Boolean) = settingsViewModel.setShowCloseConfirmDialog(show)
    fun setRememberCloseAction(remember: Boolean) = settingsViewModel.setRememberCloseAction(remember)
    fun handleCloseRequest(onExit: () -> Unit, onHide: () -> Unit) = settingsViewModel.handleCloseRequest(onExit, onHide)
    fun confirmCloseAction(action: CloseAction, remember: Boolean, onExit: () -> Unit, onHide: () -> Unit) = 
        settingsViewModel.confirmCloseAction(action, remember, onExit, onHide)
    fun setPocketMode(enabled: Boolean) = settingsViewModel.setPocketMode(enabled)
    fun setVisualizerStyle(style: VisualizerStyle) = settingsViewModel.setVisualizerStyle(style)
    fun setFloatingWindowEnabled(enabled: Boolean) = settingsViewModel.setFloatingWindowEnabled(enabled)
    fun setUseSystemTitleBar(enabled: Boolean) = settingsViewModel.setUseSystemTitleBar(enabled)
    fun setAutoCheckUpdate(enabled: Boolean) = settingsViewModel.setAutoCheckUpdate(enabled)
    fun setUseMirrorDownload(enabled: Boolean) = settingsViewModel.setUseMirrorDownload(enabled)
    fun setMirrorCdk(cdk: String) = settingsViewModel.setMirrorCdk(cdk)
    fun confirmMirrorCdk(cdk: String) = settingsViewModel.confirmMirrorCdk(cdk)
    fun dismissMirrorCdkDialog() = settingsViewModel.dismissMirrorCdkDialog()
    fun setBackgroundImage(path: String?) = settingsViewModel.setBackgroundImage(path)
    fun setBackgroundBrightness(brightness: Float) = settingsViewModel.setBackgroundBrightness(brightness)
    fun setBackgroundBlur(blurRadius: Float) = settingsViewModel.setBackgroundBlur(blurRadius)
    fun setCardOpacity(opacity: Float) = settingsViewModel.setCardOpacity(opacity)
    fun setEnableHazeEffect(enabled: Boolean) = settingsViewModel.setEnableHazeEffect(enabled)
    fun clearBackgroundImage() = settingsViewModel.clearBackgroundImage()
    fun pickBackgroundImage() = settingsViewModel.pickBackgroundImage()
    fun showSnackbar(message: String) = settingsViewModel.showSnackbar(message)
    fun clearSnackbar() = settingsViewModel.clearSnackbar()
    fun dismissFirstLaunchDialog() = settingsViewModel.dismissFirstLaunchDialog()
    fun exportLog(onResult: (String?) -> Unit) = settingsViewModel.exportLog(onResult)
    
    fun setShowVBCableDialog(show: Boolean) {
        _uiState.update { it.copy(showVBCableDialog = show) }
    }
    
    fun checkVBCableOnStartup() {
        viewModelScope.launch {
            if (getPlatform().type == PlatformType.Desktop && isWindowsPlatform()) {
                if (!isVirtualDeviceInstalled()) {
                    val hasLaunched = settings.getBoolean("vbcable_dialog_shown", false)
                    if (!hasLaunched) {
                        settings.putBoolean("vbcable_dialog_shown", true)
                        _uiState.update { it.copy(showVBCableDialog = true) }
                    }
                }
            }
        }
    }
    
    fun startVBCableInstallation() {
        viewModelScope.launch {
            try {
                installVBCable()
            } catch (e: Exception) {
                Logger.e("MainViewModel", "VB-Cable installation failed: ${e.message}", e)
                showSnackbar(String.format(getString(Res.string.vbcableInstallFailed), e.message ?: ""))
            } finally {
                _uiState.update { it.copy(showVBCableDialog = false) }
            }
        }
    }
    
    // Plugin methods
    fun importPlugin(filePath: String, onResult: (Result<PluginInfo>) -> Unit) = 
        pluginViewModel.importPlugin(filePath, onResult)
    fun enablePlugin(pluginId: String) = pluginViewModel.enablePlugin(pluginId)
    fun disablePlugin(pluginId: String) = pluginViewModel.disablePlugin(pluginId)
    fun deletePlugin(pluginId: String) = pluginViewModel.deletePlugin(pluginId)
    fun getPluginUIProvider(pluginId: String): Any? = pluginViewModel.getPluginUIProvider(pluginId)
    fun getPluginSettingsProvider(pluginId: String): Any? = pluginViewModel.getPluginSettingsProvider(pluginId)
    fun showPluginSyncWarning(missingPlugins: List<MissingPluginInfo>) = 
        pluginViewModel.showPluginSyncWarning(missingPlugins)
    fun dismissPluginSyncWarning() = pluginViewModel.dismissPluginSyncWarning()
    
    // Update methods
    fun checkUpdateManual() {
        viewModelScope.launch {            _uiState.update { it.copy(snackbarMessage = getString(Res.string.checkingUpdate)) }
            updateViewModel.checkUpdateManual()
        }
    }
    fun downloadAndInstallUpdate(useMirror: Boolean = _uiState.value.useMirrorDownload) = updateViewModel.downloadAndInstallUpdate(useMirror)
    fun dismissUpdateDialog() = updateViewModel.dismissUpdateDialog()
    fun openGitHubRelease() = updateViewModel.openGitHubRelease()

    suspend fun getPeakLevel(seconds: Int = 3): Float = audioStreamViewModel.getPeakLevel(seconds)
    suspend fun getAverageRms(seconds: Int = 3): Float = audioStreamViewModel.getAverageRms(seconds)

    // Performance methods
    fun setPerformanceMode(mode: String) = audioStreamViewModel.setPerformanceMode(mode)
    fun setBufferSizeMultiplier(multiplier: Float) = audioStreamViewModel.setBufferSizeMultiplier(multiplier)

    fun clearInstallMessage() {
        _uiState.update { it.copy(installMessage = null) }
    }
}
