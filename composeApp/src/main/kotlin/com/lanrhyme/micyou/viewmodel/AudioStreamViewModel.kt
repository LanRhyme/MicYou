package com.lanrhyme.micyou.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import com.lanrhyme.micyou.audio.AudioEngine
import com.lanrhyme.micyou.audio.AudioFormat
import com.lanrhyme.micyou.audio.AudioLevelData
import com.lanrhyme.micyou.audio.AudioLevelHistory
import com.lanrhyme.micyou.audio.AudioMetrics
import com.lanrhyme.micyou.audio.ChannelCount
import com.lanrhyme.micyou.audio.SampleRate
import com.lanrhyme.micyou.network.ConnectionErrorDetails
import com.lanrhyme.micyou.network.ConnectionErrorHelper
import com.lanrhyme.micyou.network.DeviceDiscoveryManager
import com.lanrhyme.micyou.network.DiscoveredDevice
import com.lanrhyme.micyou.ui.MonitoringMetricsHistory
import com.lanrhyme.micyou.util.Constants
import com.lanrhyme.micyou.util.Logger
import com.lanrhyme.micyou.util.PerformanceConfig
import com.lanrhyme.micyou.audio.AudioEffectType
import com.lanrhyme.micyou.audio.EqualizerConfig

data class AudioStreamUiState(
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
    val nsType: NoiseReductionType = NoiseReductionType.Ulunas,
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

    // Performance Settings
    val performanceMode: String = "Default",
    val performanceConfig: PerformanceConfig = PerformanceConfig.DEFAULT,

    // Monitoring Panel State
    val showMonitoringPanel: Boolean = false
)

class AudioStreamViewModel : ViewModel() {
    private val _audioEngine = AudioEngine()
    val audioEngine: AudioEngine get() = _audioEngine
    private val _uiState = MutableStateFlow(AudioStreamUiState())
    val uiState: StateFlow<AudioStreamUiState> = _uiState.asStateFlow()

    // 音频电平相关
    val audioLevels = _audioEngine.audioLevels
    val rawSpectrum = _audioEngine.rawSpectrum
    val processedSpectrum = _audioEngine.processedSpectrum
    val audioLevelData = _audioEngine.audioLevelData
    val audioMetrics = _audioEngine.audioMetrics

    // 设备发现
    private val discoveryManager = DeviceDiscoveryManager()
    val discoveredDevices: StateFlow<List<DiscoveredDevice>> = discoveryManager.discoveredDevices
    val isDiscovering: StateFlow<Boolean> = discoveryManager.isDiscovering

    // 音频历史记录（用于可视化）
    private val audioLevelHistory = AudioLevelHistory(maxDurationSeconds = 10)
    private val _levelHistory = MutableStateFlow<List<AudioLevelHistory.AudioLevelSample>>(emptyList())
    val levelHistory: StateFlow<List<AudioLevelHistory.AudioLevelSample>> = _levelHistory.asStateFlow()

    // 监控指标历史记录
    private val metricsHistory = MonitoringMetricsHistory(maxSamples = 120) // 记录约 1 分钟的历史（500ms 间隔）
    private val _metricsHistoryFlow = MutableStateFlow<List<AudioMetrics>>(emptyList())
    val metricsHistoryFlow: StateFlow<List<AudioMetrics>> = _metricsHistoryFlow.asStateFlow()

    private val repo = com.lanrhyme.micyou.settings.SettingsRepository
    private var isStartStreamRequestPending = false

    init {
        loadSettings()
        setupAudioEngineObservers()
        if (_uiState.value.mode == ConnectionMode.Wifi) {
            discoveryManager.startDiscovery()
        }
    }

