package com.lanrhyme.micyou

import com.lanrhyme.micyou.audio.AudioOutputManager
import com.lanrhyme.micyou.audio.AudioProcessorPipeline
import micyou.composeapp.generated.resources.Res
import micyou.composeapp.generated.resources.errorAdbReverseFailed
import org.jetbrains.compose.resources.getString
import com.lanrhyme.micyou.network.MdnsAdvertiser
import com.lanrhyme.micyou.network.NetworkServer
import com.lanrhyme.micyou.platform.AdbManager
import com.lanrhyme.micyou.platform.PlatformInfo
import com.lanrhyme.micyou.web.WebModeService
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.sqrt

actual class AudioEngine actual constructor() {
    private val _state = MutableStateFlow(StreamState.Idle)
    actual val streamState: Flow<StreamState> = _state
    private val _audioLevels = MutableStateFlow(0f)
    actual val audioLevels: Flow<Float> = _audioLevels
    private val _audioLevelData = MutableStateFlow(AudioLevelData.SILENT)
    actual val audioLevelData: Flow<AudioLevelData> = _audioLevelData
    private val _audioMetrics = MutableStateFlow<AudioMetrics?>(null)
    actual val audioMetrics: Flow<AudioMetrics?> = _audioMetrics
    private val _lastError = MutableStateFlow<String?>(null)
    actual val lastError: Flow<String?> = _lastError

    private val _isMuted = MutableStateFlow(false)
    actual val isMuted: Flow<Boolean> = _isMuted
    private val webModeService = WebModeService()
    actual val webServerUrl: Flow<String?> = webModeService.webUrl
    
    private val _pluginSyncReceived = MutableStateFlow<PluginSyncMessage?>(null)
    val pluginSyncReceived: Flow<PluginSyncMessage?> = _pluginSyncReceived
    
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null
    private var audioProcessingJob: Job? = null
    private var stopJob: Job? = null // 用于跟踪停止操作，防止竞态条件
    private var webAudioFeedingJob: Job? = null
    private val startStopMutex = Mutex()

    private val audioOutputManager = AudioOutputManager()
    private val audioPipeline = AudioProcessorPipeline()

    // 当前音频参数（用于计算比特率）
    private var currentSampleRate: Int = 0
    private var currentChannelCount: Int = 0
    private var currentAudioFormatValue: Int = 0
    
    private var lastStatusLogTime = 0L
    
    private val audioPacketChannel = Channel<AudioPacketMessage>(
        capacity = Constants.AUDIO_PACKET_CHANNEL_CAPACITY,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    
    private val mdnsAdvertiser = MdnsAdvertiser()

    private val networkServer = NetworkServer(
        onAudioPacketReceived = { audioPacket ->
            processReceivedPacket(audioPacket)
        },
        onMuteStateChanged = { muted ->
            _isMuted.value = muted
        },
        onPluginSyncReceived = { syncMessage ->
            _pluginSyncReceived.value = syncMessage
        }
    )
    
    init {
        // Start mDNS advertisement immediately so Android clients can discover this server
        scope.launch(Dispatchers.IO) {
            try {
                val settings = SettingsFactory.getSettings()
                val port = settings.getString("port", "6000").toIntOrNull() ?: 6000
                mdnsAdvertiser.advertise(port)
            } catch (e: Exception) {
                Logger.w("AudioEngine", "Failed to start mDNS advertisement: ${e.message}")
            }
        }

        scope.launch {
            networkServer.state.collect { newState ->
                if (newState == StreamState.Streaming) {
                    audioPipeline.reset()
                    startAudioProcessing()
                } else if (newState == StreamState.Idle || newState == StreamState.Error) {
                    stopAudioProcessing()
                }
                _state.value = newState
            }
        }
        scope.launch {
            networkServer.lastError.collect { error ->
                if (error != null) {
                    _lastError.value = error
                }
            }
        }

        scope.launch {
            webModeService.state.collect { newState ->
                if (newState == StreamState.Streaming) {
                    audioPipeline.reset()
                    startAudioProcessing()
                    startWebAudioFeeding()
                } else if (newState == StreamState.Idle || newState == StreamState.Error) {
                    stopAudioProcessing()
                    stopWebAudioFeeding()
                }
                if (_state.value != StreamState.Streaming) {
                    _state.value = newState
                }
            }
        }
        scope.launch {
            webModeService.lastError.collect { error ->
                if (error != null) {
                    _lastError.value = error
                }
            }
        }
    }
    
    private fun startAudioProcessing() {
        if (audioProcessingJob?.isActive == true) return
        
        audioProcessingJob = scope.launch(Dispatchers.Default) {
            Logger.d("AudioEngine", "音频处理协程已启动")
            while (isActive) {
                try {
                    val audioPacket = audioPacketChannel.receiveCatching().getOrNull() ?: break
                    
                    if (!audioOutputManager.init(audioPacket.sampleRate, audioPacket.channelCount)) {
                        Logger.e("AudioEngine", "初始化音频输出失败")
                        continue
                    }
    val queuedMs = audioOutputManager.getQueuedDurationMs()

                    // 保存当前音频参数用于计算比特率
                    currentSampleRate = audioPacket.sampleRate
                    currentChannelCount = audioPacket.channelCount
                    currentAudioFormatValue = audioPacket.audioFormat

                    val processedBuffer = audioPipeline.process(
                        inputBuffer = audioPacket.buffer,
                        audioFormat = audioPacket.audioFormat,
                        channelCount = audioPacket.channelCount,
                        queuedDurationMs = queuedMs
                    )

                    if (processedBuffer != null) {
                        audioOutputManager.write(processedBuffer, 0, processedBuffer.size)
    val levelData = calculateAudioLevelData(processedBuffer)
                        _audioLevels.value = levelData.rms
                        _audioLevelData.value = levelData

                        // 更新音频指标
                        updateAudioMetrics(queuedMs)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Logger.e("AudioEngine", "音频处理错误", e)
                }
            }
            Logger.d("AudioEngine", "音频处理协程已停止")
        }
    }
    
    private fun stopAudioProcessing() {
        audioProcessingJob?.cancel()
        audioProcessingJob = null
        while (audioPacketChannel.tryReceive().isSuccess) {
        }
    }

    actual val installProgress: Flow<String?> = VirtualAudioDeviceManager.installProgress
    
    actual suspend fun installDriver() {
        VirtualAudioDeviceManager.installVirtualDevice()
    }
    
    actual fun updateConfig(
        enableNS: Boolean,
        nsType: NoiseReductionType,
        enableAGC: Boolean,
        agcTargetLevel: Int,
        enableVAD: Boolean,
        vadThreshold: Int,
        enableDereverb: Boolean,
        dereverbLevel: Float,
        amplification: Float
    ) {
        audioPipeline.updateConfig(
            enableNS, nsType, enableAGC, agcTargetLevel,
            enableVAD, vadThreshold, enableDereverb, dereverbLevel,
            amplification
        )
        
        if (System.getProperty("micyou.debugAudioConfig") == "true") {
            Logger.d("AudioEngine", "配置更新: 放大器=$amplification, VAD=$enableVAD ($vadThreshold), AGC=$enableAGC ($agcTargetLevel), NS=$enableNS ($nsType)")
        }
    }

    actual suspend fun start(
        ip: String, 
        port: Int, 
        mode: ConnectionMode, 
        isClient: Boolean,
        sampleRate: SampleRate,
        channelCount: ChannelCount,
        audioFormat: AudioFormat
    ) {
        if (isClient) return 
        Logger.i("AudioEngine", "启动 JVM AudioEngine: 模式=$mode, 端口=$port, 采样率=${sampleRate.value}, 声道=${channelCount.label}, 格式=${audioFormat.label}")
        
        _lastError.value = null
        
        if (mode == ConnectionMode.Usb) {
            Logger.i("AudioEngine", "正在为 USB 模式执行 ADB reverse，端口 $port")
            if (AdbManager.runAdbReverse(port)) {
                Logger.i("AudioEngine", "ADB reverse 成功，USB 隧道已建立")
            } else {
                val errorMsg = String.format(getString(Res.string.errorAdbReverseFailed), port)
                Logger.e("AudioEngine", errorMsg)
                _lastError.value = errorMsg
                _state.value = StreamState.Error
                throw Exception(errorMsg)
            }
        }
        
        startStopMutex.withLock {
            // 等待任何正在进行的停止操作完成，防止竞态条件
            stopJob?.join()
            stopJob = null

            val currentJob = job
            if (currentJob != null && !currentJob.isCompleted) {
                Logger.w("AudioEngine", "AudioEngine 已在运行，忽略启动请求")
            } else {
                if (mode == ConnectionMode.Web) {
                    val webPort = if (port in 1..65535) port else 0
                    Logger.i("AudioEngine", "启动 Web 服务器，端口: $webPort")
                    webModeService.start(webPort)
                    Logger.i("AudioEngine", "WebModeService started successfully")
                } else {
                    // 直接调用 networkServer.start()，不 launch 新协程
                    // NetworkServer 内部会管理自己的协程
                    networkServer.start(port)
                    Logger.i("AudioEngine", "NetworkServer started successfully")
                }
            }
        }
    }

    private suspend fun processReceivedPacket(audioPacket: AudioPacketMessage) {
        try {
            audioPacketChannel.send(audioPacket)
        } catch (e: Exception) {
            Logger.e("AudioEngine", "发送音频包到处理通道失败", e)
        }
    }
    
    actual suspend fun setMute(muted: Boolean) {
        _isMuted.value = muted
        networkServer.sendMuteState(muted)
    }
    
    suspend fun sendPluginSync(plugins: List<PluginInfoMessage>, platform: String) {
        networkServer.sendPluginSync(plugins, platform)
    }
    
    actual fun setMonitoring(enabled: Boolean) {
        audioOutputManager.setMonitoring(enabled)
    }

    actual fun setStreamingNotificationEnabled(enabled: Boolean) {
    }

    actual fun setAudioSource(sourceName: String) {
        // JVM 端不支持音频源选择
    }

    actual fun stop() {
         try {
             job?.cancel()
             job = null
             // 使用协程异步停止，避免阻塞调用线程
             // 保存停止 Job 以便 start() 可以等待其完成，防止竞态条件
             // 检查是否已有活跃的停止操作，避免协程泄漏
             if (stopJob?.isActive != true) {
                 stopJob = scope.launch {
                     try {
                         withTimeoutOrNull(Constants.SERVER_STOP_TIMEOUT_MS) {
                             networkServer.stop()
                         } ?: Logger.w("AudioEngine", "NetworkServer stop timeout after ${Constants.SERVER_STOP_TIMEOUT_MS}ms")
                     } catch (e: Exception) {
                         Logger.e("AudioEngine", "Error in async stop: ${e.message}", e)
                     }

                     try {
                        webModeService.stop()
                    } catch (e: Exception) {
                        Logger.e("AudioEngine", "Error stopping WebModeService: ${e.message}", e)
                    }
                 }
             } else {
                 Logger.d("AudioEngine", "Stop operation already in progress, skipping duplicate stop request")
             }
             stopWebAudioFeeding()
             _lastError.value = null
             _state.value = StreamState.Idle
         } catch (e: Exception) {
             Logger.e("AudioEngine", "Error stopping audio engine: ${e.message}", e)
         } finally {
             audioOutputManager.release()
             audioPipeline.release()
             mdnsAdvertiser.close()
         }
    }

    private fun startWebAudioFeeding() {
        if (webAudioFeedingJob?.isActive == true) return

        webAudioFeedingJob = scope.launch(Dispatchers.Default) {
            Logger.d("AudioEngine", "Web 音频馈送协程已启动")
            while (isActive) {
                try {
                    val audioPacket = webModeService.receiveAudioPacket() ?: continue
                    processReceivedPacket(audioPacket)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Logger.e("AudioEngine", "Web 音频馈送错误", e)
                }
            }
            Logger.d("AudioEngine", "Web 音频馈送协程已停止")
        }
    }

    private fun stopWebAudioFeeding() {
        webAudioFeedingJob?.cancel()
        webAudioFeedingJob = null
    }
    
    /**
     * 计算 16-bit PCM 音频数据的电平数据。
     * 返回 RMS、峰值和分贝值。
     * 注意：此方法只处理 16-bit 格式，因为 AudioProcessorPipeline 已将所有格式转换为 16-bit。
     */
    private fun calculateAudioLevelData(buffer: ByteArray): AudioLevelData {
        if (buffer.isEmpty()) return AudioLevelData.SILENT

        var sum = 0.0
        var maxSample = 0.0
        var count = 0
        var i = 0
        while (i + 1 < buffer.size) {
            val lo = buffer[i].toInt() and 0xFF
            val hi = buffer[i + 1].toInt()
    val sample = (hi shl 8) or lo
            val normalized = sample / 32768.0

            sum += normalized * normalized
            maxSample = maxOf(maxSample, kotlin.math.abs(normalized))
            count++
            i += 2
        }

        if (count == 0) return AudioLevelData.SILENT

        val rms = sqrt(sum / count).toFloat().coerceIn(0f, 1f)
    val peak = maxSample.toFloat().coerceIn(0f, 1f)

        return AudioLevelData.fromRmsAndPeak(rms, peak)
    }

    /**
     * 更新音频指标（比特率、延迟、丢包、抖动等）
     */
    private fun updateAudioMetrics(latencyMs: Long) {
        if (currentSampleRate <= 0 || currentChannelCount <= 0) return

        val bitsPerSample = when (currentAudioFormatValue) {
            4, 32 -> 32  // PCM_FLOAT
            6, 24 -> 24  // PCM_24BIT
            3, 8 -> 8    // PCM_8BIT
            else -> 16   // PCM_16BIT
        }
    val bitrate = AudioMetrics.calculateBitrate(currentSampleRate, currentChannelCount, bitsPerSample)
    val udpStats = networkServer.getUdpStats()
    val rtt = networkServer.getRtt()
    
    val metrics = AudioMetrics(
            bitrate = bitrate,
            sampleRate = currentSampleRate,
            latencyMs = latencyMs + rtt, // 估算总延迟 = 缓冲区延迟 + 网络延迟
            networkLatencyMs = rtt,
            packetLossRate = udpStats?.lossRate ?: 0.0,
            jitterMs = udpStats?.jitter ?: 0.0,
            bufferDurationMs = latencyMs
        )
        _audioMetrics.value = metrics

        // Log audio status periodically (once per minute)
        val now = System.currentTimeMillis()
        if (now - lastStatusLogTime >= 60_000) {
            lastStatusLogTime = now
            Logger.i("AudioEngine", "Audio Status Report: RTT=${rtt}ms, Loss=${String.format("%.2f", metrics.packetLossRate)}%, Jitter=${String.format("%.2f", metrics.jitterMs)}ms, Buffer=${latencyMs}ms, Bitrate=${bitrate/1000}kbps, SampleRate=${currentSampleRate}Hz")
        }
    }

    /**
     * 更新性能配置
     */
    actual fun updatePerformanceConfig(config: PerformanceConfig) {
        audioPipeline.updatePerformanceConfig(config)
        Logger.d("AudioEngine", "性能配置已更新: shortsCapacity=${config.initialShortsCapacity}, growthFactor=${config.bufferGrowthFactor}")
    }
}
