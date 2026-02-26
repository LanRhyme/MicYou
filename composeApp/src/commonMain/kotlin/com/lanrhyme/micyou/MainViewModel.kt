package com.lanrhyme.micyou

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class ConnectionMode(val label: String) {
    Wifi( "Wi-Fi (TCP)"),
    Bluetooth("Bluetooth"),
    Usb("USB (ADB)")
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

data class AppUiState(
    val mode: ConnectionMode = ConnectionMode.Wifi,
    val streamState: StreamState = StreamState.Idle,
    val ipAddress: String = "192.168.1.5", // 默认 IP
    val port: String = "6000",
    val errorMessage: String? = null,
    val themeMode: ThemeMode = ThemeMode.System,
    val seedColor: Long = 0xFF4285F4, // Google Blue - 默认主题色
    val monitoringEnabled: Boolean = false,
    val sampleRate: SampleRate = SampleRate.Rate44100,
    val channelCount: ChannelCount = ChannelCount.Mono,
    val audioFormat: AudioFormat = AudioFormat.PCM_16BIT,
    val installMessage: String? = null,
    
    // Audio Processing Settings
    val enableNS: Boolean = false,
    val nsType: NoiseReductionType = NoiseReductionType.RNNoise, // 默认使用 RNNoise
    
    val enableAGC: Boolean = false,
    val agcTargetLevel: Int = 32000,
    
    val enableVAD: Boolean = false,
    val vadThreshold: Int = 10,
    
    val enableDereverb: Boolean = false,
    val dereverbLevel: Float = 0.5f,
    
    val amplification: Float = 10.0f,

    val audioConfigRevision: Int = 0,

    val enableStreamingNotification: Boolean = true,
    
    val autoStart: Boolean = false,
    
    val isMuted: Boolean = false,
    val language: AppLanguage = AppLanguage.System,
    val useDynamicColor: Boolean = false,
    val bluetoothAddress: String = "",
    val isAutoConfig: Boolean = true,
    val snackbarMessage: String? = null,
    val showFirewallDialog: Boolean = false,
    val pendingFirewallPort: Int? = null,
    val minimizeToTray: Boolean = true,
    val closeAction: CloseAction = CloseAction.Prompt,
    val showCloseConfirmDialog: Boolean = false,
    val rememberCloseAction: Boolean = false,
    val newVersionAvailable: GitHubRelease? = null,
    val pocketMode: Boolean = true
)

enum class CloseAction(val label: String) {
    Prompt("prompt"),
    Minimize("minimize"),
    Exit("exit")
}

class MainViewModel : ViewModel() {
    private val audioEngine = AudioEngine()
    private val _uiState = MutableStateFlow(AppUiState())
    val uiState: StateFlow<AppUiState> = _uiState.asStateFlow()
    val audioLevels = audioEngine.audioLevels
    private val settings = SettingsFactory.getSettings()
    private val updateChecker = UpdateChecker()

    init {
        // Load settings
        val savedModeName = settings.getString("connection_mode", ConnectionMode.Wifi.name)
        val savedMode = when (savedModeName) {
            "WifiUdp" -> ConnectionMode.Bluetooth
            else -> try { ConnectionMode.valueOf(savedModeName) } catch(e: Exception) { ConnectionMode.Wifi }
        }
        
        val savedIp = settings.getString("ip_address", "192.168.1.5")
        val savedPort = settings.getString("port", "6000")
        
        val savedThemeModeName = settings.getString("theme_mode", ThemeMode.System.name)
        val savedThemeMode = try { ThemeMode.valueOf(savedThemeModeName) } catch(e: Exception) { ThemeMode.System }
        
        val savedSeedColor = settings.getLong("seed_color", 0xFF4285F4)
        
        val savedMonitoring = settings.getBoolean("monitoring_enabled", false)

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
        
        val savedAmplification = settings.getFloat("amplification", 10.0f)

        val savedEnableStreamingNotification = settings.getBoolean("enable_streaming_notification", true)
        
        val savedAutoStart = settings.getBoolean("auto_start", false)

        val savedLanguageName = settings.getString("language", AppLanguage.System.name)
        val savedLanguage = try { AppLanguage.valueOf(savedLanguageName) } catch(e: Exception) { AppLanguage.System }

        val savedUseDynamicColor = settings.getBoolean("use_dynamic_color", false)
        val savedBluetoothAddress = settings.getString("bluetooth_address", "")
        val savedIsAutoConfig = settings.getBoolean("is_auto_config", true)
        val savedMinimizeToTray = settings.getBoolean("minimize_to_tray", true)
        val savedCloseActionName = settings.getString("close_action", CloseAction.Prompt.name)
        val savedCloseAction = try {
            CloseAction.valueOf(savedCloseActionName)
        } catch (e: Exception) {
            CloseAction.Prompt
        }
        val savedPocketMode = settings.getBoolean("pocket_mode", true)

        _uiState.update { 
            it.copy(
                mode = savedMode,
                ipAddress = savedIp,
                port = savedPort,
                themeMode = savedThemeMode,
                seedColor = savedSeedColor,
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
                autoStart = savedAutoStart,
                enableStreamingNotification = savedEnableStreamingNotification,
                language = savedLanguage,
                useDynamicColor = savedUseDynamicColor,
                bluetoothAddress = savedBluetoothAddress,
                isAutoConfig = savedIsAutoConfig,
                minimizeToTray = savedMinimizeToTray,
                closeAction = savedCloseAction,
                pocketMode = savedPocketMode
            ) 
        }
        
        // Apply auto config on startup if enabled
        if (savedIsAutoConfig) {
            applyAutoConfig(savedMode)
        }
        
        audioEngine.setMonitoring(savedMonitoring)
        audioEngine.setStreamingNotificationEnabled(savedEnableStreamingNotification)
        updateAudioEngineConfig()

        viewModelScope.launch {
            audioEngine.streamState.collect { state ->
                _uiState.update { it.copy(streamState = state) }
            }
        }
        
        viewModelScope.launch {
            audioEngine.lastError.collect { error ->
                _uiState.update { it.copy(errorMessage = error) }
            }
        }
        
        viewModelScope.launch {
            audioEngine.installProgress.collect { msg ->
                _uiState.update { it.copy(installMessage = msg) }
            }
        }
        
        viewModelScope.launch {
            audioEngine.isMuted.collect { muted ->
                _uiState.update { it.copy(isMuted = muted) }
            }
        }

        if (getPlatform().type == PlatformType.Desktop) {
            viewModelScope.launch {
                audioEngine.installDriver()
            }
            if (savedAutoStart) {
                startStream()
            }
        }

        viewModelScope.launch {
            val result = updateChecker.checkUpdate()
            result.onSuccess { release ->
                if (release != null) {
                    _uiState.update { it.copy(newVersionAvailable = release) }
                }
            }
        }
    }

    fun dismissUpdateDialog() {
        _uiState.update { it.copy(newVersionAvailable = null) }
    }

    fun checkUpdateManual() {
        viewModelScope.launch {
            val strings = getStrings(_uiState.value.language)
            _uiState.update { it.copy(snackbarMessage = strings.checkingUpdate) }
            val result = updateChecker.checkUpdate()
            
            result.onSuccess { release ->
                if (release != null) {
                    _uiState.update { it.copy(newVersionAvailable = release, snackbarMessage = null) }
                } else {
                    _uiState.update { it.copy(snackbarMessage = strings.isLatestVersion) }
                }
            }.onFailure { e ->
                _uiState.update { it.copy(snackbarMessage = strings.updateCheckFailed.format(e.message)) }
            }
        }
    }
    
    private fun updateAudioEngineConfig() {
        val s = _uiState.value
        audioEngine.updateConfig(
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

    fun setAutoConfig(enabled: Boolean) {
        _uiState.update { it.copy(isAutoConfig = enabled) }
        settings.putBoolean("is_auto_config", enabled)
        if (enabled) {
            applyAutoConfig(_uiState.value.mode)
        }
    }

    fun setMinimizeToTray(enabled: Boolean) {
        _uiState.update { it.copy(minimizeToTray = enabled) }
        settings.putBoolean("minimize_to_tray", enabled)
    }

    fun setCloseAction(action: CloseAction) {
        _uiState.update { it.copy(closeAction = action) }
        settings.putString("close_action", action.name)
    }

    fun setShowCloseConfirmDialog(show: Boolean) {
        _uiState.update { it.copy(showCloseConfirmDialog = show) }
    }

    fun setRememberCloseAction(remember: Boolean) {
        _uiState.update { it.copy(rememberCloseAction = remember) }
    }

    fun setPocketMode(enabled: Boolean) {
        _uiState.update { it.copy(pocketMode = enabled) }
        settings.putBoolean("pocket_mode", enabled)
    }

    fun handleCloseRequest(onExit: () -> Unit, onHide: () -> Unit) {
        val state = _uiState.value
        when (state.closeAction) {
            CloseAction.Prompt -> {
                _uiState.update { it.copy(showCloseConfirmDialog = true) }
            }
            CloseAction.Minimize -> onHide()
            CloseAction.Exit -> onExit()
        }
    }

    fun confirmCloseAction(action: CloseAction, remember: Boolean, onExit: () -> Unit, onHide: () -> Unit) {
        if (remember) {
            setCloseAction(action)
        }
        _uiState.update { it.copy(showCloseConfirmDialog = false) }
        // 关键：延迟或在状态更新后执行回调，确保 UI 线程安全
        if (action == CloseAction.Minimize) {
            onHide()
        } else {
            onExit()
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
            audioEngine.setMute(newMuteState)
        }
    }

    fun startStream() {
        Logger.i("MainViewModel", "Starting stream")
        val mode = _uiState.value.mode
        val ip = if (mode == ConnectionMode.Bluetooth) _uiState.value.bluetoothAddress else _uiState.value.ipAddress
        val port = _uiState.value.port.toIntOrNull() ?: 6000
        val isClient = getPlatform().type == PlatformType.Android
        val sampleRate = _uiState.value.sampleRate
        val channelCount = _uiState.value.channelCount
        val audioFormat = _uiState.value.audioFormat

        _uiState.update { it.copy(streamState = StreamState.Connecting, errorMessage = null) }

        // 启动音频引擎（不阻塞）
        viewModelScope.launch {
            // Config is already updated via updateAudioEngineConfig, but we pass params to start just in case or for init
            updateAudioEngineConfig()

            try {
                Logger.d("MainViewModel", "Calling audioEngine.start()")
                audioEngine.start(ip, port, mode, isClient, sampleRate, channelCount, audioFormat)
                Logger.i("MainViewModel", "Stream started successfully")
            } catch (e: Exception) {
                Logger.e("MainViewModel", "Failed to start stream", e)
                _uiState.update { it.copy(streamState = StreamState.Error, errorMessage = e.message) }
            }
        }

        // 异步检查防火墙（不阻塞启动）
        if (!isClient && mode == ConnectionMode.Wifi) {
            viewModelScope.launch {
                if (!isPortAllowed(port, "TCP")) {
                    Logger.w("MainViewModel", "Port $port is not allowed by firewall")
                    _uiState.update { it.copy(showFirewallDialog = true, pendingFirewallPort = port) }
                }
            }
        }
    }

    fun dismissFirewallDialog() {
        _uiState.update { it.copy(showFirewallDialog = false, pendingFirewallPort = null) }
    }

    fun confirmAddFirewallRule() {
        val port = _uiState.value.pendingFirewallPort ?: return
        _uiState.update { it.copy(showFirewallDialog = false, pendingFirewallPort = null) }
        
        viewModelScope.launch {
            val result = addFirewallRule(port, "TCP")
            if (result.isSuccess) {
                Logger.i("MainViewModel", "Firewall rule added successfully")
                startStream() // 成功添加后重试启动串流
            } else {
                val error = result.exceptionOrNull()?.message ?: "Unknown error"
                Logger.e("MainViewModel", "Failed to add firewall rule: $error")
                _uiState.update { it.copy(errorMessage = "无法自动添加防火墙规则: $error\n请尝试以管理员身份运行程序，或手动在防火墙中放行 TCP $port 端口。") }
            }
        }
    }

    fun stopStream() {
        Logger.i("MainViewModel", "Stopping stream")
        audioEngine.stop()
    }

    fun setMode(mode: ConnectionMode) {
        Logger.i("MainViewModel", "Setting connection mode to $mode")
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
        } else if (mode == ConnectionMode.Bluetooth) {
            // Even if manual, we should suggest safe defaults or just leave it if user insists.
            // But previous logic forced it. Let's keep previous logic as fallback if auto config is somehow bypassed
            // or just rely on applyAutoConfig if we want strict control.
            // Wait, if isAutoConfig is FALSE, we should NOT change settings automatically.
            // So we remove the previous forced block and rely on applyAutoConfig being called if true.
            // If false, we do nothing.
        }

        _uiState.update { it.copy(mode = mode, port = updatedPort) }
        settings.putString("connection_mode", mode.name)
        if (updatedPort != current.port) {
            settings.putString("port", updatedPort)
        }
    }
    
    fun setIp(ip: String) {
        if (_uiState.value.mode == ConnectionMode.Bluetooth) {
            Logger.d("MainViewModel", "Setting Bluetooth address to $ip")
            _uiState.update { it.copy(bluetoothAddress = ip) }
            settings.putString("bluetooth_address", ip)
        } else {
            Logger.d("MainViewModel", "Setting IP to $ip")
            _uiState.update { it.copy(ipAddress = ip) }
            settings.putString("ip_address", ip)
        }
    }

    fun setPort(port: String) {
        Logger.d("MainViewModel", "Setting port to $port")
        _uiState.update { it.copy(port = port) }
        settings.putString("port", port)
    }

    fun setThemeMode(mode: ThemeMode) {
        _uiState.update { it.copy(themeMode = mode) }
        settings.putString("theme_mode", mode.name)
    }

    fun setSeedColor(color: Long) {
        _uiState.update { it.copy(seedColor = color) }
        settings.putLong("seed_color", color)
    }

    fun setMonitoringEnabled(enabled: Boolean) {
        _uiState.update { it.copy(monitoringEnabled = enabled) }
        settings.putBoolean("monitoring_enabled", enabled)
        audioEngine.setMonitoring(enabled)
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
    
    fun setAutoStart(enabled: Boolean) {
        _uiState.update { it.copy(autoStart = enabled) }
        settings.putBoolean("auto_start", enabled)
    }

    fun setEnableStreamingNotification(enabled: Boolean) {
        _uiState.update { it.copy(enableStreamingNotification = enabled) }
        settings.putBoolean("enable_streaming_notification", enabled)
        audioEngine.setStreamingNotificationEnabled(enabled)
    }

    fun setUseDynamicColor(enable: Boolean) {
        settings.putBoolean("use_dynamic_color", enable)
        _uiState.update { it.copy(useDynamicColor = enable) }
    }

    fun clearInstallMessage() {
        _uiState.update { it.copy(installMessage = null) }
    }

    fun showSnackbar(message: String) {
        _uiState.update { it.copy(snackbarMessage = message) }
    }

    fun clearSnackbar() {
        _uiState.update { it.copy(snackbarMessage = null) }
    }

    fun setLanguage(language: AppLanguage) {
        Logger.i("MainViewModel", "Setting language to ${language.name}")
        _uiState.update { it.copy(language = language) }
        settings.putString("language", language.name)
    }

    fun exportLog(onResult: (String?) -> Unit) {
        val path = Logger.getLogFilePath()
        onResult(path)
    }
}