    private fun loadSettings() {
        val savedMode = repo.getConnectionMode()
        val savedProtocol = repo.getTransportProtocol()
        val savedIp = repo.getIpAddress()
        val savedPort = repo.getPort()
        val savedMonitoring = repo.getMonitoringEnabled()
        val savedSampleRate = repo.getSampleRate()
        val savedChannelCount = repo.getChannelCount()
        val savedAudioFormat = repo.getAudioFormat()
        val savedNS = repo.getEnableNS()
        val savedNSType = repo.getNsType()
        val savedAGC = repo.getEnableAGC()
        val savedAGCTarget = repo.getAgcTarget()
        val savedVAD = repo.getEnableVAD()
        val savedVADThreshold = repo.getVadThreshold()
        val savedDereverb = repo.getEnableDereverb()
        val savedDereverbLevel = repo.getDereverbLevel()
        val savedAmplification = repo.getAmplification()
        val savedNsIntensity = repo.getNsIntensity()
        val savedAgcAttackRate = repo.getAgcAttackRate()
        val savedAgcDecayRate = repo.getAgcDecayRate()
        val savedChain = repo.getProcessingChain()
        val savedAndroidAudioSourceName = repo.getAndroidAudioSource()
        val savedIsAutoConfig = repo.getIsAutoConfig()
        val savedPerformanceMode = repo.getPerformanceMode()
        val savedBufferSizeMultiplier = repo.getBufferSizeMultiplier()
        val savedEqualizerConfig = repo.getEqualizerConfig()

        _uiState.update {
            it.copy(
                mode = savedMode,
                transportProtocol = savedProtocol,
                ipAddress = savedIp,
                bindAddress = repo.getBindAddress(),
                isAutoBindAddress = repo.getIsAutoBindAddress(),
                port = savedPort,
                monitoringEnabled = savedMonitoring,
                sampleRate = savedSampleRate,
                channelCount = savedChannelCount,
                audioFormat = savedAudioFormat,
                enableNS = savedNS,
                nsType = savedNSType,
                enableAGC = savedAGC,
                agcTargetLevel = savedAGCTarget,
                enableVAD = savedVAD,
                vadThreshold = savedVADThreshold,
                enableDereverb = savedDereverb,
                dereverbLevel = savedDereverbLevel,
                amplification = savedAmplification,
                nsIntensity = savedNsIntensity,
                agcAttackRate = savedAgcAttackRate,
                agcDecayRate = savedAgcDecayRate,
                processingChain = savedChain,
                equalizerConfig = savedEqualizerConfig,
                androidAudioSourceName = savedAndroidAudioSourceName,
                isAutoConfig = savedIsAutoConfig,
                performanceMode = savedPerformanceMode,
                performanceConfig = PerformanceConfig.withBufferSizeMultiplier(savedBufferSizeMultiplier)
            )
        }

        // Apply auto config on startup if enabled
        if (savedIsAutoConfig) {
            applyAutoConfig()
        }

        _audioEngine.setMonitoring(savedMonitoring)
        updateAudioEngineConfig()
    }

    private fun setupAudioEngineObservers() {
        viewModelScope.launch {
            _audioEngine.streamState.collect { state ->
                _uiState.update { it.copy(streamState = state) }
                // 当停止时清空历史记录
                if (state == StreamState.Idle) {
                    audioLevelHistory.clear()
                    _levelHistory.value = emptyList()
                    metricsHistory.clear()
                    _metricsHistoryFlow.value = emptyList()
                }
            }
        }

        viewModelScope.launch {
            _audioEngine.lastError.collect { error ->
                if (error == "UDP_AUDIO_WARNING") {
                    _uiState.update { it.copy(showUdpWarningDialog = true) }
                } else {
                    _uiState.update { it.copy(errorMessage = error) }
                }
            }
        }

        viewModelScope.launch {
            _audioEngine.isMuted.collect { muted ->
                _uiState.update { it.copy(isMuted = muted) }
            }
        }

        // 监听音频电平数据并更新历史记录
        viewModelScope.launch {
            _audioEngine.audioLevelData.collect { levelData ->
                audioLevelHistory.addSample(levelData)
                _levelHistory.value = audioLevelHistory.getSamples()
            }
        }

        // 监听音频指标数据并更新历史记录
        viewModelScope.launch {
            _audioEngine.audioMetrics.collect { metrics ->
                if (metrics != null) {
                    metricsHistory.addSample(metrics)
                    _metricsHistoryFlow.value = metricsHistory.getSamples()
                }
            }
        }

        // Auto-start handled via MainViewModel
    }

    fun updateAudioEngineConfig() {
        val s = _uiState.value
        _audioEngine.updateConfig(
            enableNS = s.enableNS,
            nsType = s.nsType,
            nsIntensity = s.nsIntensity,
            enableAGC = s.enableAGC,
            agcTargetLevel = s.agcTargetLevel,
            agcAttackRate = s.agcAttackRate,
            agcDecayRate = s.agcDecayRate,
            enableVAD = s.enableVAD,
            vadThreshold = s.vadThreshold,
            enableDereverb = s.enableDereverb,
            dereverbLevel = s.dereverbLevel,
            amplification = s.amplification,
            processingChain = s.processingChain,
            equalizerConfig = s.equalizerConfig
        )
        _uiState.update { it.copy(audioConfigRevision = it.audioConfigRevision + 1) }
    }

    fun setEqualizerConfig(config: EqualizerConfig) {
        _uiState.update { it.copy(equalizerConfig = config) }
        repo.putEqualizerConfig(config)
        updateAudioEngineConfig()
    }

