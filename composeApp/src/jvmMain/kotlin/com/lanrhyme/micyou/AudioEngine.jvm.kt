package com.lanrhyme.micyou

import com.lanrhyme.micyou.audio.AudioOutputManager
import com.lanrhyme.micyou.audio.AudioProcessorPipeline
import com.lanrhyme.micyou.audio.AudioSpectrumAnalyzer
import micyou.composeapp.generated.resources.Res
import micyou.composeapp.generated.resources.errorAdbReverseFailed
import micyou.composeapp.generated.resources.errorIpChangeRestartFailed
import org.jetbrains.compose.resources.getString
import com.lanrhyme.micyou.network.MdnsAdvertiser
import com.lanrhyme.micyou.network.NetworkAddressChangeEvent
import com.lanrhyme.micyou.network.NetworkAddressChangeListener
import com.lanrhyme.micyou.network.NetworkAddressMonitor
import com.lanrhyme.micyou.network.NetworkServer
import com.lanrhyme.micyou.network.WebServer
import com.lanrhyme.micyou.platform.AdbManager
import com.lanrhyme.micyou.platform.PlatformInfo
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.sqrt

actual class AudioEngine actual constructor() {
    private val _state = MutableStateFlow(StreamState.Idle)
    actual val streamState: Flow<StreamState> = _state
    private val _audioLevels = MutableStateFlow(0f)
    actual val audioLevels: Flow<Float> = _audioLevels
    
    private val _rawSpectrum = MutableStateFlow(FloatArray(0))
    actual val rawSpectrum: Flow<FloatArray> = _rawSpectrum.asStateFlow()
    
    private val _processedSpectrum = MutableStateFlow(FloatArray(0))
    actual val processedSpectrum: Flow<FloatArray> = _processedSpectrum.asStateFlow()
    
    private val rawSpectrumAnalyzer = AudioSpectrumAnalyzer()
    private val processedSpectrumAnalyzer = AudioSpectrumAnalyzer()

    private val _audioLevelData = MutableStateFlow(AudioLevelData.SILENT)
    actual val audioLevelData: Flow<AudioLevelData> = _audioLevelData
    private val _audioMetrics = MutableStateFlow<AudioMetrics?>(null)
    actual val audioMetrics: Flow<AudioMetrics?> = _audioMetrics
    private val _lastError = MutableStateFlow<String?>(null)
    actual val lastError: Flow<String?> = _lastError

    private val _isMuted = MutableStateFlow(false)
    actual val isMuted: Flow<Boolean> = _isMuted
    
    private val _pluginSyncReceived = MutableStateFlow<PluginSyncMessage?>(null)
    val pluginSyncReceived: Flow<PluginSyncMessage?> = _pluginSyncReceived
    
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var audioProcessingJob: Job? = null
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
    private val networkAddressMonitor = NetworkAddressMonitor()
    private val networkAddressChangeListener = object : NetworkAddressChangeListener {
        override fun onAddressChanged(event: NetworkAddressChangeEvent) {
            handleIpAddressChanged(event)
        }
    }

    // Track current mode and config for IP change restart. Guard with startStopMutex.
    private var currentMode: ConnectionMode? = null
    private var currentPort: Int = -1
    private var currentBindAddress: String = "0.0.0.0"
    private var currentTransportProtocol: TransportProtocol = TransportProtocol.Both

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

    private val webServer = WebServer(
        port = Constants.DEFAULT_WEB_PORT,
        onAudioPacketReceived = { audioPacket ->
            scope.launch { processReceivedPacket(audioPacket) }
        }
    )

    private val _webUrl = MutableStateFlow("")
    private val _webClientCount = MutableStateFlow(0)
    actual val webUrl: Flow<String> = _webUrl.asStateFlow()
    actual val webClientCount: Flow<Int> = _webClientCount.asStateFlow()

    private data class RestartConfig(
        val mode: ConnectionMode,
        val port: Int,
        val bindAddress: String,
        val transportProtocol: TransportProtocol
    )

    init {
        // Start mDNS advertisement immediately so Android clients can discover this server
        scope.launch(Dispatchers.IO) {
            try {
                val settings = SettingsFactory.getSettings()
                val port = settings.getString("port", Constants.DEFAULT_TCP_PORT.toString()).toIntOrNull() ?: Constants.DEFAULT_TCP_PORT
                mdnsAdvertiser.advertise(port)
            } catch (e: Exception) {
                Logger.w("AudioEngine", "Failed to start mDNS advertisement: ${e.message}")
            }
        }

        // Register IP change listener. Monitoring is started only while a stream is active.
        networkAddressMonitor.addListener(networkAddressChangeListener)

        scope.launch {
            networkServer.state.collect { newState ->
                if (newState == StreamState.Streaming) {
                    audioPipeline.reset()
                    startAudioProcessing()
                } else if (newState == StreamState.Connecting || newState == StreamState.Idle || newState == StreamState.Error) {
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
            webServer.state.collect { newState ->
                if (newState == StreamState.Streaming) {
                    audioPipeline.reset()
                    if (_state.value != StreamState.Streaming) {
                        startAudioProcessing()
                    }
                } else if (newState == StreamState.Idle || newState == StreamState.Error) {
                    val hasNetworkClients = networkServer.state.value == StreamState.Streaming
                    if (!hasNetworkClients) {
                        stopAudioProcessing()
                    }
                }
                if (_state.value != StreamState.Streaming || newState != StreamState.Streaming) {
                    _state.value = newState
                }
            }
        }
        scope.launch {
            webServer.lastError.collect { error ->
                if (error != null) {
                    _lastError.value = error
                }
            }
        }
        scope.launch {
            webServer.clientCountFlow.collect { count ->
                _webClientCount.value = count
            }
        }
    }

    /**
     * Handles local IP address changes while using automatic bind mode.
     */
    private fun handleIpAddressChanged(event: NetworkAddressChangeEvent) {
        val newPrimaryIp = event.newPrimaryIp ?: return
        Logger.i("AudioEngine", "Handling IP change: ${event.oldPrimaryIp} -> $newPrimaryIp")

        scope.launch(Dispatchers.IO) {
            try {
                val restartConfig = startStopMutex.withLock {
                    if (currentBindAddress != "0.0.0.0") {
                        Logger.d("AudioEngine", "Manual bind mode (currentBindAddress=$currentBindAddress), skipping auto IP change")
                        return@withLock null
                    }

                    val mode = currentMode ?: return@withLock null
                    if (_state.value != StreamState.Streaming && _state.value != StreamState.Connecting) {
                        Logger.d("AudioEngine", "No active stream, skipping server restart")
                        return@withLock null
                    }

                    RestartConfig(mode, currentPort, currentBindAddress, currentTransportProtocol)
                }

                val config = restartConfig ?: return@launch
                restartForIpChange(config, newPrimaryIp)
            } catch (e: Exception) {
                Logger.e("AudioEngine", "Failed to restart server after IP change", e)
                _lastError.value = String.format(getString(Res.string.errorIpChangeRestartFailed), e.message ?: "")
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

                    // 计算原始频谱 (Raw Spectrum)
                    val rawShorts = audioPipeline.convertToShorts(audioPacket.buffer, audioPacket.audioFormat)
                    if (rawShorts != null) {
                        _rawSpectrum.value = rawSpectrumAnalyzer.calculateSpectrum(rawShorts)

                        val processedBuffer = audioPipeline.process(
                            inputShorts = rawShorts,
                            channelCount = audioPacket.channelCount,
                            sampleRate = audioPacket.sampleRate,
                            queuedDurationMs = queuedMs
                        )

                        if (processedBuffer != null) {
                            // 计算处理后频谱 (Processed Spectrum)
                            // 注意：processedBuffer 始终是 16-bit PCM (value = 2)
                            _processedSpectrum.value = processedSpectrumAnalyzer.calculateSpectrumFromBytes(processedBuffer)

                            audioOutputManager.write(processedBuffer, 0, processedBuffer.size)
                            val levelData = calculateAudioLevelData(processedBuffer)
                            _audioLevels.value = levelData.rms
                            _audioLevelData.value = levelData

                            // 更新音频指标
                            updateAudioMetrics(queuedMs)
                        }
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
        equalizerConfig: EqualizerConfig
    ) {
        audioPipeline.updateConfig(
            enableNS = enableNS,
            nsType = nsType,
            nsIntensity = nsIntensity,
            enableAGC = enableAGC,
            agcTargetLevel = agcTargetLevel,
            agcAttackRate = agcAttackRate,
            agcDecayRate = agcDecayRate,
            enableVAD = enableVAD,
            vadThreshold = vadThreshold,
            enableDereverb = enableDereverb,
            dereverbLevel = dereverbLevel,
            amplification = amplification,
            newProcessingChain = processingChain,
            equalizerConfig = equalizerConfig
        )
        
        if (System.getProperty("micyou.debugAudioConfig") == "true") {
            Logger.d("AudioEngine", "配置更新: 放大器=$amplification, VAD=$enableVAD ($vadThreshold), AGC=$enableAGC ($agcTargetLevel), NS=$enableNS ($nsType, $nsIntensity), EQ=${equalizerConfig.enabled}")
        }
    }

    actual suspend fun start(
        ip: String,
        port: Int,
        mode: ConnectionMode,
        isClient: Boolean,
        sampleRate: SampleRate,
        channelCount: ChannelCount,
        audioFormat: AudioFormat,
        transportProtocol: TransportProtocol
    ) {
        if (isClient) return
        Logger.i("AudioEngine", "启动 JVM AudioEngine: 模式=$mode, 协议=$transportProtocol, 端口=$port, 采样率=${sampleRate.value}, 声道=${channelCount.label}, 格式=${audioFormat.label}")

        startStopMutex.withLock {
            startLocked(ip, port, mode, sampleRate, channelCount, audioFormat, transportProtocol)
        }
    }

    private suspend fun startLocked(
        ip: String,
        port: Int,
        mode: ConnectionMode,
        sampleRate: SampleRate,
        channelCount: ChannelCount,
        audioFormat: AudioFormat,
        transportProtocol: TransportProtocol
    ) {
        if (_state.value == StreamState.Streaming || _state.value == StreamState.Connecting) {
            Logger.w("AudioEngine", "AudioEngine 已在运行，忽略启动请求")
            return
        }

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

        currentMode = mode
        currentPort = if (mode == ConnectionMode.Web) port.takeIf { it in 1..65535 } ?: Constants.DEFAULT_WEB_PORT else port
        currentTransportProtocol = transportProtocol

        if (mode == ConnectionMode.Web) {
            val webPort = currentPort
            Logger.i("AudioEngine", "启动 Web 模式，端口=$webPort")

            val bindAddress = ip.takeIf { it.isNotBlank() } ?: "0.0.0.0"
            val displayIp = if (bindAddress == "0.0.0.0") getPreferredLocalIpAddress() else bindAddress
            val webUrlStr = "https://$displayIp:$webPort"
            _webUrl.value = webUrlStr
            currentBindAddress = bindAddress

            webServer.start(webPort, bindAddress)
            runCatching { mdnsAdvertiser.reAdvertise(webPort, bindAddress) }
                .onFailure { Logger.w("AudioEngine", "mDNS advertise failed: ${it.message}") }
            updateNetworkAddressMonitor(bindAddress)
            Logger.i("AudioEngine", "WebServer started at $webUrlStr")
            return
        }

        val bindAddress = ip.takeIf { it.isNotBlank() } ?: getPreferredLocalIpAddress()
        currentBindAddress = bindAddress
        networkServer.start(port, bindAddress, transportProtocol, mode)
        scope.launch(Dispatchers.IO) {
            runCatching { mdnsAdvertiser.reAdvertise(port, bindAddress) }
                .onFailure { Logger.w("AudioEngine", "mDNS advertise failed: ${it.message}") }
        }
        updateNetworkAddressMonitor(bindAddress)
        Logger.i("AudioEngine", "NetworkServer started successfully on $bindAddress:$port")
    }

    private fun updateNetworkAddressMonitor(bindAddress: String) {
        if (bindAddress == "0.0.0.0") {
            networkAddressMonitor.start()
        } else {
            networkAddressMonitor.stop()
        }
    }

    private suspend fun restartForIpChange(config: RestartConfig, newPrimaryIp: String) {
        startStopMutex.withLock {
            if (currentMode != config.mode || currentPort != config.port || currentBindAddress != config.bindAddress) {
                Logger.d("AudioEngine", "Stream config changed during IP restart, skipping stale restart")
                return
            }

            when (config.mode) {
                ConnectionMode.Wifi -> {
                    Logger.i("AudioEngine", "Restarting NetworkServer with new IP: $newPrimaryIp")
                    networkServer.stop()
                    networkServer.start(config.port, config.bindAddress, config.transportProtocol, config.mode)
                    runCatching { mdnsAdvertiser.reAdvertise(config.port, config.bindAddress) }
                        .onFailure { Logger.w("AudioEngine", "mDNS re-advertise failed: ${it.message}") }
                }
                ConnectionMode.Web -> {
                    Logger.i("AudioEngine", "Restarting WebServer with new IP: $newPrimaryIp")
                    _webUrl.value = "https://$newPrimaryIp:${config.port}"
                    webServer.stop()
                    webServer.start(config.port, config.bindAddress)
                    runCatching { mdnsAdvertiser.reAdvertise(config.port, config.bindAddress) }
                        .onFailure { Logger.w("AudioEngine", "mDNS re-advertise failed: ${it.message}") }
                }
                else -> {
                    Logger.d("AudioEngine", "IP restart skipped for mode ${config.mode}")
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
        audioOutputManager.setAudioSource(sourceName)
        // 如果当前正在推流，需要重启音频输出以切换设备
        if (_state.value == StreamState.Streaming) {
            Logger.i("AudioEngine", "Audio source changed while streaming, re-initializing output...")
            audioOutputManager.init(currentSampleRate, currentChannelCount)
        }
    }

    actual fun stop() {
        stopAudioProcessing()
        scope.launch(Dispatchers.IO) {
            stopAndWait()
        }
    }

    /**
     * 挂起函数版本的 stop，确保停止操作完全完成后再返回。
     * 用于 IP 切换等需要严格顺序的场景。
     */
    actual suspend fun stopAndWait() {
        startStopMutex.withLock {
            stopLocked()
        }
    }

    private suspend fun stopLocked() {
        _lastError.value = null

        currentMode = null
        currentPort = -1
        currentBindAddress = "0.0.0.0"
        currentTransportProtocol = TransportProtocol.Both
        stopAudioProcessing()

        try {
            withTimeoutOrNull(Constants.SERVER_STOP_TIMEOUT_MS) {
                networkServer.stop()
            } ?: Logger.w("AudioEngine", "NetworkServer stop timeout after ${Constants.SERVER_STOP_TIMEOUT_MS}ms")
        } catch (e: Exception) {
            Logger.e("AudioEngine", "Error stopping NetworkServer: ${e.message}", e)
        }
        try {
            webServer.stop()
            _webUrl.value = ""
            _webClientCount.value = 0
        } catch (e: Exception) {
            Logger.w("AudioEngine", "Error stopping WebServer: ${e.message}")
        }
        try {
            audioOutputManager.release()
        } catch (e: Exception) {
            Logger.w("AudioEngine", "Error releasing AudioOutputManager: ${e.message}")
        }
        try {
            audioPipeline.release()
        } catch (e: Exception) {
            Logger.w("AudioEngine", "Error releasing AudioProcessorPipeline: ${e.message}")
        }
        try {
            mdnsAdvertiser.close()
        } catch (e: Exception) {
            Logger.w("AudioEngine", "Error closing MdnsAdvertiser: ${e.message}")
        }
        try {
            networkAddressMonitor.stop()
        } catch (e: Exception) {
            Logger.w("AudioEngine", "Error stopping NetworkAddressMonitor: ${e.message}")
        }
        _state.value = StreamState.Idle
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
