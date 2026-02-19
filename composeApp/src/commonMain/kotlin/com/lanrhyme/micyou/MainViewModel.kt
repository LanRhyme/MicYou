package com.lanrhyme.micyou

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.random.Random

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

internal fun mapConnectionModeFromSettings(savedModeName: String): ConnectionMode {
    return when (savedModeName) {
        "WifiUdp" -> ConnectionMode.Wifi
        else -> try {
            ConnectionMode.valueOf(savedModeName)
        } catch (e: Exception) {
            ConnectionMode.Wifi
        }
    }
}

internal data class AutoConfigPreset(
    val sampleRate: SampleRate,
    val channelCount: ChannelCount,
    val audioFormat: AudioFormat
)

internal fun resolveAutoConfigPreset(mode: ConnectionMode): AutoConfigPreset {
    return if (mode == ConnectionMode.Bluetooth) {
        AutoConfigPreset(
            sampleRate = SampleRate.Rate16000,
            channelCount = ChannelCount.Mono,
            audioFormat = AudioFormat.PCM_16BIT
        )
    } else {
        AutoConfigPreset(
            sampleRate = SampleRate.Rate48000,
            channelCount = ChannelCount.Stereo,
            audioFormat = AudioFormat.PCM_16BIT
        )
    }
}

internal fun generateAuthToken(length: Int = 24): String {
    val alphabet = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
    return buildString(length) {
        repeat(length) {
            append(alphabet[Random.nextInt(alphabet.length)])
        }
    }
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
    val authToken: String = "",
    val showCloseConfirmDialog: Boolean = false,
    val rememberCloseAction: Boolean = false,
    val newVersionAvailable: GitHubRelease? = null,
    val videoEnabled: Boolean = false,
    val videoPort: String = "7000",
    val videoState: VideoStreamState = VideoStreamState.Idle,
    val videoErrorMessage: String? = null,
    val showVirtualCameraDiagDialog: Boolean = false,
    val virtualCameraDiagMessage: String? = null,
    val videoProfile: VideoProfile = VideoProfile.FHD_1080P_30,
    val videoQuality: Int = 85,
    val videoFps: Int = 0,
    val videoLatencyMs: Long = 0L,
    val videoRttMs: Long? = null,
    val videoVirtualCameraBinding: String? = null,
    val videoWidth: Int = 0,
    val videoHeight: Int = 0
)

enum class CloseAction(val label: String) {
    Prompt("prompt"),
    Minimize("minimize"),
    Exit("exit")
}

class MainViewModel : ViewModel() {
    private val audioEngine = AudioEngine()
    private val videoEngine = VideoEngine()
    private val _uiState = MutableStateFlow(AppUiState())
    val uiState: StateFlow<AppUiState> = _uiState.asStateFlow()
    val audioLevels = audioEngine.audioLevels
    val videoFrame = videoEngine.latestFrame
    val videoStats = videoEngine.stats
    private val settings = SettingsFactory.getSettings()
    private val updateChecker = UpdateChecker()