    private fun applyAutoConfig() {
        setSampleRate(SampleRate.Rate48000)
        setChannelCount(ChannelCount.Stereo)
        setAudioFormat(AudioFormat.PCM_16BIT)
    }

    fun toggleStream() {
        if (_uiState.value.streamState == StreamState.Streaming || _uiState.value.streamState == StreamState.Connecting) {
            stopStream()
        } else {
            startStream()
        }
    }

    fun toggleMute() {
        val newMuteState = !_uiState.value.isMuted
        viewModelScope.launch {
            _audioEngine.setMute(newMuteState)
        }
    }

    fun startStream() {
        if (isStartStreamRequestPending ||
            _uiState.value.streamState == StreamState.Streaming ||
            _uiState.value.streamState == StreamState.Connecting
        ) {
            Logger.d("AudioStreamViewModel", "Start stream request ignored: already starting or running")
            return
        }

        isStartStreamRequestPending = true
        viewModelScope.launch {
            try {
                startStreamInternal()
            } finally {
                isStartStreamRequestPending = false
            }
        }
    }

    private suspend fun startStreamInternal() {
        Logger.i("AudioStreamViewModel", "Starting stream")
        val mode = _uiState.value.mode
        val ip = _uiState.value.ipAddress

        // 端口验证：确保端口在有效范围内 (1-65535)
        val rawPort = _uiState.value.port.toIntOrNull()
        val port = when {
            rawPort == null -> {
                Logger.w(
                    "AudioStreamViewModel",
                    "Invalid port format: ${_uiState.value.port}, using default ${Constants.DEFAULT_TCP_PORT}"
                )
                Constants.DEFAULT_TCP_PORT
            }

            rawPort <= 0 || rawPort > 65535 -> {
                Logger.w(
                    "AudioStreamViewModel",
                    "Port out of range: $rawPort, using default ${Constants.DEFAULT_TCP_PORT}"
                )
                Constants.DEFAULT_TCP_PORT
            }

            else -> rawPort
        }

        // IP 地址验证
        if (ip.isBlank()) {
            Logger.e("AudioStreamViewModel", "IP address is empty")
            _uiState.update {
                it.copy(
                    streamState = StreamState.Error,
                    errorMessage = com.lanrhyme.micyou.util.getString(com.lanrhyme.micyou.R.string.errorIpAddressEmpty),
                    showErrorDialog = true
                )
            }
            return
        }
        // 基本的 IP 格式验证
        val ipRegex = Regex("^((25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\\.){3}(25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)$")
        if (!ipRegex.matches(ip) && !ip.startsWith("127.")) {
            Logger.w("AudioStreamViewModel", "IP address format may be invalid: $ip")
        }

        val sampleRate = _uiState.value.sampleRate
        val channelCount = _uiState.value.channelCount
        val audioFormat = _uiState.value.audioFormat

        _uiState.update {
            it.copy(
                streamState = StreamState.Connecting,
                errorMessage = null,
                showErrorDialog = false,
                errorDetails = null
            )
        }

        updateAudioEngineConfig()

        try {
            Logger.d("AudioStreamViewModel", "Calling _audioEngine.start()")
            _audioEngine.start(ip, port, mode, sampleRate, channelCount, audioFormat, _uiState.value.transportProtocol)
            Logger.i("AudioStreamViewModel", "Stream started successfully")
        } catch (e: kotlinx.coroutines.CancellationException) {
            Logger.i("AudioStreamViewModel", "Stream start cancelled by user")
            _uiState.update { it.copy(streamState = StreamState.Idle) }
            return
        } catch (e: Exception) {
            Logger.e("AudioStreamViewModel", "Failed to start stream", e)

            val errorType = ConnectionErrorHelper.analyzeError(e, mode)
            val language = repo.getLanguage()
            val errorDetails = ConnectionErrorHelper.generateErrorDetails(
                type = errorType,
                originalMessage = e.message ?: "Unknown error",
                mode = mode,
                port = port,
                ip = ip
            )

            _uiState.update {
                it.copy(
                    streamState = StreamState.Error,
                    errorMessage = errorDetails.localizedMessage,
                    showErrorDialog = true,
                    errorDetails = errorDetails
                )
            }
            return
        }
    }

    fun stopStream() {
        Logger.i("AudioStreamViewModel", "Stopping stream")
        _uiState.update { it.copy(streamState = StreamState.Idle) }
        _audioEngine.stop()
    }

