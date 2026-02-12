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
    val seedColor: Long = 0xFF6750A4,
    val monitoringEnabled: Boolean = false,
    val sampleRate: SampleRate = SampleRate.Rate44100,
    val channelCount: ChannelCount = ChannelCount.Mono,
    val audioFormat: AudioFormat = AudioFormat.PCM_16BIT,
    val installMessage: String? = null,
    
    // Audio Processing Settings
    val enableNS: Boolean = false,
    val nsType: NoiseReductionType = NoiseReductionType.Ulunas,
    
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
    val bluetoothAddress: String = ""
)

class MainViewModel : ViewModel() {
    private val audioEngine = AudioEngine()
    private val _uiState = MutableStateFlow(AppUiState())
    val uiState: StateFlow<AppUiState> = _uiState.asStateFlow()
    val audioLevels = audioEngine.audioLevels
    private val settings = SettingsFactory.getSettings()

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
        
        val savedSeedColor = settings.getLong("seed_color", 0xFF6750A4)
        
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
                bluetoothAddress = savedBluetoothAddress
            ) 
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
        val mode = _uiState.value.mode
        val ip = if (mode == ConnectionMode.Bluetooth) _uiState.value.bluetoothAddress else _uiState.value.ipAddress
        val port = _uiState.value.port.toIntOrNull() ?: 6000
        val isClient = getPlatform().type == PlatformType.Android
        val sampleRate = _uiState.value.sampleRate
        val channelCount = _uiState.value.channelCount
        val audioFormat = _uiState.value.audioFormat

        // Config is already updated via updateAudioEngineConfig, but we pass params to start just in case or for init
        updateAudioEngineConfig()

        viewModelScope.launch {
            audioEngine.start(ip, port, mode, isClient, sampleRate, channelCount, audioFormat)
        }
    }

    fun stopStream() {
        audioEngine.stop()
    }

    fun setMode(mode: ConnectionMode) {
        val platformType = getPlatform().type
        val current = _uiState.value

        val updatedPort = if (platformType == PlatformType.Android && mode == ConnectionMode.Usb) {
            val parsed = current.port.toIntOrNull()
            if (parsed == null || parsed <= 0) "6000" else current.port
        } else {
            current.port
        }

        _uiState.update { it.copy(mode = mode, port = updatedPort) }
        settings.putString("connection_mode", mode.name)
        if (updatedPort != current.port) {
            settings.putString("port", updatedPort)
        }
    }
    
    fun setIp(ip: String) {
        if (_uiState.value.mode == ConnectionMode.Bluetooth) {
            _uiState.update { it.copy(bluetoothAddress = ip) }
            settings.putString("bluetooth_address", ip)
        } else {
            _uiState.update { it.copy(ipAddress = ip) }
            settings.putString("ip_address", ip)
        }
    }

    fun setPort(port: String) {
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

    fun setLanguage(language: AppLanguage) {
        _uiState.update { it.copy(language = language) }
        settings.putString("language", language.name)
    }
}