    init {
        // Load settings
        val savedModeName = settings.getString("connection_mode", ConnectionMode.Wifi.name)
        val savedMode = mapConnectionModeFromSettings(savedModeName)
        
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
        val savedAuthToken = settings.getString("auth_token", "").trim()
        val savedVideoEnabled = settings.getBoolean("video_enabled", false)
        val savedVideoPort = settings.getString("video_port", "7000")
        val savedVideoProfileName = settings.getString("video_profile", VideoProfile.FHD_1080P_30.name)
        val savedVideoProfile = try { VideoProfile.valueOf(savedVideoProfileName) } catch (e: Exception) { VideoProfile.FHD_1080P_30 }
        val savedVideoQuality = settings.getInt("video_quality", 85).coerceIn(30, 95)

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
                authToken = savedAuthToken,
                videoEnabled = savedVideoEnabled,
                videoPort = savedVideoPort,
                videoProfile = savedVideoProfile,
                videoQuality = savedVideoQuality
            ) 
        }
        PlatformAdaptor.setAuthToken(savedAuthToken)
        PlatformAdaptor.setVideoProfile(savedVideoProfile)
        PlatformAdaptor.setVideoQuality(savedVideoQuality)
        
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

        viewModelScope.launch {
            videoEngine.streamState.collect { state ->
                _uiState.update { it.copy(videoState = state) }
            }
        }

        viewModelScope.launch {
            videoEngine.lastError.collect { error ->
                val shouldShowDiag = getPlatform().type == PlatformType.Desktop &&
                    !error.isNullOrBlank() &&
                    isVirtualCameraRelatedError(error)
                _uiState.update {
                    it.copy(
                        videoErrorMessage = error,
                        showVirtualCameraDiagDialog = shouldShowDiag,
                        virtualCameraDiagMessage = if (shouldShowDiag) buildVirtualCameraDiag(error) else it.virtualCameraDiagMessage
                    )
                }
            }
        }

        viewModelScope.launch {
            videoEngine.stats.collect { stats ->
                _uiState.update {
                    it.copy(
                        videoFps = stats.fps,
                        videoLatencyMs = stats.latencyMs,
                        videoWidth = stats.width,
                        videoHeight = stats.height
                    )
                }
            }
        }

        viewModelScope.launch {
            videoEngine.rttMs.collect { rtt ->
                _uiState.update { it.copy(videoRttMs = rtt) }
            }
        }
        viewModelScope.launch {
            videoEngine.virtualCameraBinding.collect { binding ->
                _uiState.update { it.copy(videoVirtualCameraBinding = binding) }
            }
        }

        if (getPlatform().type == PlatformType.Desktop) {
            viewModelScope.launch {
                audioEngine.installDriver()
            }
            if (savedAutoStart) {
                startStream()
                if (savedVideoEnabled) {
                    startVideo()
                }
            }
        }

        viewModelScope.launch {
            val release = updateChecker.checkUpdate()
            if (release != null) {
                _uiState.update { it.copy(newVersionAvailable = release) }
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
            val release = updateChecker.checkUpdate()
            if (release != null) {
                _uiState.update { it.copy(newVersionAvailable = release) }
            } else {
                _uiState.update { it.copy(snackbarMessage = strings.isLatestVersion) }
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
        val preset = resolveAutoConfigPreset(mode)
        setSampleRate(preset.sampleRate)
        setChannelCount(preset.channelCount)
        setAudioFormat(preset.audioFormat)
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

        val prerequisiteError = validateStreamingPrerequisites(mode)
        if (prerequisiteError != null) {
            _uiState.update { it.copy(streamState = StreamState.Error, errorMessage = prerequisiteError) }
            return
        }

        _uiState.update { it.copy(streamState = StreamState.Connecting, errorMessage = null) }

        val shouldStartVideoWithAudio = getPlatform().type == PlatformType.Desktop &&
            mode != ConnectionMode.Bluetooth &&
            _uiState.value.videoState != VideoStreamState.Streaming &&
            _uiState.value.videoState != VideoStreamState.Connecting
        if (shouldStartVideoWithAudio) {
            startVideo()
        }

        // 启动音频引擎（不阻塞）
        viewModelScope.launch {
            // Config is already updated via updateAudioEngineConfig, but we pass params to start just in case or for init
            updateAudioEngineConfig()

            try {
                Logger.d("MainViewModel", "Calling audioEngine.start()")
                audioEngine.start(ip, port, mode, isClient, sampleRate, channelCount, audioFormat)
                Logger.i("MainViewModel", "Stream started successfully")
            } catch (e: CancellationException) {
                Logger.d("MainViewModel", "Audio start coroutine cancelled")
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
        if (getPlatform().type == PlatformType.Desktop &&
            (_uiState.value.videoState == VideoStreamState.Streaming || _uiState.value.videoState == VideoStreamState.Connecting)
        ) {
            stopVideo()
        }
    }

    fun toggleVideo() {
        if (_uiState.value.videoState == VideoStreamState.Streaming || _uiState.value.videoState == VideoStreamState.Connecting) {
            stopVideo()
        } else {
            startVideo()
        }
    }

    fun startVideo() {
        Logger.i("MainViewModel", "Starting video")
        val mode = _uiState.value.mode
        val ip = if (mode == ConnectionMode.Bluetooth) _uiState.value.bluetoothAddress else _uiState.value.ipAddress
        val port = _uiState.value.videoPort.toIntOrNull() ?: 7000
        val isClient = getPlatform().type == PlatformType.Android
        val profile = _uiState.value.videoProfile
        val quality = _uiState.value.videoQuality

        val prerequisiteError = validateVideoPrerequisites(mode)
        if (prerequisiteError != null) {
            _uiState.update { it.copy(videoState = VideoStreamState.Error, videoErrorMessage = prerequisiteError) }
            return
        }

        _uiState.update { it.copy(videoState = VideoStreamState.Connecting, videoErrorMessage = null) }
        viewModelScope.launch {
            try {
                videoEngine.updateConfig(profile, quality)
                videoEngine.start(ip, port, mode, isClient, profile, quality)
            } catch (e: CancellationException) {
                Logger.d("MainViewModel", "Video start coroutine cancelled")
            } catch (e: Exception) {
                Logger.e("MainViewModel", "Failed to start video", e)
                _uiState.update { it.copy(videoState = VideoStreamState.Error, videoErrorMessage = e.message) }
            }
        }

        if (!isClient && mode == ConnectionMode.Wifi) {
            viewModelScope.launch {
                if (!isPortAllowed(port, "TCP")) {
                    _uiState.update { it.copy(snackbarMessage = "Video port $port may be blocked by firewall.") }
                }
            }
        }
    }

    fun stopVideo() {
        Logger.i("MainViewModel", "Stopping video")
        videoEngine.stop()
    }

    fun switchVideoCamera() {
        videoEngine.switchCamera()
    }

    fun restartVirtualCamera() {
        videoEngine.restartVirtualCamera()
        _uiState.update { it.copy(snackbarMessage = "Virtual camera restarted.") }
    }

    fun testVideoRtt() {
        videoEngine.requestRttTest()
    }

    fun dismissVirtualCameraDiagDialog() {
        _uiState.update { it.copy(showVirtualCameraDiagDialog = false) }
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

    fun setAuthToken(token: String) {
        val normalized = token.trim()
        _uiState.update { it.copy(authToken = normalized) }
        settings.putString("auth_token", normalized)
        PlatformAdaptor.setAuthToken(normalized)
    }

    fun clearAuthToken() {
        setAuthToken("")
    }

    fun generateAndSetAuthToken() {
        setAuthToken(generateAuthToken())
    }

    fun setVideoEnabled(enabled: Boolean) {
        _uiState.update { it.copy(videoEnabled = enabled) }
        settings.putBoolean("video_enabled", enabled)
    }

    fun setVideoPort(port: String) {
        _uiState.update { it.copy(videoPort = port) }
        settings.putString("video_port", port)
    }

    fun setVideoProfile(profile: VideoProfile) {
        _uiState.update { it.copy(videoProfile = profile) }
        settings.putString("video_profile", profile.name)
        PlatformAdaptor.setVideoProfile(profile)
        videoEngine.updateConfig(profile, _uiState.value.videoQuality)
    }

    fun setVideoQuality(quality: Int) {
        val normalized = quality.coerceIn(30, 95)
        _uiState.update { it.copy(videoQuality = normalized) }
        settings.putInt("video_quality", normalized)
        PlatformAdaptor.setVideoQuality(normalized)
        videoEngine.updateConfig(_uiState.value.videoProfile, normalized)
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

    override fun onCleared() {
        super.onCleared()
        audioEngine.stop()
        videoEngine.stop()
    }

    private fun isVirtualCameraRelatedError(error: String): Boolean {
        val normalized = error.lowercase()
        return normalized.contains("virtual camera") ||
            normalized.contains("vcam") ||
            normalized.contains("pyvirtualcam") ||
            normalized.contains("helper")
    }

    private fun buildVirtualCameraDiag(error: String): String {
        return buildString {
            appendLine("Virtual camera helper start failed.")
            appendLine("Error: $error")
            appendLine()
            appendLine("Quick checks:")
            appendLine("1) Install dependencies:")
            appendLine("""   "C:\Users\Administrator\AppData\Local\Programs\Python\Python310\python.exe" -m pip install pyvirtualcam numpy""")
            appendLine("2) Ensure one virtual camera backend exists (OBS Virtual Camera / Unity Capture).")
            appendLine("3) Restart MicYou desktop.")
            appendLine("4) In meeting software choose the virtual camera device.")
        }
    }
}
