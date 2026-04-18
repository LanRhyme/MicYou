package com.lanrhyme.micyou.plugin

import com.lanrhyme.micyou.AudioEngine
import com.lanrhyme.micyou.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * PluginHost 实现的公共基类。
 *
 * 提供跨平台共享的 PluginHost 功能实现，平台特定部分由子类实现。
 */
abstract class BasePluginHostImpl(
    protected val audioEngine: AudioEngine,
    protected val settings: Settings,
    protected val scope: CoroutineScope = CoroutineScope(Dispatchers.Default)
) : PluginHost {

    protected val _audioConfig = MutableStateFlow(AudioConfig())
    protected val _streamState = MutableStateFlow(StreamState.Idle)
    protected val _audioLevels = MutableStateFlow(0f)
    protected val _isMuted = MutableStateFlow(false)
    protected val _connectionInfo = MutableStateFlow<ConnectionInfo?>(null)

    // 平台特定的 DataChannelProvider，由子类提供
    protected abstract val dataChannelProvider: PluginDataChannelProvider

    init {
        // 监听 AudioEngine 状态变化
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
            nsType = mapNsType(config.nsType),
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
            mode = mapConnectionMode(mode),
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

    /**
     * 已注册的音频效果器及其优先级。
     * 存储为 Pair<效果器, 优先级>，按优先级排序时使用。
     */
    protected val registeredEffects = mutableMapOf<String, Pair<AudioEffectProvider, Int>>()

    /**
     * 注册音频效果器。
     *
     * @param effect 音频效果器提供者
     * @param priority 处理优先级，数值越小越先处理（例如：降噪=10, AGC=50, 放大=100）
     */
    override fun registerAudioEffect(effect: AudioEffectProvider, priority: Int) {
        registeredEffects[effect.id] = effect to priority
    }

    override fun unregisterAudioEffect(effect: AudioEffectProvider) {
        registeredEffects.remove(effect.id)
    }

    /**
     * 获取按优先级排序的已注册效果器列表。
     * 优先级数值越小，越先处理音频。
     *
     * @return 按优先级升序排列的效果器列表
     */
    fun getSortedEffects(): List<AudioEffectProvider> {
        return registeredEffects.values
            .sortedBy { it.second }  // 按优先级升序排序
            .map { it.first }        // 提取效果器
    }

    /**
     * 批量处理音频数据，按优先级顺序应用所有已注册的效果器。
     *
     * @param input 输入音频数据
     * @param channelCount 声道数
     * @param sampleRate 采样率
     * @return 处理后的音频数据
     */
    fun processAudio(input: ShortArray, channelCount: Int, sampleRate: Int): ShortArray {
        var output = input
        for (effect in getSortedEffects()) {
            if (effect.isEnabled) {
                output = effect.process(output, channelCount, sampleRate)
            }
        }
        return output
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

    // Settings 方法实现
    override fun getSetting(key: String, defaultValue: String): String =
        settings.getString(key, defaultValue)

    override fun setSetting(key: String, value: String) =
        settings.putString(key, value)

    override fun getSettingBoolean(key: String, defaultValue: Boolean): Boolean =
        settings.getBoolean(key, defaultValue)

    override fun setSettingBoolean(key: String, value: Boolean) =
        settings.putBoolean(key, value)

    override fun getSettingInt(key: String, defaultValue: Int): Int =
        settings.getInt(key, defaultValue)

    override fun setSettingInt(key: String, value: Int) =
        settings.putInt(key, value)

    override fun getSettingFloat(key: String, defaultValue: Float): Float =
        settings.getFloat(key, defaultValue)

    override fun setSettingFloat(key: String, value: Float) =
        settings.putFloat(key, value)

    // 辅助映射方法
    protected fun mapNsType(type: NoiseReductionType): com.lanrhyme.micyou.NoiseReductionType {
        return when (type) {
            NoiseReductionType.Ulunas -> com.lanrhyme.micyou.NoiseReductionType.Ulunas
            NoiseReductionType.RNNoise -> com.lanrhyme.micyou.NoiseReductionType.RNNoise
            NoiseReductionType.Speexdsp -> com.lanrhyme.micyou.NoiseReductionType.Speexdsp
            NoiseReductionType.None -> com.lanrhyme.micyou.NoiseReductionType.None
        }
    }

    protected fun mapConnectionMode(mode: ConnectionMode): com.lanrhyme.micyou.ConnectionMode {
        return when (mode) {
            ConnectionMode.Wifi -> com.lanrhyme.micyou.ConnectionMode.Wifi
            ConnectionMode.Bluetooth -> com.lanrhyme.micyou.ConnectionMode.Bluetooth
            ConnectionMode.Usb -> com.lanrhyme.micyou.ConnectionMode.Usb
        }
    }
}