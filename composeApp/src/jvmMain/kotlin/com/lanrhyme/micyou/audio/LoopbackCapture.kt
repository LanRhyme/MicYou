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
    private var captureJob: Job? = null
    private var process: Process? = null

    override val isCapturing: Boolean get() = capturing

    override suspend fun start(sampleRate: Int, channelCount: Int) {
        if (capturing) return
        capturing = true

        Logger.i("LoopbackCapture", "Starting PulseAudio/PipeWire loopback capture: ${sampleRate}Hz, ${channelCount}ch")

        val coroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        captureJob = coroutineScope.launch {
            try {
                // 1. 探测音频服务器
                val isPipeWire = isPipeWire()
                
                // 2. 构建命令
                val command = if (isPipeWire) {
                    listOf(
                        "pw-record", "--raw",
                        "--format=s16",
                        "--rate=$sampleRate",
                        "--channels=$channelCount",
                        "-P", "{ stream.capture.sink=true }",
                        "-"
                    )
                } else {
                    listOf(
                        "parec", "-d", "@DEFAULT_MONITOR@",
                        "--format=s16le",
                        "--rate=$sampleRate",
                        "--channels=$channelCount"
                    )
                }

                Logger.d("LoopbackCapture", "Executing command: ${command.joinToString(" ")}")
                
                val pb = ProcessBuilder(command)
                pb.redirectError(ProcessBuilder.Redirect.PIPE)
                process = pb.start()

                val inputStream = process?.inputStream
                val errorStream = process?.errorStream

                // 错误日志处理
                launch {
                    errorStream?.bufferedReader()?.forEachLine { line ->
                        Logger.w("LoopbackCapture", "Linux Capture Error: $line")
                    }
                }

                if (inputStream == null) {
                    Logger.e("LoopbackCapture", "Failed to get input stream from capture process")
                    return@launch
                }

                // 3. 读取循环
                val bufferSize = sampleRate * channelCount * 2 / 100 // 10ms buffer
                val buffer = ByteArray(bufferSize)
                
                while (isActive && capturing) {
                    val read = withContext(Dispatchers.IO) {
                        inputStream.read(buffer)
                    }
                    if (read > 0) {
                        val timestamp = System.currentTimeMillis()
                        audioCallback?.invoke(buffer.copyOf(read), sampleRate, channelCount, timestamp)
                    } else if (read == -1) {
                        Logger.w("LoopbackCapture", "Capture process ended unexpectedly")
                        break
                    }
                }
            } catch (e: Exception) {
                Logger.e("LoopbackCapture", "Error in Linux loopback capture", e)
            } finally {
                stop()
            }
        }
    }

    private suspend fun isPipeWire(): Boolean = withContext(Dispatchers.IO) {
        try {
            val p = ProcessBuilder("pactl", "info").start()
            val output = p.inputStream.bufferedReader().readText()
            p.waitFor()
            output.contains("on PipeWire", ignoreCase = true)
        } catch (e: Exception) {
            false
        }
    }

    override fun stop() {
        if (!capturing) return
        capturing = false
        captureJob?.cancel()
        captureJob = null
        process?.destroy()
        process = null
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
