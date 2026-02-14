package com.lanrhyme.micyou

import io.ktor.network.selector.*
import io.ktor.network.sockets.*
import io.ktor.utils.io.*
import io.ktor.utils.io.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.protobuf.*
import kotlinx.serialization.*
import java.net.BindException
import java.io.IOException
import java.io.EOFException
import javax.sound.sampled.*
import kotlin.math.*
import java.nio.ByteBuffer
import java.nio.ByteOrder

import de.maxhenkel.rnnoise4j.Denoiser
import javax.bluetooth.*
import javax.microedition.io.*
import io.ktor.utils.io.jvm.javaio.*
import java.io.OutputStream
import kotlin.coroutines.CoroutineContext
import java.nio.file.Files

@OptIn(DelicateCoroutinesApi::class)
fun OutputStream.toByteWriteChannel(context: CoroutineContext = Dispatchers.IO): ByteWriteChannel = GlobalScope.reader(context, autoFlush = true) {
    val buffer = ByteArray(4096)
    while (!channel.isClosedForRead) {
        val count = channel.readAvailable(buffer)
        if (count == -1) break
        this@toByteWriteChannel.write(buffer, 0, count)
        this@toByteWriteChannel.flush()
    }
}.channel

@OptIn(ExperimentalSerializationApi::class)
actual class AudioEngine actual constructor() {
    private val _state = MutableStateFlow(StreamState.Idle)
    actual val streamState: Flow<StreamState> = _state
    private val _audioLevels = MutableStateFlow(0f)
    actual val audioLevels: Flow<Float> = _audioLevels
    private val _lastError = MutableStateFlow<String?>(null)
    actual val lastError: Flow<String?> = _lastError
    
    private val _isMuted = MutableStateFlow(false)
    actual val isMuted: Flow<Boolean> = _isMuted
    
    private var job: Job? = null
    private val startStopMutex = Mutex()
    private val proto = ProtoBuf { }
    private val CHECK_1 = "MicYouCheck1"
    private val CHECK_2 = "MicYouCheck2"
    
    private var serverSocket: ServerSocket? = null
    private var btNotifier: StreamConnectionNotifier? = null
    @Volatile
    private var activeSocket: Socket? = null
    private var activeBtConnection: StreamConnection? = null
    
    private var monitoringLine: SourceDataLine? = null
    private var isUsingCable = false
    private var selectorManager: SelectorManager? = null
    
    // 发送消息的通道（控制）
    private var sendChannel: Channel<MessageWrapper>? = null
    
    @Volatile
    private var isMonitoring = false
    
    // 配置状态
    @Volatile private var enableNS: Boolean = false
    @Volatile private var nsType: NoiseReductionType = NoiseReductionType.Ulunas
    @Volatile private var enableAGC: Boolean = false
    @Volatile private var agcTargetLevel: Int = 32000
    @Volatile private var enableVAD: Boolean = false
    @Volatile private var vadThreshold: Int = 10
    @Volatile private var enableDereverb: Boolean = false
    @Volatile private var dereverbLevel: Float = 0.5f
    @Volatile private var amplification: Float = 10.0f
    
    // 内部音频处理状态
    private var agcEnvelope: Float = 0f

    // RNNoise 实例
    private var denoiserLeft: Denoiser? = null
    private var denoiserRight: Denoiser? = null

    // Ulunas 音频处理器实例
    private var ulunasProcessorLeft: AudioProcessor? = null
    private var ulunasProcessorRight: AudioProcessor? = null

    // Ulunas 模型路径（缓存）
    private var ulunasModelPath: String? = null

    private var dereverbBufferLeft: IntArray? = null
    private var dereverbBufferRight: IntArray? = null
    private var dereverbIndex: Int = 0
    private var lastProcessedChannelCount: Int = -1
    private var scratchShorts: ShortArray = ShortArray(0)
    private var scratchResultBuffer: ByteArray = ByteArray(0)
    private var rnnoiseFrameLeft: ShortArray = ShortArray(0)
    private var rnnoiseFrameRight: ShortArray = ShortArray(0)
    private var ulunasFrameLeft: FloatArray = FloatArray(0)
    private var ulunasFrameRight: FloatArray = FloatArray(0)
    private var playbackRatio: Double = 1.0
    private var playbackRatioIntegral: Double = 0.0
    private var resamplePosFrames: Double = 0.0
    private var resamplePrevFrame: ShortArray = ShortArray(0)
    private var scratchResampledShorts: ShortArray = ShortArray(0)

    // 存储原始音频输出设备（用于Linux平台恢复）
    private var originalDefaultSink: String? = null

    actual val installProgress: Flow<String?> = VBCableManager.installProgress
    
    actual suspend fun installDriver() {
        VBCableManager.installVBCable()
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
        this.enableNS = enableNS
        this.nsType = nsType
        this.enableAGC = enableAGC
        this.agcTargetLevel = agcTargetLevel
        this.enableVAD = enableVAD
        this.vadThreshold = vadThreshold
        this.enableDereverb = enableDereverb
        this.dereverbLevel = dereverbLevel
        this.amplification = amplification
        
        if (System.getProperty("micyou.debugAudioConfig") == "true") {
            Logger.d("AudioEngine", "Config updated: Amp=$amplification, VAD=$enableVAD ($vadThreshold), AGC=$enableAGC ($agcTargetLevel), NS=$enableNS ($nsType)")
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
        Logger.i("AudioEngine", "Starting JVM AudioEngine: mode=$mode, port=$port, sampleRate=${sampleRate.value}, channels=${channelCount.label}, format=${audioFormat.label}")
        
        // 在Linux平台重定向音频输出到虚拟设备
        if (PlatformUtils.isLinux) {
            originalDefaultSink = PlatformUtils.getDefaultSink()
            Logger.i("AudioEngine", "Linux平台：保存原始默认音频输出设备: $originalDefaultSink")
            
            val success = PlatformUtils.redirectAudioToVirtualDevice()
            if (success) {
                Logger.i("AudioEngine", "成功重定向音频输出到虚拟设备")
            } else {
                Logger.w("AudioEngine", "音频重定向失败，可能影响内录功能")
            }
        }
        
        _lastError.value = null // 清除之前的错误
        
        // 如果是USB模式，先执行ADB reverse
        if (mode == ConnectionMode.Usb) {
            try {
                Logger.i("AudioEngine", "Executing ADB reverse for USB mode on port $port")
                AdbManager.runAdbReverse(port)
                Logger.i("AudioEngine", "ADB reverse successful, USB tunnel established")
            } catch (e: Exception) {
                val errorMsg = "ADB reverse 失败: ${e.message}\n请确保已安装ADB并连接Android设备，然后在终端执行：\nadb reverse tcp:$port tcp:$port"
                Logger.e("AudioEngine", errorMsg, e)
                _lastError.value = errorMsg
                _state.value = StreamState.Error
                return
            }
        }
        
        val jobToJoin = startStopMutex.withLock {
            val currentJob = job
            if (currentJob != null && !currentJob.isCompleted) {
                Logger.w("AudioEngine", "AudioEngine already running, ignoring start request")
                null
            } else {
                _state.value = StreamState.Connecting
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        if (mode == ConnectionMode.Bluetooth) {
                            Logger.i("AudioEngine", "Starting Bluetooth server")
                            while (isActive) {
                                try {
                                    val localDevice = LocalDevice.getLocalDevice()
                                    localDevice.discoverable = DiscoveryAgent.GIAC
                                    Logger.i("AudioEngine", "Local Bluetooth: name=${localDevice.friendlyName}, address=${localDevice.bluetoothAddress}")
                                    
                                    val uuid = javax.bluetooth.UUID("0000110100001000800000805F9B34FB", false)
                                    val url = "btspp://localhost:$uuid;name=MicYouServer"
                                    
                                    btNotifier = Connector.open(url) as StreamConnectionNotifier
                                    Logger.i("AudioEngine", "Bluetooth service started: $url")
                                    
                                    while (isActive) {
                                        val connection = btNotifier?.acceptAndOpen() ?: break
                                        activeBtConnection = connection
                                        Logger.i("AudioEngine", "Accepted Bluetooth connection")
                                        _state.value = StreamState.Streaming
                                        _lastError.value = null
                                        
                                        try {
                                            val input = connection.openInputStream().toByteReadChannel()
                                            val output = connection.openOutputStream().toByteWriteChannel()
                                            handleConnection(input, output)
                                        } catch (e: Exception) {
                                            if (!isNormalDisconnect(e)) {
                                                Logger.e("AudioEngine", "Bluetooth connection error", e)
                                                _lastError.value = "蓝牙连接错误: ${e.message}"
                                            }
                                        } finally {
                                            activeBtConnection = null
                                            try {
                                                connection.close()
                                            } catch (e: Exception) {}
                                            Logger.i("AudioEngine", "Bluetooth connection closed, waiting for new connection")
                                            _state.value = StreamState.Connecting
                                        }
                                    }
                                } catch (e: Exception) { // Bluetooth specific errors
                                    if (isActive) {
                                        Logger.e("AudioEngine", "Bluetooth service error", e)
                                        // Only show error if we really failed to init or loop crashed repeatedly
                                        if (_state.value != StreamState.Connecting) {
                                             _state.value = StreamState.Error
                                             _lastError.value = "蓝牙服务错误: ${e.message}. 正在尝试重启服务..."
                                        }
                                    }
                                }
                            }
                        } else {
                            // TCP server
                            try {
                                selectorManager = SelectorManager(Dispatchers.IO)
                                serverSocket = aSocket(selectorManager!!).tcp().bind("0.0.0.0", port = port)
                                Logger.i("AudioEngine", "Listening on port $port (0.0.0.0)")
                                
                                while (isActive) {
                                    val socket = serverSocket?.accept() ?: break
                                    activeSocket = socket
                                    Logger.i("AudioEngine", "Accepted TCP connection from ${socket.remoteAddress}")
                                    _state.value = StreamState.Streaming
                                    _lastError.value = null
                                    
                                    try {
                                        val input = socket.openReadChannel()
                                        val output = socket.openWriteChannel(autoFlush = true)
                                        handleConnection(input, output)
                                    } catch (e: Exception) {
                                        if (!isNormalDisconnect(e)) {
                                            Logger.e("AudioEngine", "TCP connection error", e)
                                            _lastError.value = "连接处理错误: ${e.message}"
                                        }
                                    } finally {
                                        activeSocket = null
                                        socket.close()
                                        Logger.i("AudioEngine", "TCP connection closed, waiting for new connection")
                                        _state.value = StreamState.Connecting
                                    }
                                }
                            } catch (e: BindException) {
                                if (isActive) {
                                    val msg = "端口 $port 已被占用。请关闭其他 AndroidMic 实例。"
                                    Logger.e("AudioEngine", msg)
                                    _state.value = StreamState.Error
                                    _lastError.value = msg
                                }
                            }
                        }
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        if (isActive) {
                            if (!isNormalDisconnect(e)) {
                                Logger.e("AudioEngine", "Server error", e)
                                _state.value = StreamState.Error
                                _lastError.value = "服务器错误: ${e.message}"
                            }
                        }
                    } finally {
                        Logger.d("AudioEngine", "Cleaning up server resources")
                        serverSocket?.close()
                        serverSocket = null
                        btNotifier?.close()
                        btNotifier = null
                        selectorManager?.close()
                        selectorManager = null
                        if (_state.value != StreamState.Error) {
                            _state.value = StreamState.Idle
                        }
                        Logger.i("AudioEngine", "AudioEngine stopped")
                    }
                }.also { job = it }
            }
        }
        jobToJoin?.join()
    }

    private suspend fun handleConnection(input: ByteReadChannel, output: ByteWriteChannel) {
        // 握手
        val check1Packet = input.readPacket(CHECK_1.length)
        val check1String = check1Packet.readText()
        
        if (!check1String.equals(CHECK_1)) {
            Logger.e("AudioEngine", "Handshake failed: received $check1String")
            return
        }

        output.writeFully(CHECK_2.encodeToByteArray())
        output.flush()

        sendChannel = Channel(Channel.UNLIMITED)
        
        coroutineScope {
            val writerJob = launch(Dispatchers.IO) {
                for (msg in sendChannel!!) {
                    try {
                        val packetBytes = proto.encodeToByteArray(MessageWrapper.serializer(), msg)
                        val length = packetBytes.size
                        output.writeInt(PACKET_MAGIC)
                        output.writeInt(length)
                        output.writeFully(packetBytes)
                        output.flush()
                    } catch (e: Exception) {
                        break
                    }
                }
            }
        
            sendChannel?.send(MessageWrapper(mute = MuteMessage(_isMuted.value)))

            try {
                agcEnvelope = 0f

                while (currentCoroutineContext().isActive) {
                    val magic = input.readInt()
                    if (magic != PACKET_MAGIC) {
                        var resyncMagic = magic
                        while (currentCoroutineContext().isActive) {
                            val byte = input.readByte().toInt() and 0xFF
                            resyncMagic = (resyncMagic shl 8) or byte
                            if (resyncMagic == PACKET_MAGIC) {
                                break
                            }
                        }
                    }

                    val length = input.readInt()

                    if (length > 2 * 1024 * 1024) {
                        continue
                    }

                    if (length <= 0) continue

                    val packetBytes = ByteArray(length)
                    input.readFully(packetBytes)

                    try {
                        val wrapper: MessageWrapper = proto.decodeFromByteArray(MessageWrapper.serializer(), packetBytes)

                        if (wrapper.mute != null) {
                            _isMuted.value = wrapper.mute.isMuted
                        }

                        val audioPacket = wrapper.audioPacket?.audioPacket
                        if (audioPacket != null) {
                            if (monitoringLine == null) {
                                Logger.d("AudioEngine", "Initializing monitoringLine: SR=${audioPacket.sampleRate}, Channels=${audioPacket.channelCount}")
                                val audioFormat = javax.sound.sampled.AudioFormat(
                                    audioPacket.sampleRate.toFloat(),
                                    16,
                                    audioPacket.channelCount,
                                    true,
                                    false 
                                )

                                val info = DataLine.Info(SourceDataLine::class.java, audioFormat)

                                val mixers = AudioSystem.getMixerInfo()
                                Logger.d("AudioEngine", "Found ${mixers.size} mixers: ${mixers.joinToString { it.name }}")
                                
                                val cableMixerInfo = mixers
                                    .filter { it.name.contains("CABLE Input", ignoreCase = true) }
                                    .find { mixerInfo ->
                                        try {
                                            val mixer = AudioSystem.getMixer(mixerInfo)
                                            mixer.isLineSupported(info)
                                        } catch (e: Exception) {
                                            false
                                        }
                                    }

                                if (cableMixerInfo != null) {
                                    val mixer = AudioSystem.getMixer(cableMixerInfo)
                                    monitoringLine = mixer.getLine(info) as SourceDataLine
                                    isUsingCable = true
                                    Logger.i("AudioEngine", "Using VB-CABLE Input for audio output: ${cableMixerInfo.name}")
                                } else {
                                    Logger.w("AudioEngine", "VB-CABLE Input not found or not supporting format, trying default output")
                                    try {
                                        monitoringLine = AudioSystem.getLine(info) as SourceDataLine
                                        isUsingCable = false
                                        Logger.i("AudioEngine", "Using default system output")
                                    } catch (e: Exception) {
                                        Logger.e("AudioEngine", "Failed to get default system output line", e)
                                    }
                                }

                                val bytesPerSecond = (audioPacket.sampleRate * audioPacket.channelCount * 2).coerceAtLeast(1)
                                val bufferSizeBytes = (bytesPerSecond / 5).coerceIn(8192, 131072)
                                monitoringLine?.open(audioFormat, bufferSizeBytes)
                                monitoringLine?.start()
                            }

                            // 如果积压数据过多（超过5个包），直接丢弃当前包以追赶进度
                            // input.availableForRead 表示通道中缓冲的字节数
                            if (input.availableForRead > length * 5) {
                                continue
                            }

                            val processedBuffer = processAudio(audioPacket.buffer, audioPacket.audioFormat, audioPacket.channelCount)

                            if (processedBuffer != null) {
                                // 只有在既没使用虚拟电缆也没开启监听时才静音
                                // 在Linux平台上，音频需要发送到虚拟设备，不应静音
                                if (!isUsingCable && !isMonitoring && !PlatformUtils.isLinux) {
                                    // Logger.d("AudioEngine", "Silencing buffer: isUsingCable=$isUsingCable, isMonitoring=$isMonitoring")
                                    processedBuffer.fill(0.toByte())
                                }

                                try {
                                    monitoringLine?.write(processedBuffer, 0, processedBuffer.size)
                                } catch (e: Exception) {
                                    Logger.e("AudioEngine", "Error writing to monitoringLine", e)
                                }

                                val rms = calculateRMS(processedBuffer)
                                _audioLevels.value = rms
                            }
                        }
                    } catch (e: Exception) {
                    }
                }
            } catch (e: Exception) {
                if (!isNormalDisconnect(e)) throw e
            } finally {
                writerJob.cancel()
                sendChannel?.close()
                sendChannel = null
                monitoringLine?.drain()
                monitoringLine?.close()
                monitoringLine = null
                try {
                    denoiserLeft?.close()
                    denoiserLeft = null
                    denoiserRight?.close()
                    denoiserRight = null
                    ulunasProcessorLeft?.destroy()
                    ulunasProcessorLeft = null
                    ulunasProcessorRight?.destroy()
                    ulunasProcessorRight = null
                } catch (e: Exception) {
                }
                _audioLevels.value = 0f
            }
        }
    }
    
    actual suspend fun setMute(muted: Boolean) {
        _isMuted.value = muted
        // If connected, send message
        try {
            sendChannel?.send(MessageWrapper(mute = MuteMessage(muted)))
        } catch (e: Exception) {
            Logger.e("AudioEngine", "Failed to send mute message", e)
        }
    }
    
    actual fun setMonitoring(enabled: Boolean) {
        isMonitoring = enabled
    }

    actual fun setStreamingNotificationEnabled(enabled: Boolean) {
    }

    actual fun stop() {
         try {
             job?.cancel()
             job = null
             activeSocket?.close()
             activeSocket = null
             activeBtConnection?.close()
             activeBtConnection = null
             sendChannel?.close()
             sendChannel = null
             serverSocket?.close()
             serverSocket = null
             btNotifier?.close()
             btNotifier = null
             selectorManager?.close()
             selectorManager = null
             _lastError.value = null
             _state.value = StreamState.Idle
         } catch (e: Exception) {
             e.printStackTrace()
         } finally {
             // 在Linux平台恢复原始默认音频输出设备
             if (PlatformUtils.isLinux && originalDefaultSink != null) {
                 Logger.i("AudioEngine", "Linux平台：恢复原始默认音频输出设备: $originalDefaultSink")
                 val success = PlatformUtils.restoreDefaultSink(originalDefaultSink)
                 if (success) {
                     Logger.i("AudioEngine", "成功恢复原始音频输出设备")
                 } else {
                     Logger.w("AudioEngine", "恢复原始音频输出设备失败")
                 }
             }
         }
    }

    private fun isNormalDisconnect(e: Throwable): Boolean {
        if (e is kotlinx.coroutines.CancellationException) return true
        if (e is EOFException) return true
        if (e is ClosedReceiveChannelException) return true
        if (e is IOException) {
            val msg = e.message ?: ""
            if (msg.contains("Socket closed", ignoreCase = true)) return true
            if (msg.contains("Connection reset", ignoreCase = true)) return true
            if (msg.contains("Broken pipe", ignoreCase = true)) return true
        }
        return false
    }
    
    private fun processAudio(buffer: ByteArray, format: Int, channelCount: Int): ByteArray? {
        if (lastProcessedChannelCount != channelCount) {
            lastProcessedChannelCount = channelCount
            dereverbBufferLeft = null
            dereverbBufferRight = null
            dereverbIndex = 0
        }
        // 转换为 ShortArray 进行处理
        val shortsSize = when (format) {
            4, 32 -> buffer.size / 4
            3, 8 -> buffer.size
            else -> buffer.size / 2
        }
        if (shortsSize <= 0) return null
        if (scratchShorts.size != shortsSize) {
            scratchShorts = ShortArray(shortsSize)
        }
        val shorts = scratchShorts
        
        when (format) {
            4, 32 -> { // PCM_FLOAT（32 位浮点数）
                for (i in shorts.indices) {
                    val byteIndex = i * 4
                    // 小端序
                    val bits = (buffer[byteIndex].toInt() and 0xFF) or
                               ((buffer[byteIndex + 1].toInt() and 0xFF) shl 8) or
                               ((buffer[byteIndex + 2].toInt() and 0xFF) shl 16) or
                               ((buffer[byteIndex + 3].toInt() and 0xFF) shl 24)
                    val sample = Float.fromBits(bits)
                    // 截断并转换为 16 位 PCM
                    shorts[i] = (sample * 32767.0f).toInt().coerceIn(-32768, 32767).toShort()
                }
            }
            3, 8 -> { // PCM_8BIT（无符号 8 位）
                 for (i in shorts.indices) {
                     // 8 位 PCM 通常是无符号 0-255，128 为 0
                     val sample = (buffer[i].toInt() and 0xFF) - 128
                     shorts[i] = (sample * 256).toShort()
                 }
            }
            else -> { // PCM_16BIT（默认）
                for (i in shorts.indices) {
                     val byteIndex = i * 2
                     val sample = (buffer[byteIndex].toInt() and 0xFF) or
                                  ((buffer[byteIndex + 1].toInt()) shl 8)
                     shorts[i] = sample.toShort()
                }
            }
        }
        
        var processedShorts = shorts

        var speechProbability: Float? = null

        if (enableNS) {
            when (nsType) {
                NoiseReductionType.RNNoise -> {
                    // RNNoise 降噪
                    if (denoiserLeft == null) denoiserLeft = Denoiser()
                    if (channelCount >= 2 && denoiserRight == null) denoiserRight = Denoiser()

                    val frameSize = 480
                    val framesPerChannel = processedShorts.size / channelCount
                    val frameCount = framesPerChannel / frameSize

                    if (frameCount > 0 && (channelCount == 1 || channelCount == 2)) {
                        if (rnnoiseFrameLeft.size != frameSize) rnnoiseFrameLeft = ShortArray(frameSize)
                        if (channelCount == 2 && rnnoiseFrameRight.size != frameSize) rnnoiseFrameRight = ShortArray(frameSize)
                        val left = rnnoiseFrameLeft
                        val right = if (channelCount == 2) rnnoiseFrameRight else null

                        var probSum = 0f
                        var probN = 0

                        for (f in 0 until frameCount) {
                            val base = f * frameSize * channelCount
                            if (channelCount == 1) {
                                for (i in 0 until frameSize) {
                                    left[i] = processedShorts[base + i]
                                }
                                val p = denoiserLeft!!.denoiseInPlace(left)
                                for (i in 0 until frameSize) {
                                    processedShorts[base + i] = left[i]
                                }
                                probSum += p
                                probN++
                            } else {
                                for (i in 0 until frameSize) {
                                    val idx = base + i * 2
                                    left[i] = processedShorts[idx]
                                    right!![i] = processedShorts[idx + 1]
                                }
                                val pL = denoiserLeft!!.denoiseInPlace(left)
                                val pR = denoiserRight!!.denoiseInPlace(right!!)
                                for (i in 0 until frameSize) {
                                    val idx = base + i * 2
                                    processedShorts[idx] = left[i]
                                    processedShorts[idx + 1] = right!![i]
                                }
                                probSum += ((pL + pR) * 0.5f)
                                probN++
                            }
                        }

                        if (probN > 0) {
                            speechProbability = probSum / probN.toFloat()
                        }
                    }
                }
                NoiseReductionType.Ulunas -> {
                    // Ulunas 降噪
                    val modelPath = getUlnasModelPath()

                    if (ulunasProcessorLeft == null) {
                        ulunasProcessorLeft = AudioProcessor(0f, 0f, modelPath, 960, 480)
                    }
                    if (channelCount >= 2 && ulunasProcessorRight == null) {
                        ulunasProcessorRight = AudioProcessor(0f, 0f, modelPath, 960, 480)
                    }

                    val hopLength = 480
                    val framesPerChannel = processedShorts.size / channelCount
                    val frameCount = framesPerChannel / hopLength

                    if (frameCount > 0 && (channelCount == 1 || channelCount == 2)) {
                        if (ulunasFrameLeft.size != hopLength) ulunasFrameLeft = FloatArray(hopLength)
                        if (channelCount == 2 && ulunasFrameRight.size != hopLength) ulunasFrameRight = FloatArray(hopLength)
                        val left = ulunasFrameLeft
                        val right = if (channelCount == 2) ulunasFrameRight else null

                        for (f in 0 until frameCount) {
                            val base = f * hopLength * channelCount
                            if (channelCount == 1) {
                                for (i in 0 until hopLength) {
                                    left[i] = processedShorts[base + i] / 32768.0f
                                }
                                val processedLeft = ulunasProcessorLeft!!.process(left)
                                for (i in 0 until hopLength) {
                                    processedShorts[base + i] = (processedLeft[i] * 32767.0f).toInt().coerceIn(-32768, 32767).toShort()
                                }
                            } else {
                                for (i in 0 until hopLength) {
                                    val idx = base + i * 2
                                    left[i] = processedShorts[idx] / 32768.0f
                                    right!![i] = processedShorts[idx + 1] / 32768.0f
                                }
                                val processedLeft = ulunasProcessorLeft!!.process(left)
                                val processedRight = ulunasProcessorRight!!.process(right!!)
                                for (i in 0 until hopLength) {
                                    val idx = base + i * 2
                                    processedShorts[idx] = (processedLeft[i] * 32767.0f).toInt().coerceIn(-32768, 32767).toShort()
                                    processedShorts[idx + 1] = (processedRight[i] * 32767.0f).toInt().coerceIn(-32768, 32767).toShort()
                                }
                            }
                        }
                    }
                }
                else -> { /* None 或其他类型不处理 */ }
            }
        }

        if (enableDereverb && dereverbLevel > 0f && (channelCount == 1 || channelCount == 2)) {
            val delay = 480
            if (channelCount == 1) {
                val buf = dereverbBufferLeft ?: IntArray(delay).also { dereverbBufferLeft = it }
                for (i in processedShorts.indices) {
                    val delayed = buf[dereverbIndex]
                    val current = processedShorts[i].toInt()
                    buf[dereverbIndex] = current
                    val out = (current - (delayed * dereverbLevel).toInt()).coerceIn(-32768, 32767)
                    processedShorts[i] = out.toShort()
                    dereverbIndex++
                    if (dereverbIndex >= delay) dereverbIndex = 0
                }
            } else {
                val bufL = dereverbBufferLeft ?: IntArray(delay).also { dereverbBufferLeft = it }
                val bufR = dereverbBufferRight ?: IntArray(delay).also { dereverbBufferRight = it }
                var i = 0
                while (i + 1 < processedShorts.size) {
                    val delayedL = bufL[dereverbIndex]
                    val delayedR = bufR[dereverbIndex]
                    val currentL = processedShorts[i].toInt()
                    val currentR = processedShorts[i + 1].toInt()
                    bufL[dereverbIndex] = currentL
                    bufR[dereverbIndex] = currentR
                    val outL = (currentL - (delayedL * dereverbLevel).toInt()).coerceIn(-32768, 32767)
                    val outR = (currentR - (delayedR * dereverbLevel).toInt()).coerceIn(-32768, 32767)
                    processedShorts[i] = outL.toShort()
                    processedShorts[i + 1] = outR.toShort()
                    dereverbIndex++
                    if (dereverbIndex >= delay) dereverbIndex = 0
                    i += 2
                }
            }
        }

        if (enableAGC && agcTargetLevel > 0) {
            // 采用更成熟的 RMS (均方根) 能量探测算法，而非简单的峰值检测
            var sumSquares = 0.0
            for (s in processedShorts) {
                val sample = s.toDouble() / 32768.0
                sumSquares += sample * sample
            }
            val rms = kotlin.math.sqrt(sumSquares / processedShorts.size.toDouble())
            
            // 目标能量（将 agcTargetLevel 映射到 0.0-1.0 范围）
            val targetRms = (agcTargetLevel.toDouble() / 32768.0).coerceIn(0.01, 0.9)
            
            // 只有当声音能量超过环境噪音阈值时才进行增益调整
            if (rms > 0.001) {
                val error = targetRms / (rms + 1e-6)
                val desiredGain = error.toFloat().coerceIn(0.5f, 5.0f)
                
                if (agcEnvelope == 0f) {
                    agcEnvelope = 1.0f
                }
                
                // 采用 WebRTC/Speex 风格的平滑逻辑
                // 显著减慢增益的变化速度，使其在数秒内缓慢调整，而不是每帧剧烈变动
                val smoothing = if (desiredGain < agcEnvelope) {
                    0.005f // 非常缓慢的下降，防止声音变小
                } else {
                    0.01f  // 略快一点的上升，确保声音能恢复
                }
                agcEnvelope = agcEnvelope * (1f - smoothing) + desiredGain * smoothing
            } else {
                // 静音时，增益非常缓慢地回归 1.0
                agcEnvelope = agcEnvelope * 0.999f + 1.0f * 0.001f
            }
            
            // 最终增益应用，带有限幅保护
            val finalGain = agcEnvelope.coerceIn(0.8f, 5.0f)
            
            for (i in processedShorts.indices) {
                val v = (processedShorts[i].toInt() * finalGain).toInt().coerceIn(-32768, 32767)
                processedShorts[i] = v.toShort()
            }
        }

        if (amplification != 1.0f) {
            for (i in processedShorts.indices) {
                val sample = processedShorts[i].toInt()
                val amplified = (sample * amplification).toInt()
                processedShorts[i] = amplified.coerceIn(-32768, 32767).toShort()
            }
        }

        if (enableVAD) {
            val sensitivity = vadThreshold.coerceIn(0, 100) / 100f
            val requiredConfidence = 1f - sensitivity
            val speech = speechProbability?.let { it >= requiredConfidence } ?: run {
                var sum = 0.0
                for (s in processedShorts) {
                    val n = s.toDouble() / 32768.0
                    sum += n * n
                }
                val rms = if (processedShorts.isNotEmpty()) kotlin.math.sqrt(sum / processedShorts.size.toDouble()).toFloat() else 0f
                rms >= (requiredConfidence * 0.12f)
            }
            if (!speech) {
                for (i in processedShorts.indices) {
                    processedShorts[i] = 0
                }
            }
        }

        val line = monitoringLine
        if (line != null) {
            val bytesPerSecond = (line.format.sampleRate.toInt() * line.format.channels * 2).coerceAtLeast(1)
            val queuedBytes = (line.bufferSize - line.available()).coerceAtLeast(0)
            val queuedMs = queuedBytes * 1000L / bytesPerSecond.toLong()
            playbackRatio = updatePlaybackRatio(queuedMs, playbackRatioIntegral).also {
                playbackRatioIntegral = it.second
            }.first

            if (queuedMs >= 2000) {
                line.flush()
                resamplePosFrames = 0.0
            }
        }

        var processedShortCount = processedShorts.size
        if (kotlin.math.abs(playbackRatio - 1.0) >= 0.00005) {
            val inputForResample = processedShorts
            processedShortCount = resampleInterleavedShorts(processedShorts = inputForResample, channelCount = channelCount, ratio = playbackRatio)
            processedShorts = scratchResampledShorts
        }

        // Convert back to ByteArray
        val neededBytes = processedShortCount * 2
        if (scratchResultBuffer.size != neededBytes) {
            scratchResultBuffer = ByteArray(neededBytes)
        }
        val resultBuffer = scratchResultBuffer
        ByteBuffer.wrap(resultBuffer).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(processedShorts, 0, processedShortCount)
        return resultBuffer
    }

    private fun updatePlaybackRatio(
        queuedMs: Long,
        currentIntegral: Double
    ): Pair<Double, Double> {
        val targetMs = 60.0 // 降低目标延迟
        val errorMs = queuedMs.toDouble() - targetMs
        
        // 分段控制参数
        // 1. 如果误差非常大（> 200ms），使用更激进的比例
        // 2. 如果误差较小，使用 PI 控制
        
        if (errorMs > 100) {
            // 严重积压，快速追赶
            // 1.05x ~ 1.1x
            return 1.10 to currentIntegral
        } else if (errorMs < -100) {
            // 严重欠载（几乎不可能发生，除非网络断流）
            return 0.95 to currentIntegral
        }
        
        // 正常范围内的 PI 控制
        val kP = 0.0002 // 增大 P
        val kI = 0.000002 // 增大 I
        val maxAdjust = 0.10 // 增大调节范围到 10%
        
        var integral = (currentIntegral + errorMs).coerceIn(-10000.0, 10000.0)
        val adjust = (errorMs * kP + integral * kI).coerceIn(-maxAdjust, maxAdjust)
        val ratio = (1.0 + adjust).coerceIn(1.0 - maxAdjust, 1.0 + maxAdjust)
        return ratio to integral
    }

    private fun resampleInterleavedShorts(processedShorts: ShortArray, channelCount: Int, ratio: Double): Int {
        if (channelCount <= 0) return processedShorts.size
        val inputFrames = processedShorts.size / channelCount
        if (inputFrames <= 1) return processedShorts.size

        if (resamplePrevFrame.size != channelCount) {
            resamplePrevFrame = ShortArray(channelCount)
            for (c in 0 until channelCount) {
                resamplePrevFrame[c] = processedShorts[c]
            }
            resamplePosFrames = 1.0
        }

        val effectiveFrames = inputFrames + 1
        var outFrames = 0
        var pos = resamplePosFrames

        val estimatedOutFrames = ((inputFrames.toDouble() / ratio) + 4.0).toInt().coerceAtLeast(8)
        val neededShorts = estimatedOutFrames * channelCount
        if (scratchResampledShorts.size < neededShorts) {
            scratchResampledShorts = ShortArray(neededShorts)
        }

        fun sample(frameIndex: Int, channel: Int): Int {
            return if (frameIndex == 0) {
                resamplePrevFrame[channel].toInt()
            } else {
                processedShorts[(frameIndex - 1) * channelCount + channel].toInt()
            }
        }

        while (true) {
            val base = pos.toInt()
            if (base + 1 >= effectiveFrames) break
            val frac = pos - base.toDouble()

            val outBase = outFrames * channelCount
            for (c in 0 until channelCount) {
                val s0 = sample(base, c)
                val s1 = sample(base + 1, c)
                val v = (s0 + (s1 - s0) * frac).toInt().coerceIn(-32768, 32767)
                scratchResampledShorts[outBase + c] = v.toShort()
            }

            outFrames++
            pos += ratio

            val required = (outFrames + 1) * channelCount
            if (required > scratchResampledShorts.size) {
                scratchResampledShorts = scratchResampledShorts.copyOf((scratchResampledShorts.size * 2).coerceAtLeast(required))
            }
        }

        val lastFrameOffset = (inputFrames - 1) * channelCount
        for (c in 0 until channelCount) {
            resamplePrevFrame[c] = processedShorts[lastFrameOffset + c]
        }

        resamplePosFrames = pos - inputFrames.toDouble()

        val outShorts = outFrames * channelCount
        return outShorts
    }


    private fun calculateRMS(buffer: ByteArray): Float {
        var sum = 0.0
        var count = 0
        var i = 0
        while (i + 1 < buffer.size) {
            val lo = buffer[i].toInt() and 0xFF
            val hi = buffer[i + 1].toInt()
            val sample = (hi shl 8) or lo
            val normalized = sample / 32768.0
            sum += normalized * normalized
            count++
            i += 2
        }
        return if (count > 0) kotlin.math.sqrt(sum / count.toDouble()).toFloat() else 0f
    }

    /**
     * 获取 Ulunas 模型文件路径
     * 优先从系统属性获取，否则从资源文件提取到临时目录
     */
    private fun getUlnasModelPath(): String {
        // 检查缓存
        ulunasModelPath?.let { return it }

        // 优先从系统属性获取
        System.getProperty("micyou.ulunas.model.path")?.let {
            ulunasModelPath = it
            return it
        }

        // 从资源文件提取到临时目录
        val tempDir = Files.createTempDirectory("micyou")
        val modelFile = tempDir.resolve("ulunas.onnx").toFile()

        // 如果临时文件已存在且不为空，直接返回
        if (modelFile.exists() && modelFile.length() > 0) {
            ulunasModelPath = modelFile.absolutePath
            return modelFile.absolutePath
        }

        // 从资源提取
        val classLoader = this.javaClass.classLoader
        val resourceStream = classLoader.getResourceAsStream("models/ulunas.onnx")
            ?: throw IOException("无法找到 Ulunas 模型文件: models/ulunas.onnx")

        resourceStream.use { input ->
            modelFile.outputStream().use { output ->
                input.copyTo(output)
            }
        }

        // 设置删除钩子
        modelFile.deleteOnExit()

        ulunasModelPath = modelFile.absolutePath
        return modelFile.absolutePath
    }
}