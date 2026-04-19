package com.lanrhyme.micyou

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class AudioStreamUiState(
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
    val bluetoothAddress: String = "",
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

    // Performance Settings
    val performanceMode: String = "Default",
    val performanceConfig: PerformanceConfig = PerformanceConfig.DEFAULT
)

class AudioStreamViewModel : ViewModel() {
    private val _audioEngine = AudioEngine()
    val audioEngine: AudioEngine get() = _audioEngine
    private val _uiState = MutableStateFlow(AudioStreamUiState())
    val uiState: StateFlow<AudioStreamUiState> = _uiState.asStateFlow()

    // 音频电平相关
    val audioLevels = _audioEngine.audioLevels
    val audioLevelData = _audioEngine.audioLevelData
    val audioMetrics = _audioEngine.audioMetrics

    // 音频历史记录（用于可视化）
    private val audioLevelHistory = AudioLevelHistory(maxDurationSeconds = 10)
    private val _levelHistory = MutableStateFlow<List<AudioLevelHistory.AudioLevelSample>>(emptyList())
    val levelHistory: StateFlow<List<AudioLevelHistory.AudioLevelSample>> = _levelHistory.asStateFlow()

    private val settings = SettingsFactory.getSettings()

    init {
        loadSettings()
        setupAudioEngineObservers()
    }

