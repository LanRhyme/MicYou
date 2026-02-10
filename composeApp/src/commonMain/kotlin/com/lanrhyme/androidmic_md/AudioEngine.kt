package com.lanrhyme.androidmic_md

import kotlinx.coroutines.flow.Flow

// 音频引擎，负责处理音频流的发送和接收
expect class AudioEngine() {
    // 流状态流
    val streamState: Flow<StreamState>
    // 音频电平流（用于可视化）
    val audioLevels: Flow<Float>
    // 启动音频引擎
    suspend fun start(ip: String, port: Int, mode: ConnectionMode, isClient: Boolean)
    // 停止音频引擎
    fun stop()
}
