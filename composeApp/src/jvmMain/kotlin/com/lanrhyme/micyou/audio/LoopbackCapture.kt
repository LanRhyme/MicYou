package com.lanrhyme.micyou.audio

import com.lanrhyme.micyou.Logger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 系统音频回环采集接口
 * 用于采集 PC 端系统播放的音频，作为 AEC 的参考信号
 */
interface LoopbackCapture {
    /** 开始采集 */
    suspend fun start(sampleRate: Int, channelCount: Int)

    /** 停止采集 */
    fun stop()

    /** 注册音频数据回调 */
    fun onAudioData(callback: (ByteArray, Int, Int, Long) -> Unit)

    /** 是否正在采集 */
    val isCapturing: Boolean
}

/**
 * Windows WASAPI 回环采集实现已移至 WasapiLoopbackCapture.kt
 */

/**
 * Linux PulseAudio/PipeWire 回环采集实现
 */
class PulseAudioLoopbackCapture : LoopbackCapture {
    private var capturing = false
    private var audioCallback: ((ByteArray, Int, Int, Long) -> Unit)? = null

    override val isCapturing: Boolean get() = capturing

    override suspend fun start(sampleRate: Int, channelCount: Int) {
        if (capturing) return
        capturing = true

        Logger.i("LoopbackCapture", "Starting PulseAudio loopback capture: ${sampleRate}Hz, ${channelCount}ch")

        // TODO: 实现 PulseAudio/PipeWire Monitor 源采集
        // PulseAudio: pactl list sources | grep monitor
        // 然后使用 pa_simple_new 打开 monitor 源

        Logger.w("LoopbackCapture", "PulseAudio loopback capture not yet implemented")
    }

    override fun stop() {
        capturing = false
        Logger.i("LoopbackCapture", "Loopback capture stopped")
    }

    override fun onAudioData(callback: (ByteArray, Int, Int, Long) -> Unit) {
        audioCallback = callback
    }
}

/**
 * 回环音频采集管理器
 * 根据平台选择合适的采集实现
 */
class LoopbackCaptureManager {
    private var capture: LoopbackCapture? = null

    private val _isCapturing = MutableStateFlow(false)
    val isCapturing = _isCapturing.asStateFlow()

    /**
     * 根据平台创建采集实例
     */
    fun createCapture(): LoopbackCapture {
        val os = System.getProperty("os.name").lowercase()
        return when {
            os.contains("win") -> WasapiLoopbackCapture()
            os.contains("linux") -> PulseAudioLoopbackCapture()
            else -> {
                Logger.w("LoopbackCapture", "Unsupported platform for loopback capture: $os")
                WasapiLoopbackCapture() // 默认尝试 WASAPI
            }
        }
    }

    /**
     * 开始采集
     */
    suspend fun start(sampleRate: Int = 44100, channelCount: Int = 1) {
        capture?.stop()
        capture = createCapture()
        capture?.start(sampleRate, channelCount)
        _isCapturing.value = true
    }

    /**
     * 停止采集
     */
    fun stop() {
        capture?.stop()
        capture = null
        _isCapturing.value = false
    }

    /**
     * 注册音频数据回调
     */
    fun onAudioData(callback: (ByteArray, Int, Int, Long) -> Unit) {
        capture?.onAudioData(callback)
    }
}