    private fun loadSettings() {
        val savedModeName = settings.getString("connection_mode", ConnectionMode.Wifi.name)
        val savedMode = when (savedModeName) {
            "WifiUdp" -> ConnectionMode.Bluetooth
            else -> try { ConnectionMode.valueOf(savedModeName) } catch(e: Exception) { ConnectionMode.Wifi }
        }
        
        val savedIp = settings.getString("ip_address", "192.168.1.5")
        val savedPort = settings.getString("port", "6000")
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

        val savedAndroidAudioSourceName = settings.getString("android_audio_source", "Unprocessed")
        val savedBluetoothAddress = settings.getString("bluetooth_address", "")
        val savedIsAutoConfig = settings.getBoolean("is_auto_config", true)

        val savedPerformanceMode = settings.getString("performance_mode", "Default")
        val savedBufferSizeMultiplier = settings.getFloat("buffer_size_multiplier", 1.0f)

        _uiState.update {
            it.copy(
                mode = savedMode,
                ipAddress = savedIp,
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
                androidAudioSourceName = savedAndroidAudioSourceName,
                bluetoothAddress = savedBluetoothAddress,
                isAutoConfig = savedIsAutoConfig,
                performanceMode = savedPerformanceMode,
                performanceConfig = PerformanceConfig.withBufferSizeMultiplier(savedBufferSizeMultiplier)
            )
        }
        
        // Apply auto config on startup if enabled
        if (savedIsAutoConfig) {
            applyAutoConfig(savedMode)
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
                }
            }
        }

        viewModelScope.launch {
            _audioEngine.lastError.collect { error ->
                _uiState.update { it.copy(errorMessage = error) }
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
            enableAGC = s.enableAGC,
            agcTargetLevel = s.agcTargetLevel,
            enableVAD = s.enableVAD,
            vadThreshold = s.vadThreshold,
            enableDereverb = s.enableDereverb,
            dereverbLevel = s.dereverbLevel,
            amplification = s.amplification
        )
        _uiState.update { it.copy(audioConfigRevision = it.audioConfigRevision + 1) }
    }

    private fun applyAutoConfig(mode: ConnectionMode) {
        if (mode == ConnectionMode.Bluetooth) {
            // Low bandwidth optimization
            setSampleRate(SampleRate.Rate16000)
            setChannelCount(ChannelCount.Mono)
            setAudioFormat(AudioFormat.PCM_16BIT)
        } else {
            // High quality for WiFi/USB
            setSampleRate(SampleRate.Rate48000)
            setChannelCount(ChannelCount.Stereo)
            setAudioFormat(AudioFormat.PCM_16BIT)
        }
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
        val ip = if (mode == ConnectionMode.Bluetooth) _uiState.value.bluetoothAddress else _uiState.value.ipAddress

        // 端口验证：确保端口在有效范围内 (1-65535)
        val rawPort = _uiState.value.port.toIntOrNull()
        val port = when {
            rawPort == null -> {
                Logger.w("AudioStreamViewModel", "Invalid port format: ${_uiState.value.port}, using default 6000")
                6000
            }
            rawPort <= 0 || rawPort > 65535 -> {
                Logger.w("AudioStreamViewModel", "Port out of range: $rawPort, using default 6000")
                6000
            }
            else -> rawPort
        }

        // IP 地址验证（非蓝牙模式）
        if (mode != ConnectionMode.Bluetooth) {
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

        // 蓝牙地址验证（蓝牙模式）
        if (mode == ConnectionMode.Bluetooth) {
            if (ip.isBlank()) {
                Logger.e("AudioStreamViewModel", "Bluetooth address is empty")
                _uiState.update {
                    it.copy(
                        streamState = StreamState.Error,
                        errorMessage = "请选择蓝牙设备",
                        showErrorDialog = true
                    )
                }
                return
            }
            // MAC 地址格式验证
            val macRegex = Regex("^([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}$")
            if (!macRegex.matches(ip)) {
                Logger.w("AudioStreamViewModel", "Bluetooth MAC address format may be invalid: $ip")
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
                _audioEngine.start(ip, port, mode, isClient, sampleRate, channelCount, audioFormat)
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
                val strings = getStrings(language)
                val errorDetails = ConnectionErrorHelper.generateErrorDetails(
                    type = errorType,
                    originalMessage = e.message ?: "Unknown error",
                    errors = strings.errors,
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
                if (!isPortAllowed(port, "TCP")) {
                    Logger.w("AudioStreamViewModel", "Port $port is not allowed by firewall")
                    _uiState.update { it.copy(showFirewallDialog = true, pendingFirewallPort = port) }
                }
            }
        }
    }

    fun stopStream() {
        Logger.i("AudioStreamViewModel", "Stopping stream")
        _audioEngine.stop()
    }

    fun setMode(mode: ConnectionMode) {
        Logger.i("AudioStreamViewModel", "Setting connection mode to $mode")
        val platformType = getPlatform().type
        val current = _uiState.value

        val updatedPort = if (platformType == PlatformType.Android && mode == ConnectionMode.Usb) {
            val parsed = current.port.toIntOrNull()
            if (parsed == null || parsed <= 0) "6000" else current.port
        } else {
            current.port
        }
        
        // Auto-configure for Bluetooth to optimize bandwidth and stability
        if (current.isAutoConfig) {
             applyAutoConfig(mode)
        }

        _uiState.update { it.copy(mode = mode, port = updatedPort) }
        settings.putString("connection_mode", mode.name)
        if (updatedPort != current.port) {
            settings.putString("port", updatedPort)
        }
    }
    
    fun setIp(ip: String) {
        if (_uiState.value.mode == ConnectionMode.Bluetooth) {
            Logger.d("AudioStreamViewModel", "Setting Bluetooth address to $ip")
            _uiState.update { it.copy(bluetoothAddress = ip) }
            settings.putString("bluetooth_address", ip)
        } else {
            Logger.d("AudioStreamViewModel", "Setting IP to $ip")
            _uiState.update { it.copy(ipAddress = ip) }
            settings.putString("ip_address", ip)
        }
    }

    fun setPort(port: String) {
        // 验证端口输入
        val portInt = port.toIntOrNull()
        val validatedPort = when {
            port.isBlank() -> "6000" // 空值使用默认端口
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
            applyAutoConfig(_uiState.value.mode)
        }
    }

    fun dismissFirewallDialog() {
        _uiState.update { it.copy(showFirewallDialog = false, pendingFirewallPort = null) }
    }
    
    fun dismissErrorDialog() {
        _uiState.update { it.copy(showErrorDialog = false) }
    }
    
    fun retryAfterError() {
        dismissErrorDialog()
        startStream()
    }

    fun confirmAddFirewallRule() {
        val port = _uiState.value.pendingFirewallPort ?: return
        _uiState.update { it.copy(showFirewallDialog = false, pendingFirewallPort = null) }
        
        viewModelScope.launch {
            val result = addFirewallRule(port, "TCP")
            if (result.isSuccess) {
                Logger.i("AudioStreamViewModel", "Firewall rule added successfully")
                startStream() // 成功添加后重试启动串流
            } else {
                val error = result.exceptionOrNull()?.message ?: "Unknown error"
                Logger.e("AudioStreamViewModel", "Failed to add firewall rule: $error")
                _uiState.update { it.copy(errorMessage = "无法自动添加防火墙规则: $error\n请尝试以管理员身份运行程序，或手动在防火墙中放行 TCP $port 端口。") }
            }
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
    fun getPeakLevel(seconds: Int = 3): Float {
        return audioLevelHistory.getPeakInRange(seconds)
    }

    /**
     * 获取平均 RMS（最近N秒内）
     */
    fun getAverageRms(seconds: Int = 3): Float {
        return audioLevelHistory.getAverageRms(seconds)
    }

    override fun onCleared() {
        super.onCleared()
        _audioEngine.stop()
    }
}
