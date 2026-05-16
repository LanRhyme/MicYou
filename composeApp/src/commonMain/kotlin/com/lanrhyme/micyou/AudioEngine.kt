package com.lanrhyme.micyou

import kotlinx.coroutines.flow.Flow

// 音频引擎，负责处理音频流的发送和接收
expect class AudioEngine() {
    // 流状态流
    val streamState: Flow<StreamState>
    // 音频电平流（用于可视化）
    val audioLevels: Flow<Float>
    // 原始音频频谱流
    val rawSpectrum: Flow<FloatArray>
    // 处理后音频频谱流
    val processedSpectrum: Flow<FloatArray>
    // 详细音频电平数据流（包含 RMS、峰值、dB）
    val audioLevelData: Flow<AudioLevelData>
    // 音频指标流（比特率、延迟）
    val audioMetrics: Flow<AudioMetrics?>
    // 错误信息流
    val lastError: Flow<String?>
    // 静音状态流
    val isMuted: Flow<Boolean>

    // 启动音频引擎
    suspend fun start(
        ip: String,
        port: Int,
        mode: ConnectionMode,
        isClient: Boolean,
        sampleRate: SampleRate,
        channelCount: ChannelCount,
        audioFormat: AudioFormat
    )

    // 更新音频处理配置
    fun updateConfig(
        enableNS: Boolean,
        nsType: NoiseReductionType,
        nsIntensity: Float,
        enableAGC: Boolean,
        agcTargetLevel: Int,
        agcAttackRate: Float,
        agcDecayRate: Float,
        enableVAD: Boolean,
        vadThreshold: Int,
        enableDereverb: Boolean,
        dereverbLevel: Float,
        amplification: Float,
        processingChain: List<AudioEffectType>,
        equalizerConfig: EqualizerConfig = EqualizerConfig()
    )


    // 更新性能配置
    fun updatePerformanceConfig(config: PerformanceConfig)
    
    // 设置插件音频效果器（仅桌面端有效）
    fun setPluginAudioEffects(effects: List<com.lanrhyme.micyou.plugin.AudioEffectProvider>)

    // 停止音频引擎
    fun stop()
    // 设置是否启用本地监听（仅桌面端有效）
    fun setMonitoring(enabled: Boolean)

    // 安装驱动进度（仅桌面端有效）
    val installProgress: Flow<String?>
    // 安装驱动（仅桌面端有效）
    suspend fun installDriver()

    // 设置静音状态
    suspend fun setMute(muted: Boolean)

    fun setStreamingNotificationEnabled(enabled: Boolean)

    // 设置音频源（仅 Android 端有效）
    fun setAudioSource(sourceName: String)

    // Web 模式状态（仅桌面端有效）
    val webUrl: Flow<String>
    val webClientCount: Flow<Int>
}
