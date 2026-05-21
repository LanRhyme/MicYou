package com.lanrhyme.micyou

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import micyou.composeapp.generated.resources.*
import micyou.composeapp.generated.resources.Res
import org.jetbrains.compose.resources.getString

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
    val showFirewallDialog: Boolean = false,
    val pendingFirewallPort: Int? = null,

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
    val androidAudioSourceName: String = "Unprocessed",
    val audioConfigRevision: Int = 0,

    // Performance Settings
    val performanceMode: String = "Default",
    val performanceConfig: PerformanceConfig = PerformanceConfig.DEFAULT,

    // Monitoring Panel State
    val showMonitoringPanel: Boolean = false,

    // Web Mode State
    val webUrl: String = "",
    val webClientCount: Int = 0
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

    private val settings = SettingsFactory.getSettings()

    init {
        loadSettings()
        setupAudioEngineObservers()
        // Auto-start discovery on Android when in WiFi mode
        if (getPlatform().type == PlatformType.Android && _uiState.value.mode == ConnectionMode.Wifi) {
            discoveryManager.startDiscovery()
        }

        // Observe web mode state
        viewModelScope.launch {
            _audioEngine.webUrl.collect { url ->
                _uiState.update { it.copy(webUrl = url) }
            }
        }
        viewModelScope.launch {
            _audioEngine.webClientCount.collect { count ->
                _uiState.update { it.copy(webClientCount = count) }
            }
        }
    }

    private fun loadSettings() {
        val savedModeName = settings.getString("connection_mode", ConnectionMode.Wifi.name)
    val savedMode = when (savedModeName) {
            "WifiUdp" -> ConnectionMode.Wifi
            else -> try { ConnectionMode.valueOf(savedModeName) } catch(e: Exception) { ConnectionMode.Wifi }
        }
        val effectiveMode = if (getPlatform().type == PlatformType.Android && savedMode == ConnectionMode.Web) {
            ConnectionMode.Wifi
        } else {
            savedMode
        }
        val savedProtocolName = settings.getString("transport_protocol", TransportProtocol.Both.name)
        val savedProtocol = try { TransportProtocol.valueOf(savedProtocolName) } catch(e: Exception) { TransportProtocol.Both }
        val platform = getPlatform()
        val isDesktop = platform.type == PlatformType.Desktop
        val savedIp = settings.getString("ip_address", "192.168.1.5")
        val savedBindAddress = settings.getString("bind_address", "0.0.0.0")
        val savedSelectedIp = settings.getString("selected_ip_address", "")
        val defaultAutoBind = savedSelectedIp == "auto" ||
            (savedSelectedIp.isBlank() && (savedBindAddress.isBlank() || savedBindAddress == "0.0.0.0"))
        val savedAutoBindSetting = settings.getBoolean("is_auto_bind_address", defaultAutoBind)
        val manualDesktopIp = when {
            !isDesktop -> ""
            savedSelectedIp == "auto" || savedAutoBindSetting -> ""
            savedSelectedIp.isNotBlank() -> savedSelectedIp
            savedBindAddress.isNotBlank() && savedBindAddress != "0.0.0.0" -> savedBindAddress
            else -> ""
        }
        val effectiveIp = if (isDesktop) manualDesktopIp.ifBlank { platform.ipAddress } else savedIp
        val effectiveBindAddress = if (isDesktop && manualDesktopIp.isNotBlank()) manualDesktopIp else "0.0.0.0"
        val savedIsAutoBind = isDesktop && manualDesktopIp.isBlank()
    val savedPort = settings.getString("port", Constants.DEFAULT_TCP_PORT.toString())
    val savedMonitoring = false
        settings.putBoolean("monitoring_enabled", false)
    val savedSampleRateName = settings.getString("sample_rate", SampleRate.Rate48000.name)
    val savedSampleRate = try { SampleRate.valueOf(savedSampleRateName) } catch(e: Exception) { SampleRate.Rate48000 }
    val savedChannelCountName = settings.getString("channel_count", ChannelCount.Stereo.name)
    val savedChannelCount = try { ChannelCount.valueOf(savedChannelCountName) } catch(e: Exception) { ChannelCount.Stereo }
    val savedAudioFormatName = settings.getString("audio_format", AudioFormat.PCM_FLOAT.name)
    val savedAudioFormat = try { AudioFormat.valueOf(savedAudioFormatName) } catch(e: Exception) { AudioFormat.PCM_FLOAT }
    val savedNS = settings.getBoolean("enable_ns", false)
    val savedNSTypeName = settings.getString("ns_type", NoiseReductionType.Ulunas.name)
    val savedNSType = try { NoiseReductionType.valueOf(savedNSTypeName) } catch(e: Exception) { NoiseReductionType.Ulunas }
    val savedAGC = settings.getBoolean("enable_agc", false)
    val savedAGCTarget = settings.getInt("agc_target", 32000)
    val savedVAD = settings.getBoolean("enable_vad", false)
    val savedVADThreshold = settings.getInt("vad_threshold", 10)
    val savedDereverb = settings.getBoolean("enable_dereverb", false)
    val savedDereverbLevel = settings.getFloat("dereverb_level", 0.5f)
    val savedAmplification = settings.getFloat("amplification", 15.0f)
    val savedNsIntensity = settings.getFloat("ns_intensity", 1.0f)
    val savedAgcAttackRate = settings.getFloat("agc_attack_rate", 0.01f)
    val savedAgcDecayRate = settings.getFloat("agc_decay_rate", 0.005f)
    val savedChainStr = settings.getString("processing_chain", "")
    var savedChain = if (savedChainStr.isEmpty()) {
        listOf(
            AudioEffectType.NoiseReduction,
            AudioEffectType.Dereverb,
            AudioEffectType.Equalizer,
            AudioEffectType.Amplifier,
            AudioEffectType.AGC,
            AudioEffectType.VAD
        )
    } else {
        savedChainStr.split(",").mapNotNull { name ->
            AudioEffectType.entries.find { it.name == name }
        }.toMutableList().apply {
            if (!contains(AudioEffectType.Equalizer)) {
                // Insert Equalizer before Amplifier if it exists, otherwise before AGC/VAD
                val ampIndex = indexOf(AudioEffectType.Amplifier)
                if (ampIndex != -1) {
                    add(ampIndex, AudioEffectType.Equalizer)
                } else {
                    val lastSafeIndex = (size - 2).coerceAtLeast(0)
                    add(lastSafeIndex, AudioEffectType.Equalizer)
                }
            }
        }
    }

    val savedAndroidAudioSourceName = settings.getString("android_audio_source", "Unprocessed")
    val savedIsAutoConfig = settings.getBoolean("is_auto_config", true)
    val savedPerformanceMode = settings.getString("performance_mode", "Default")
    val savedBufferSizeMultiplier = settings.getFloat("buffer_size_multiplier", 1.0f)
    
    val savedEqEnabled = settings.getBoolean("equalizer_enabled", false)
    val savedEqPreAmp = settings.getFloat("equalizer_preamp", 0f)
    val savedEqGainsStr = settings.getString("equalizer_gains", "")
    val savedEqGains = if (savedEqGainsStr.isEmpty()) List(10) { 0f } else {
        savedEqGainsStr.split(",").mapNotNull { it.toFloatOrNull() }.takeIf { it.size == 10 } ?: List(10) { 0f }
    }
    val savedEqualizerConfig = EqualizerConfig(savedEqEnabled, savedEqGains, savedEqPreAmp)

        _uiState.update {
            it.copy(
                mode = effectiveMode,
                transportProtocol = savedProtocol,
                ipAddress = effectiveIp,
                bindAddress = effectiveBindAddress,
                isAutoBindAddress = savedIsAutoBind,
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

        if (getPlatform().type == PlatformType.Desktop) {
            if (_uiState.value.isAutoConfig && _uiState.value.mode == ConnectionMode.Wifi) {
                // Auto start is handled in MainViewModel for now
            }
        }
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
        settings.putBoolean("equalizer_enabled", config.enabled)
        settings.putFloat("equalizer_preamp", config.preAmp)
        settings.putString("equalizer_gains", config.gains.joinToString(","))
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
        Logger.i("AudioStreamViewModel", "Starting stream")
        val mode = _uiState.value.mode
        val isDesktop = getPlatform().type == PlatformType.Desktop
        val ip = if (isDesktop && _uiState.value.isAutoBindAddress) "0.0.0.0" else _uiState.value.ipAddress

        // 端口验证：确保端口在有效范围内 (1-65535)
        val rawPort = _uiState.value.port.toIntOrNull()
        val port = when {
            rawPort == null -> {
                Logger.w("AudioStreamViewModel", "Invalid port format: ${_uiState.value.port}, using default ${Constants.DEFAULT_TCP_PORT}")
                if (mode == ConnectionMode.Web) Constants.DEFAULT_WEB_PORT else Constants.DEFAULT_TCP_PORT
            }
            rawPort <= 0 || rawPort > 65535 -> {
                Logger.w("AudioStreamViewModel", "Port out of range: $rawPort, using default ${Constants.DEFAULT_TCP_PORT}")
                if (mode == ConnectionMode.Web) Constants.DEFAULT_WEB_PORT else Constants.DEFAULT_TCP_PORT
            }
            else -> rawPort
        }

        // IP 地址验证 (skip for Web mode)
        if (mode != ConnectionMode.Web) {
            if (ip.isBlank()) {
                Logger.e("AudioStreamViewModel", "IP address is empty")
                _uiState.update {
                    it.copy(
                        streamState = StreamState.Error,
                        errorMessage = "IP 地址不能为空",
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
        }
        val isClient = getPlatform().type == PlatformType.Android
        val sampleRate = _uiState.value.sampleRate
        val channelCount = _uiState.value.channelCount
        val audioFormat = _uiState.value.audioFormat

        _uiState.update { it.copy(streamState = StreamState.Connecting, errorMessage = null, showErrorDialog = false, errorDetails = null) }

        // 启动音频引擎（不阻塞）
        viewModelScope.launch {
            updateAudioEngineConfig()

            try {
                Logger.d("AudioStreamViewModel", "Calling _audioEngine.start()")
                _audioEngine.start(ip, port, mode, isClient, sampleRate, channelCount, audioFormat, _uiState.value.transportProtocol)
                Logger.i("AudioStreamViewModel", "Stream started successfully")
            } catch (e: Exception) {
                Logger.e("AudioStreamViewModel", "Failed to start stream", e)
                
                // 分析错误并生成详细错误信息
                val errorType = ConnectionErrorHelper.analyzeError(e, mode)
                val savedLanguageName = settings.getString("language", AppLanguage.System.name)
                val language = try {
                    AppLanguage.valueOf(savedLanguageName)
                } catch (ex: Exception) {
                    AppLanguage.System
                }
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
            }
        }

        // 异步检查防火墙（不阻塞启动）
        if (!isClient && mode == ConnectionMode.Wifi) {
            viewModelScope.launch {
                val tcpAllowed = isPortAllowed(port, "TCP")
                    val udpPort = calculateUdpPort(port)
                    val udpAllowed = isPortAllowed(udpPort, "UDP")
                if (!tcpAllowed || !udpAllowed) {
                    Logger.w("AudioStreamViewModel", "Port $port (TCP) or $udpPort (UDP) is not allowed by firewall")
                    _uiState.update { it.copy(showFirewallDialog = true, pendingFirewallPort = port) }
                }
            }
        }
    }

    fun stopStream() {
        Logger.i("AudioStreamViewModel", "Stopping stream")
        _uiState.update { it.copy(streamState = StreamState.Idle) }
        _audioEngine.stop()
    }

    fun setMode(mode: ConnectionMode) {
        Logger.i("AudioStreamViewModel", "Setting connection mode to $mode")
        val platformType = getPlatform().type

        if (platformType == PlatformType.Android && mode == ConnectionMode.Web) {
            Logger.w("AudioStreamViewModel", "Web mode is not supported on Android, ignoring")
            return
        }

        val current = _uiState.value

        val updatedPort = when {
            mode == ConnectionMode.Web -> {
                val parsed = current.port.toIntOrNull()
                if (parsed == null || parsed <= 0) Constants.DEFAULT_WEB_PORT.toString() else current.port
            }
            platformType == PlatformType.Android && mode == ConnectionMode.Usb -> {
                val parsed = current.port.toIntOrNull()
                if (parsed == null || parsed <= 0) Constants.DEFAULT_TCP_PORT.toString() else current.port
            }
            else -> current.port
        }
        
        // Auto-configure if enabled
        if (current.isAutoConfig) {
            if (mode == ConnectionMode.Web) {
                setSampleRate(SampleRate.Rate48000)
                setChannelCount(ChannelCount.Mono)
                setAudioFormat(AudioFormat.PCM_16BIT)
            } else {
                applyAutoConfig()
            }
        }

        _uiState.update { it.copy(mode = mode, port = updatedPort) }
        settings.putString("connection_mode", mode.name)

        // Manage discovery lifecycle based on mode
        if (getPlatform().type == PlatformType.Android) {
            if (mode == ConnectionMode.Wifi) {
                discoveryManager.startDiscovery()
            } else {
                discoveryManager.stopDiscovery()
            }
        }
        if (updatedPort != current.port) {
            settings.putString("port", updatedPort)
        }
    }

    fun setTransportProtocol(protocol: TransportProtocol) {
        Logger.i("AudioStreamViewModel", "Setting transport protocol to $protocol")
        _uiState.update { it.copy(transportProtocol = protocol) }
        settings.putString("transport_protocol", protocol.name)
    }
    
    fun setIp(ip: String, isAutoSelect: Boolean = false, restartStream: Boolean = false) {
        Logger.d("AudioStreamViewModel", "Setting IP to $ip, autoSelect=$isAutoSelect, restartStream=$restartStream")
        val wasRunning = _uiState.value.streamState == StreamState.Streaming || _uiState.value.streamState == StreamState.Connecting

        val isDesktop = getPlatform().type == PlatformType.Desktop

        if (isDesktop && isAutoSelect) {
            val autoIp = getPlatform().ipAddress
            _uiState.update {
                it.copy(
                    ipAddress = autoIp,
                    isAutoBindAddress = true,
                    bindAddress = "0.0.0.0"
                )
            }
            settings.putString("ip_address", autoIp)
            settings.putBoolean("is_auto_bind_address", true)
            settings.putString("bind_address", "0.0.0.0")
            settings.putString("selected_ip_address", "auto")
        } else {
            val selectedIp = ip.ifBlank { _uiState.value.ipAddress }
            _uiState.update {
                it.copy(
                    ipAddress = selectedIp,
                    isAutoBindAddress = if (isDesktop) false else it.isAutoBindAddress,
                    bindAddress = if (isDesktop) selectedIp else it.bindAddress
                )
            }
            settings.putString("ip_address", selectedIp)
            if (isDesktop) {
                settings.putBoolean("is_auto_bind_address", false)
                settings.putString("bind_address", selectedIp)
                settings.putString("selected_ip_address", selectedIp)
            }
        }

        // 如果要求重启流（IP 切换时），先停止再启动
        if (restartStream && wasRunning) {
            viewModelScope.launch {
                try {
                    _audioEngine.stopAndWait()
                    startStream()
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
            port.isBlank() -> Constants.DEFAULT_TCP_PORT.toString()
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
        settings.putString("port", validatedPort)
    }

    fun setMonitoringEnabled(enabled: Boolean) {
        _uiState.update { it.copy(monitoringEnabled = enabled) }
        settings.putBoolean("monitoring_enabled", enabled)
        _audioEngine.setMonitoring(enabled)
    }

    fun setSampleRate(rate: SampleRate) {
        _uiState.update { it.copy(sampleRate = rate) }
        settings.putString("sample_rate", rate.name)
    }

    fun setChannelCount(count: ChannelCount) {
        _uiState.update { it.copy(channelCount = count) }
        settings.putString("channel_count", count.name)
    }

    fun setAudioFormat(format: AudioFormat) {
        _uiState.update { it.copy(audioFormat = format) }
        settings.putString("audio_format", format.name)
    }
    
    // --- Audio Processing Setters ---

    fun setAndroidAudioProcessing(enabled: Boolean) {
        _uiState.update { it.copy(enableNS = enabled, enableAGC = enabled) }
        settings.putBoolean("enable_ns", enabled)
        settings.putBoolean("enable_agc", enabled)
        updateAudioEngineConfig()
    }

    fun setEnableNS(enabled: Boolean) {
        _uiState.update { it.copy(enableNS = enabled) }
        settings.putBoolean("enable_ns", enabled)
        updateAudioEngineConfig()
    }
    
    fun setNsType(type: NoiseReductionType) {
        _uiState.update { it.copy(nsType = type) }
        settings.putString("ns_type", type.name)
        updateAudioEngineConfig()
    }
    
    fun setEnableAGC(enabled: Boolean) {
        _uiState.update { it.copy(enableAGC = enabled) }
        settings.putBoolean("enable_agc", enabled)
        updateAudioEngineConfig()
    }
    
    fun setAgcTargetLevel(level: Int) {
        _uiState.update { it.copy(agcTargetLevel = level) }
        settings.putInt("agc_target", level)
        updateAudioEngineConfig()
    }
    
    fun setEnableVAD(enabled: Boolean) {
        _uiState.update { it.copy(enableVAD = enabled) }
        settings.putBoolean("enable_vad", enabled)
        updateAudioEngineConfig()
    }
    
    fun setVadThreshold(threshold: Int) {
        _uiState.update { it.copy(vadThreshold = threshold) }
        settings.putInt("vad_threshold", threshold)
        updateAudioEngineConfig()
    }
    
    fun setEnableDereverb(enabled: Boolean) {
        _uiState.update { it.copy(enableDereverb = enabled) }
        settings.putBoolean("enable_dereverb", enabled)
        updateAudioEngineConfig()
    }
    
    fun setDereverbLevel(level: Float) {
        _uiState.update { it.copy(dereverbLevel = level) }
        settings.putFloat("dereverb_level", level)
        updateAudioEngineConfig()
    }

    fun setNsIntensity(intensity: Float) {
        _uiState.update { it.copy(nsIntensity = intensity) }
        settings.putFloat("ns_intensity", intensity)
        updateAudioEngineConfig()
    }

    fun setAgcAttackRate(rate: Float) {
        _uiState.update { it.copy(agcAttackRate = rate) }
        settings.putFloat("agc_attack_rate", rate)
        updateAudioEngineConfig()
    }

    fun setAgcDecayRate(rate: Float) {
        _uiState.update { it.copy(agcDecayRate = rate) }
        settings.putFloat("agc_decay_rate", rate)
        updateAudioEngineConfig()
    }

    fun setProcessingChain(chain: List<AudioEffectType>) {
        _uiState.update { it.copy(processingChain = chain) }
        settings.putString("processing_chain", chain.joinToString(",") { it.name })
        updateAudioEngineConfig()
    }
    
    fun setAmplification(amp: Float) {
        _uiState.update { it.copy(amplification = amp) }
        settings.putFloat("amplification", amp)
        updateAudioEngineConfig()
    }

    fun setAndroidAudioSource(sourceName: String) {
        _uiState.update { it.copy(androidAudioSourceName = sourceName) }
        settings.putString("android_audio_source", sourceName)
        _audioEngine.setAudioSource(sourceName)
    }

    fun setAutoConfig(enabled: Boolean) {
        _uiState.update { it.copy(isAutoConfig = enabled) }
        settings.putBoolean("is_auto_config", enabled)
        if (enabled) {
            applyAutoConfig()
        }
    }

    fun setMonitoringPanelVisible(visible: Boolean) {
        _uiState.update { it.copy(showMonitoringPanel = visible) }
    }

    fun dismissFirewallDialog() {
        _uiState.update { it.copy(showFirewallDialog = false, pendingFirewallPort = null) }
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

    fun confirmAddFirewallRule() {
        val port = _uiState.value.pendingFirewallPort ?: return
        val udpPort = calculateUdpPort(port)
        _uiState.update { it.copy(showFirewallDialog = false, pendingFirewallPort = null) }
        
        viewModelScope.launch {
            // 同时添加 TCP 和 UDP 防火墙规则
            val tcpResult = addFirewallRule(port, "TCP")
    val udpResult = addFirewallRule(udpPort, "UDP")
            
            if (tcpResult.isSuccess && udpResult.isSuccess) {
                Logger.i("AudioStreamViewModel", "Firewall rules added successfully for TCP $port and UDP $udpPort")
            } else {
                val tcpError = tcpResult.exceptionOrNull()?.message ?: "Unknown error"
                val udpError = udpResult.exceptionOrNull()?.message ?: "Unknown error"
                Logger.w("AudioStreamViewModel", "Failed to add firewall rules: TCP=$tcpError, UDP=$udpError")
                // 防火墙规则添加失败不阻止音频流启动，仅显示警告
                _uiState.update { it.copy(errorMessage = "无法自动添加防火墙规则: TCP $tcpError\nUDP $udpError\n音频流仍可正常工作。如需外部设备连接，请以管理员身份运行程序，或手动在防火墙中放行 TCP $port 和 UDP $udpPort 端口。") }
            }
            
            // 无论防火墙规则是否添加成功，都尝试启动音频流
            startStream()
        }
    }

    // ==================== 性能配置方法 ====================

    /**
     * 设置性能模式
     */
    fun setPerformanceMode(mode: String) {
        val config = PerformanceConfig.fromMode(mode)

        _uiState.update { it.copy(
            performanceConfig = config,
            performanceMode = mode
        ) }

        settings.putString("performance_mode", mode)
        _audioEngine.updatePerformanceConfig(config)
    }

    /**
     * 设置缓冲区大小倍数
     */
    fun setBufferSizeMultiplier(multiplier: Float) {
        val config = PerformanceConfig.withBufferSizeMultiplier(multiplier)

        _uiState.update { it.copy(
            performanceConfig = config
        ) }

        settings.putFloat("buffer_size_multiplier", multiplier)
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
        if (getPlatform().type == PlatformType.Android) discoveryManager.startDiscovery()
    }
    fun stopDiscovery() {
        if (getPlatform().type == PlatformType.Android) discoveryManager.stopDiscovery()
    }
    fun restartDiscovery() {
        if (getPlatform().type == PlatformType.Android) {
            discoveryManager.stopDiscovery()
            discoveryManager.startDiscovery()
        }
    }

}
