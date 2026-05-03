package com.lanrhyme.micyou.audio

import com.lanrhyme.micyou.Logger

/**
 * Windows WASAPI 回环采集实现
 * 通过 JNI 调用原生 C++ DLL 实现，避免 JNA COM 调用的兼容性问题
 */
class WasapiLoopbackCapture : LoopbackCapture {
    private var capturing = false
    private var audioCallback: ((ByteArray, Int, Int, Long) -> Unit)? = null
    private val native = WasapiLoopbackNative()

    override val isCapturing: Boolean get() = capturing

    override suspend fun start(sampleRate: Int, channelCount: Int) {
        if (capturing) return

        if (!WasapiLoopbackNative.isAvailable()) {
            Logger.e("WasapiLoopback", "Native WASAPI library not available")
            return
        }

        capturing = true
        Logger.i("WasapiLoopback", "Starting native WASAPI loopback capture: ${sampleRate}Hz, ${channelCount}ch")

        native.onAudioData { data, actualSampleRate, actualChannels, ts ->
            audioCallback?.invoke(data, actualSampleRate, actualChannels, ts)
        }

        if (!native.start(sampleRate, channelCount)) {
            Logger.e("WasapiLoopback", "Failed to start native loopback capture")
            capturing = false
        }
    }

    override fun stop() {
        if (!capturing) return
        capturing = false
        native.stop()
        Logger.i("WasapiLoopback", "Loopback capture stopped")
    }

    override fun onAudioData(callback: (ByteArray, Int, Int, Long) -> Unit) {
        audioCallback = callback
    }
}
