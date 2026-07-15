package com.lanrhyme.micyou.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lanrhyme.micyou.theme.PaletteStyle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import com.lanrhyme.micyou.audio.AudioEngine
import com.lanrhyme.micyou.audio.AudioFormat
import com.lanrhyme.micyou.audio.AudioLevelData
import com.lanrhyme.micyou.audio.AudioMetrics
import com.lanrhyme.micyou.audio.ChannelCount
import com.lanrhyme.micyou.audio.SampleRate
import com.lanrhyme.micyou.network.ConnectionErrorDetails
import com.lanrhyme.micyou.network.DiscoveredDevice
import com.lanrhyme.micyou.settings.SettingsViewModel
import com.lanrhyme.micyou.theme.ThemeMode
import com.lanrhyme.micyou.ui.background.BackgroundSettings
import com.lanrhyme.micyou.update.UpdateCheckResult
import com.lanrhyme.micyou.update.UpdateInfo
import com.lanrhyme.micyou.update.UpdateViewModel
import com.lanrhyme.micyou.util.AppLanguage
import com.lanrhyme.micyou.util.Constants
import com.lanrhyme.micyou.util.getString
import com.lanrhyme.micyou.audio.AudioEffectType
import com.lanrhyme.micyou.audio.EqualizerConfig

import com.lanrhyme.micyou.R

enum class ConnectionMode(val label: String) {
    Wifi("Wi-Fi"),
    Usb("USB (ADB)")
}

enum class TransportProtocol(val label: String) {
    Tcp("TCP"),
    Both("TCP+UDP")
}

enum class StreamState {
    Idle, Connecting, Streaming, Error
}

enum class NoiseReductionType(val label: String) {
    PureVox("PureVox (ONNX)"),
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
    val transportProtocol: TransportProtocol = TransportProtocol.Both,
    val streamState: StreamState = StreamState.Idle,
    val ipAddress: String = "192.168.1.5",
    val bindAddress: String = "0.0.0.0",
    val isAutoBindAddress: Boolean = true,
    val port: String = Constants.DEFAULT_TCP_PORT.toString(),
    val errorMessage: String? = null,
    val monitoringEnabled: Boolean = false,
    val sampleRate: SampleRate = SampleRate.Rate48000,
    val channelCount: ChannelCount = ChannelCount.Stereo,
    val audioFormat: AudioFormat = AudioFormat.PCM_FLOAT,
    val isMuted: Boolean = false,
    val isAutoConfig: Boolean = true,

    // Error Dialog State
    val showErrorDialog: Boolean = false,
    val errorDetails: ConnectionErrorDetails? = null,

    // UDP Warning Dialog State
    val showUdpWarningDialog: Boolean = false,

    // Audio Processing Settings
    val enableNS: Boolean = false,
    val nsType: NoiseReductionType = NoiseReductionType.PureVox,
    val enableAGC: Boolean = false,
    val agcTargetLevel: Int = 32000,
    val agcAttackRate: Float = 0.01f,
    val agcDecayRate: Float = 0.005f,
    val enableVAD: Boolean = false,
    val vadThreshold: Int = 10,
    val enableDereverb: Boolean = false,
    val dereverbLevel: Float = 0.5f,
    val amplification: Float = 15.0f,
    val nsIntensity: Float = 1.0f,
    val equalizerConfig: EqualizerConfig = EqualizerConfig(),
    val processingChain: List<AudioEffectType> = listOf(
        AudioEffectType.NoiseReduction,
        AudioEffectType.Dereverb,
        AudioEffectType.Equalizer,
        AudioEffectType.Amplifier,
        AudioEffectType.AGC,
        AudioEffectType.VAD
    ),
    val androidAudioSourceName: String = "Mic",
    val audioConfigRevision: Int = 0,