    fun setMode(mode: ConnectionMode) {
        Logger.i("AudioStreamViewModel", "Setting connection mode to $mode")

        val current = _uiState.value

        val updatedPort = when (mode) {
            ConnectionMode.Usb -> {
                val parsed = current.port.toIntOrNull()
                if (parsed == null || parsed <= 0) Constants.DEFAULT_TCP_PORT.toString() else current.port
            }

            else -> current.port
        }

        // Auto-configure if enabled
        if (current.isAutoConfig) {
            applyAutoConfig()
        }

        _uiState.update { it.copy(mode = mode, port = updatedPort) }
        repo.putConnectionMode(mode)

        // Manage discovery lifecycle based on mode
        if (mode == ConnectionMode.Wifi) {
            discoveryManager.startDiscovery()
        } else {
            discoveryManager.stopDiscovery()
        }
        if (updatedPort != current.port) {
            repo.putPort(updatedPort)
        }
    }

    fun setTransportProtocol(protocol: TransportProtocol) {
        Logger.i("AudioStreamViewModel", "Setting transport protocol to $protocol")
        _uiState.update { it.copy(transportProtocol = protocol) }
        repo.putTransportProtocol(protocol)
    }

    fun setIp(ip: String, isAutoSelect: Boolean = false, restartStream: Boolean = false) {
        Logger.d("AudioStreamViewModel", "Setting IP to $ip, autoSelect=$isAutoSelect, restartStream=$restartStream")
        val wasRunning =
            _uiState.value.streamState == StreamState.Streaming || _uiState.value.streamState == StreamState.Connecting

        _uiState.update {
            it.copy(
                ipAddress = ip.ifBlank { _uiState.value.ipAddress }
            )
        }
        repo.putIpAddress(ip.ifBlank { _uiState.value.ipAddress })

        // 如果要求重启流（IP 切换时），先停止再启动
        if (restartStream && wasRunning) {
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    _audioEngine.stopAndWait()
                    startStreamInternal()
                } catch (e: Exception) {
                    Logger.e("AudioStreamViewModel", "Failed to restart stream after IP change", e)
                }
            }
        }
    }

    fun setPort(port: String) {
        // 验证端口输入
        val portInt = port.toIntOrNull()
        val validatedPort = when {
            port.isBlank() -> {
                // 空白输入时保持当前有效值，避免误重置为默认端口
                Logger.d("AudioStreamViewModel", "Port input is blank, keeping current value: ${_uiState.value.port}")
                _uiState.value.port
            }

            portInt == null -> {
                Logger.w("AudioStreamViewModel", "Invalid port format: $port, keeping current value")
                _uiState.value.port // 保持当前值
            }

            portInt <= 0 || portInt > 65535 -> {
                Logger.w("AudioStreamViewModel", "Port out of valid range (1-65535): $portInt, keeping current value")
                _uiState.value.port // 保持当前值
            }

            else -> port
        }

        Logger.d("AudioStreamViewModel", "Setting port to $validatedPort")
        _uiState.update { it.copy(port = validatedPort) }
        repo.putPort(validatedPort)
    }

    fun setMonitoringEnabled(enabled: Boolean) {
        _uiState.update { it.copy(monitoringEnabled = enabled) }
        repo.putMonitoringEnabled(enabled)
        _audioEngine.setMonitoring(enabled)
    }

    fun setSampleRate(rate: SampleRate) {
        _uiState.update { it.copy(sampleRate = rate) }
        repo.putSampleRate(rate)
    }

    fun setChannelCount(count: ChannelCount) {
        _uiState.update { it.copy(channelCount = count) }
        repo.putChannelCount(count)
    }

    fun setAudioFormat(format: AudioFormat) {
        _uiState.update { it.copy(audioFormat = format) }
        repo.putAudioFormat(format)
    }

    // --- Audio Processing Setters ---

    fun setAndroidAudioProcessing(enabled: Boolean) {
        _uiState.update { it.copy(enableNS = enabled, enableAGC = enabled) }
        repo.putEnableNS(enabled)
        repo.putEnableAGC(enabled)
        updateAudioEngineConfig()
    }

    fun setEnableNS(enabled: Boolean) {
        _uiState.update { it.copy(enableNS = enabled) }
        repo.putEnableNS(enabled)
        updateAudioEngineConfig()
    }

    fun setNsType(type: NoiseReductionType) {
        _uiState.update { it.copy(nsType = type) }
        repo.putNsType(type)
        updateAudioEngineConfig()
    }

    fun setEnableAGC(enabled: Boolean) {
        _uiState.update { it.copy(enableAGC = enabled) }
        repo.putEnableAGC(enabled)
        updateAudioEngineConfig()
    }

    fun setAgcTargetLevel(level: Int) {
        _uiState.update { it.copy(agcTargetLevel = level) }
        repo.putAgcTarget(level)
        updateAudioEngineConfig()
    }

    fun setEnableVAD(enabled: Boolean) {
        _uiState.update { it.copy(enableVAD = enabled) }
        repo.putEnableVAD(enabled)
        updateAudioEngineConfig()
    }

    fun setVadThreshold(threshold: Int) {
        _uiState.update { it.copy(vadThreshold = threshold) }
        repo.putVadThreshold(threshold)
        updateAudioEngineConfig()
    }

    fun setEnableDereverb(enabled: Boolean) {
        _uiState.update { it.copy(enableDereverb = enabled) }
        repo.putEnableDereverb(enabled)
        updateAudioEngineConfig()
    }

    fun setDereverbLevel(level: Float) {
        _uiState.update { it.copy(dereverbLevel = level) }
        repo.putDereverbLevel(level)
        updateAudioEngineConfig()
    }

    fun setNsIntensity(intensity: Float) {
        _uiState.update { it.copy(nsIntensity = intensity) }
        repo.putNsIntensity(intensity)
        updateAudioEngineConfig()
    }

    fun setAgcAttackRate(rate: Float) {
        _uiState.update { it.copy(agcAttackRate = rate) }
        repo.putAgcAttackRate(rate)
        updateAudioEngineConfig()
    }

    fun setAgcDecayRate(rate: Float) {
        _uiState.update { it.copy(agcDecayRate = rate) }
        repo.putAgcDecayRate(rate)
        updateAudioEngineConfig()
    }

    fun setProcessingChain(chain: List<AudioEffectType>) {
        _uiState.update { it.copy(processingChain = chain) }
        repo.putProcessingChain(chain)
        updateAudioEngineConfig()
    }

    fun setAmplification(amp: Float) {
        _uiState.update { it.copy(amplification = amp) }
        repo.putAmplification(amp)
        updateAudioEngineConfig()
    }

    fun setAndroidAudioSource(sourceName: String) {
        _uiState.update { it.copy(androidAudioSourceName = sourceName) }
        repo.putAndroidAudioSource(sourceName)
        _audioEngine.setAudioSource(sourceName)
    }

    fun setAutoConfig(enabled: Boolean) {
        _uiState.update { it.copy(isAutoConfig = enabled) }
        repo.putIsAutoConfig(enabled)
        if (enabled) {
            applyAutoConfig()
        }
    }

    fun setMonitoringPanelVisible(visible: Boolean) {
        _uiState.update { it.copy(showMonitoringPanel = visible) }
    }

    fun dismissErrorDialog() {
        _uiState.update { it.copy(showErrorDialog = false) }
    }

    fun dismissUdpWarningDialog() {
        _uiState.update { it.copy(showUdpWarningDialog = false) }
    }

    fun retryAfterError() {
        dismissErrorDialog()
        startStream()
    }

    // ==================== 性能配置方法 ====================

    /**
     * 设置性能模式
     */
    fun setPerformanceMode(mode: String) {
        val config = PerformanceConfig.fromMode(mode)

        _uiState.update {
            it.copy(
                performanceConfig = config,
                performanceMode = mode
            )
        }

        repo.putPerformanceMode(mode)
        _audioEngine.updatePerformanceConfig(config)
    }

    /**
     * 设置缓冲区大小倍数
     */
    fun setBufferSizeMultiplier(multiplier: Float) {
        val config = PerformanceConfig.withBufferSizeMultiplier(multiplier)

        _uiState.update {
            it.copy(
                performanceConfig = config
            )
        }

        repo.putBufferSizeMultiplier(multiplier)
        _audioEngine.updatePerformanceConfig(config)
    }

    /**
     * 获取峰值电平（最近N秒内）
     */
    suspend fun getPeakLevel(seconds: Int = 3): Float {
        return audioLevelHistory.getPeakInRange(seconds)
    }

    /**
     * 获取平均 RMS（最近N秒内）
     */
    suspend fun getAverageRms(seconds: Int = 3): Float {
        return audioLevelHistory.getAverageRms(seconds)
    }

    override fun onCleared() {
        super.onCleared()
        discoveryManager.stopDiscovery()
        _audioEngine.stop()
    }

    fun startDiscovery() {
        discoveryManager.startDiscovery()
    }

    fun stopDiscovery() {
        discoveryManager.stopDiscovery()
    }

    fun restartDiscovery() {
        discoveryManager.stopDiscovery()
        discoveryManager.startDiscovery()
    }

}
