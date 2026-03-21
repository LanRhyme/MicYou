package com.lanrhyme.micyou.plugin

import android.os.Build
import com.lanrhyme.micyou.AudioEngine
import com.lanrhyme.micyou.Settings
import com.lanrhyme.micyou.SettingsFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class AndroidPluginHostImpl(
    private val audioEngine: AudioEngine,
    private val settings: Settings = SettingsFactory.getSettings(),
    private val showSnackbarCallback: (String) -> Unit,
    private val showNotificationCallback: (String, String) -> Unit
) : PluginHost {
    
    private val scope = CoroutineScope(Dispatchers.Default)
    
    private val _audioConfig = MutableStateFlow(AudioConfig())
    private val _streamState = MutableStateFlow(StreamState.Idle)
    private val _audioLevels = MutableStateFlow(0f)
    private val _isMuted = MutableStateFlow(false)
    private val _connectionInfo = MutableStateFlow<ConnectionInfo?>(null)
    
    private val dataChannelProvider = AndroidPluginDataChannelProvider()
    
    init {
        scope.launch {
            audioEngine.streamState.collect { state ->
                _streamState.value = when (state) {
                    com.lanrhyme.micyou.StreamState.Idle -> StreamState.Idle
                    com.lanrhyme.micyou.StreamState.Connecting -> StreamState.Connecting
                    com.lanrhyme.micyou.StreamState.Streaming -> StreamState.Streaming
                    com.lanrhyme.micyou.StreamState.Error -> StreamState.Error
                }
            }
        }
        scope.launch {
            audioEngine.audioLevels.collect { _audioLevels.value = it }
        }
        scope.launch {
            audioEngine.isMuted.collect { _isMuted.value = it }
        }
    }
    
    override val streamState: StateFlow<StreamState> = _streamState.asStateFlow()
    override val audioLevels: StateFlow<Float> = _audioLevels.asStateFlow()
    override val isMuted: StateFlow<Boolean> = _isMuted.asStateFlow()
    override val connectionInfo: StateFlow<ConnectionInfo?> = _connectionInfo.asStateFlow()
    override val audioConfig: StateFlow<AudioConfig> = _audioConfig.asStateFlow()
    
    override fun updateAudioConfig(config: AudioConfig) {
        _audioConfig.value = config
        audioEngine.updateConfig(
            enableNS = config.enableNS,
            nsType = when (config.nsType) {
                NoiseReductionType.Ulunas -> com.lanrhyme.micyou.NoiseReductionType.Ulunas
                NoiseReductionType.RNNoise -> com.lanrhyme.micyou.NoiseReductionType.RNNoise
                NoiseReductionType.Speexdsp -> com.lanrhyme.micyou.NoiseReductionType.Speexdsp
                NoiseReductionType.None -> com.lanrhyme.micyou.NoiseReductionType.None
            },
            enableAGC = config.enableAGC,
            agcTargetLevel = config.agcTargetLevel,
            enableVAD = config.enableVAD,
            vadThreshold = config.vadThreshold,
            enableDereverb = config.enableDereverb,
            dereverbLevel = config.dereverbLevel,
            amplification = config.amplification
        )
    }
    
    override fun updateAudioConfig(block: AudioConfig.() -> AudioConfig) {
        val newConfig = _audioConfig.value.block()
        updateAudioConfig(newConfig)
    }
    
    override suspend fun startStream(ip: String, port: Int, mode: ConnectionMode, isClient: Boolean) {
        audioEngine.start(
            ip = ip,
            port = port,
            mode = when (mode) {
                ConnectionMode.Wifi -> com.lanrhyme.micyou.ConnectionMode.Wifi
                ConnectionMode.Bluetooth -> com.lanrhyme.micyou.ConnectionMode.Bluetooth
                ConnectionMode.Usb -> com.lanrhyme.micyou.ConnectionMode.Usb
            },
            isClient = isClient,
            sampleRate = com.lanrhyme.micyou.SampleRate.Rate44100,
            channelCount = com.lanrhyme.micyou.ChannelCount.Mono,
            audioFormat = com.lanrhyme.micyou.AudioFormat.PCM_16BIT
        )
    }
    
    override suspend fun stopStream() {
        audioEngine.stop()
    }
    
    override suspend fun setMute(muted: Boolean) {
        audioEngine.setMute(muted)
    }
    
    override fun setMonitoring(enabled: Boolean) {
        audioEngine.setMonitoring(enabled)
    }
    
    private val registeredEffects = mutableMapOf<String, AudioEffectProvider>()
    
    override fun registerAudioEffect(effect: AudioEffectProvider, priority: Int) {
        registeredEffects[effect.id] = effect
    }
    
    override fun unregisterAudioEffect(effect: AudioEffectProvider) {
        registeredEffects.remove(effect.id)
    }
    
    override fun createDataChannel(id: String, config: DataChannelConfig): PluginDataChannel {
        return dataChannelProvider.createChannel(id, config)
    }
    
    override fun getDataChannel(id: String): PluginDataChannel? {
        return dataChannelProvider.getChannel(id)
    }
    
    override fun closeDataChannel(id: String) {
        dataChannelProvider.closeChannel(id)
    }
    
    override fun showSnackbar(message: String) {
        showSnackbarCallback(message)
    }
    
    override fun showNotification(title: String, message: String) {
        showNotificationCallback(title, message)
    }
    
    override fun getSetting(key: String, defaultValue: String): String = settings.getString(key, defaultValue)
    override fun setSetting(key: String, value: String) = settings.putString(key, value)
    override fun getSettingBoolean(key: String, defaultValue: Boolean): Boolean = settings.getBoolean(key, defaultValue)
    override fun setSettingBoolean(key: String, value: Boolean) = settings.putBoolean(key, value)
    override fun getSettingInt(key: String, defaultValue: Int): Int = settings.getInt(key, defaultValue)
    override fun setSettingInt(key: String, value: Int) = settings.putInt(key, value)
    override fun getSettingFloat(key: String, defaultValue: Float): Float = settings.getFloat(key, defaultValue)
    override fun setSettingFloat(key: String, value: Float) = settings.putFloat(key, value)
    
    override val platform: PluginHost.PlatformInfo = PluginHost.PlatformInfo(
        name = "Android",
        version = Build.VERSION.RELEASE,
        isDesktop = false,
        isMobile = true
    )
}