    // Settings State
    val themeMode: ThemeMode = ThemeMode.System,
    val seedColor: Long = 0xFF4A672D,
    val useDynamicColor: Boolean = false,
    val oledPureBlack: Boolean = false,
    val paletteStyle: PaletteStyle = PaletteStyle.TonalSpot,
    val useExpressiveShapes: Boolean = true,
    val language: AppLanguage = AppLanguage.System,
    val autoStart: Boolean = false,
    val enableStreamingNotification: Boolean = true,
    val keepScreenOn: Boolean = false,
    val autoCheckUpdate: Boolean = true,
    val useMirrorDownload: Boolean = false,
    val mirrorCdk: String = "",
    val showMirrorCdkDialog: Boolean = false,
    val visualizerStyle: VisualizerStyle = VisualizerStyle.VolumeRing,
    val backgroundSettings: BackgroundSettings = BackgroundSettings(),
    val showFirstLaunchDialog: Boolean = false,


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


/**
 * Main ViewModel - Coordinates between specialized ViewModels
 * This ViewModel acts as a facade for the UI layer
 */
class MainViewModel : ViewModel() {
    // Specialized ViewModels
    private val audioStreamViewModel = AudioStreamViewModel()
    private val settingsViewModel = SettingsViewModel()

    private val updateViewModel = UpdateViewModel()

    private val _uiState = MutableStateFlow(AppUiState())
    val uiState: StateFlow<AppUiState> = _uiState.asStateFlow()

    // Expose audio levels from AudioStreamViewModel
    val audioLevels = audioStreamViewModel.audioLevels
    val rawSpectrum = audioStreamViewModel.rawSpectrum
    val processedSpectrum = audioStreamViewModel.processedSpectrum
    val audioLevelData = audioStreamViewModel.audioLevelData
    val audioMetricsFlow = audioStreamViewModel.audioMetrics
    val levelHistory = audioStreamViewModel.levelHistory

    private val repo = com.lanrhyme.micyou.settings.SettingsRepository

