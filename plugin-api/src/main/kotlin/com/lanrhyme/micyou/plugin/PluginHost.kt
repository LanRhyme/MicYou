package com.lanrhyme.micyou.plugin

import kotlinx.coroutines.flow.StateFlow

enum class StreamState {
    Idle, Connecting, Streaming, Error
}

enum class ConnectionMode {
    Wifi, Usb
}

enum class NoiseReductionType {
    Ulunas, RNNoise, Speexdsp, None
}

data class AudioConfig(
    val enableNS: Boolean = false,
    val nsType: NoiseReductionType = NoiseReductionType.RNNoise,
    val enableAGC: Boolean = false,
    val agcTargetLevel: Int = 32000,
    val enableVAD: Boolean = false,
    val vadThreshold: Int = 10,
    val enableDereverb: Boolean = false,
    val dereverbLevel: Float = 0.5f,
    val amplification: Float = 15.0f
)

data class ConnectionInfo(
    val mode: ConnectionMode,
    val ipAddress: String,
    val port: Int,
    val isClient: Boolean
)

interface PluginHost {
    val streamState: StateFlow<StreamState>
    val audioLevels: StateFlow<Float>
    val isMuted: StateFlow<Boolean>
    val connectionInfo: StateFlow<ConnectionInfo?>
    val audioConfig: StateFlow<AudioConfig>
    
    fun updateAudioConfig(config: AudioConfig)
    fun updateAudioConfig(block: AudioConfig.() -> AudioConfig)
    
    suspend fun startStream(
        ip: String,
        port: Int,
        mode: ConnectionMode,
        isClient: Boolean
    )
    
    suspend fun stopStream()
    suspend fun setMute(muted: Boolean)
    
    fun setMonitoring(enabled: Boolean)
    
    fun registerAudioEffect(effect: AudioEffectProvider, priority: Int = 100)
    fun unregisterAudioEffect(effect: AudioEffectProvider)
    
    fun createDataChannel(
        id: String,
        config: DataChannelConfig = DataChannelConfig()
    ): PluginDataChannel
    
    fun getDataChannel(id: String): PluginDataChannel?
    fun closeDataChannel(id: String)
    
    fun showSnackbar(message: String)
    fun showNotification(title: String, message: String)
    
    fun getSetting(key: String, defaultValue: String): String
    fun setSetting(key: String, value: String)
    fun getSettingBoolean(key: String, defaultValue: Boolean): Boolean
    fun setSettingBoolean(key: String, value: Boolean)
    fun getSettingInt(key: String, defaultValue: Int): Int
    fun setSettingInt(key: String, value: Int)
    fun getSettingFloat(key: String, defaultValue: Float): Float
    fun setSettingFloat(key: String, value: Float)
    
    val platform: PlatformInfo
    
    data class PlatformInfo(
        val name: String,
        val version: String,
        val isDesktop: Boolean,
        val isMobile: Boolean
    )
}
