package com.lanrhyme.micyou

import kotlinx.coroutines.flow.Flow

// 音频引擎，负责处理音频流的发送和接收
expect class AudioEngine() {
    // 流状态流
    val streamState: Flow<StreamState>
    // 音频电平流（用于可视化）
    val audioLevels: Flow<Float>
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
        enableAGC: Boolean,
        agcTargetLevel: Int,
        enableVAD: Boolean,
        vadThreshold: Int,
        enableDereverb: Boolean,
        dereverbLevel: Float,
        amplification: Float
    )

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
}