    init {
        // Initialize from settings
        val initialLanguage = repo.getLanguage()

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
        if (repo.getAutoCheckUpdate()) {
            updateViewModel.checkUpdateAuto()
        }

        // Observe update check results for user feedback
        viewModelScope.launch {
            updateViewModel.checkResultFlow.collect { result ->
                result?.let {
                    val message = when (it) {
                        is UpdateCheckResult.UpdateAvailable -> String.format(
                            getString(R.string.updateAvailableMsg),
                            it.info.versionName
                        )

                        is UpdateCheckResult.NoUpdate -> getString(R.string.isLatestVersion)
                        is UpdateCheckResult.Error -> String.format(getString(R.string.updateCheckFailed), it.message)
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
                updateViewModel.uiState
            ) { audioData, settingsState, updateState ->
                val audioState = audioData.state
                val currentMetrics = audioData.metrics
                val history = audioData.history

                _uiState.update { current ->
                    current.copy(
                        mode = audioState.mode,
                        transportProtocol = audioState.transportProtocol,
                        streamState = audioState.streamState,
                        ipAddress = audioState.ipAddress,
                        bindAddress = audioState.bindAddress,
                        isAutoBindAddress = audioState.isAutoBindAddress,
                        port = audioState.port,
                        errorMessage = audioState.errorMessage,
                        monitoringEnabled = audioState.monitoringEnabled,
                        sampleRate = audioState.sampleRate,
                        channelCount = audioState.channelCount,
                        audioFormat = audioState.audioFormat,
                        isMuted = audioState.isMuted,
                        isAutoConfig = audioState.isAutoConfig,
                        showErrorDialog = audioState.showErrorDialog,
                        errorDetails = audioState.errorDetails,
                        showUdpWarningDialog = audioState.showUdpWarningDialog,
                        enableNS = audioState.enableNS,
                        nsType = audioState.nsType,
                        enableAGC = audioState.enableAGC,
                        agcTargetLevel = audioState.agcTargetLevel,
                        agcAttackRate = audioState.agcAttackRate,
                        agcDecayRate = audioState.agcDecayRate,
                        enableVAD = audioState.enableVAD,
                        vadThreshold = audioState.vadThreshold,
                        enableDereverb = audioState.enableDereverb,
                        dereverbLevel = audioState.dereverbLevel,
                        amplification = audioState.amplification,
                        nsIntensity = audioState.nsIntensity,
                        processingChain = audioState.processingChain,
                        equalizerConfig = audioState.equalizerConfig,
                        androidAudioSourceName = audioState.androidAudioSourceName,
                        audioConfigRevision = audioState.audioConfigRevision,
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
                        autoCheckUpdate = settingsState.autoCheckUpdate,
                        useMirrorDownload = settingsState.useMirrorDownload,
                        mirrorCdk = settingsState.mirrorCdk,
                        showMirrorCdkDialog = settingsState.showMirrorCdkDialog,
                        visualizerStyle = settingsState.visualizerStyle,
                        backgroundSettings = settingsState.backgroundSettings,
                        showFirstLaunchDialog = settingsState.showFirstLaunchDialog,

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
                // No-op: state merged from specialized ViewModels
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
    fun setTransportProtocol(protocol: TransportProtocol) = audioStreamViewModel.setTransportProtocol(protocol)
    fun startDiscovery() = audioStreamViewModel.startDiscovery()
    fun stopDiscovery() = audioStreamViewModel.stopDiscovery()
    fun restartDiscovery() = audioStreamViewModel.restartDiscovery()
    fun selectDiscoveredDevice(device: DiscoveredDevice) {
        audioStreamViewModel.setIp(device.hostAddress)
        audioStreamViewModel.setPort(device.port.toString())
    }

    fun setIp(ip: String, isAutoSelect: Boolean = false, restartStream: Boolean = false) =
        audioStreamViewModel.setIp(ip, isAutoSelect, restartStream)

    fun setPort(port: String) = audioStreamViewModel.setPort(port)
    fun setMonitoringEnabled(enabled: Boolean) = audioStreamViewModel.setMonitoringEnabled(enabled)
    fun setSampleRate(rate: SampleRate) = audioStreamViewModel.setSampleRate(rate)
    fun setChannelCount(count: ChannelCount) = audioStreamViewModel.setChannelCount(count)
    fun setAudioFormat(format: AudioFormat) = audioStreamViewModel.setAudioFormat(format)
    fun setAndroidAudioProcessing(enabled: Boolean) = audioStreamViewModel.setAndroidAudioProcessing(enabled)
    fun setEnableNS(enabled: Boolean) = audioStreamViewModel.setEnableNS(enabled)
    fun setNsType(type: NoiseReductionType) = audioStreamViewModel.setNsType(type)
    fun setNsIntensity(intensity: Float) = audioStreamViewModel.setNsIntensity(intensity)
    fun setEnableAGC(enabled: Boolean) = audioStreamViewModel.setEnableAGC(enabled)
    fun setAgcTargetLevel(level: Int) = audioStreamViewModel.setAgcTargetLevel(level)
    fun setAgcAttackRate(rate: Float) = audioStreamViewModel.setAgcAttackRate(rate)
    fun setAgcDecayRate(rate: Float) = audioStreamViewModel.setAgcDecayRate(rate)
    fun setEnableVAD(enabled: Boolean) = audioStreamViewModel.setEnableVAD(enabled)
    fun setVadThreshold(threshold: Int) = audioStreamViewModel.setVadThreshold(threshold)
    fun setEnableDereverb(enabled: Boolean) = audioStreamViewModel.setEnableDereverb(enabled)
    fun setDereverbLevel(level: Float) = audioStreamViewModel.setDereverbLevel(level)
    fun setEqualizerConfig(config: EqualizerConfig) = audioStreamViewModel.setEqualizerConfig(config)
    fun setProcessingChain(chain: List<AudioEffectType>) = audioStreamViewModel.setProcessingChain(chain)
    fun setAmplification(amp: Float) = audioStreamViewModel.setAmplification(amp)
    fun setAndroidAudioSource(sourceName: String) = audioStreamViewModel.setAndroidAudioSource(sourceName)
    fun setAutoConfig(enabled: Boolean) = audioStreamViewModel.setAutoConfig(enabled)
    fun setMonitoringPanelVisible(visible: Boolean) = audioStreamViewModel.setMonitoringPanelVisible(visible)
    fun dismissErrorDialog() = audioStreamViewModel.dismissErrorDialog()
    fun dismissUdpWarningDialog() = audioStreamViewModel.dismissUdpWarningDialog()
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
    fun setVisualizerStyle(style: VisualizerStyle) = settingsViewModel.setVisualizerStyle(style)
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


    // Update methods
    fun checkUpdateManual() {
        viewModelScope.launch {
            _uiState.update { it.copy(snackbarMessage = getString(R.string.checkingUpdate)) }
            updateViewModel.checkUpdateManual()
        }
    }

    fun downloadAndInstallUpdate(useMirror: Boolean = _uiState.value.useMirrorDownload) =
        updateViewModel.downloadAndInstallUpdate(useMirror)

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
