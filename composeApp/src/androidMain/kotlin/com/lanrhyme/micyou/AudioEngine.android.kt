package com.lanrhyme.micyou

import android.content.Intent
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.Socket
import io.ktor.network.sockets.aSocket
import io.ktor.network.sockets.openReadChannel
import io.ktor.network.sockets.openWriteChannel
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.jvm.javaio.toByteReadChannel
import io.ktor.utils.io.readAvailable
import io.ktor.utils.io.readFully
import io.ktor.utils.io.readInt
import io.ktor.utils.io.reader
import io.ktor.utils.io.writeFully
import io.ktor.utils.io.writeInt
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.protobuf.ProtoBuf
import java.io.EOFException
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.coroutines.coroutineContext

/**
 * 将 OutputStream 转换为 ByteWriteChannel，使用当前协程的上下文。
 *
 * 此扩展函数应该仅在协程内部调用，以确保正确管理协程生命周期。
 * 使用 coroutineContext 而非 GlobalScope，避免协程泄漏。
 *
 * **重要**：此函数不会自动关闭 OutputStream，因为 OutputStream 的生命周期
 * 应该由调用者管理（例如通过 closeConnection 回调）。在 finally 中关闭会导致
 * 蓝牙/WiFi 连接提前断开，影响音频传输。
 *
 * @return ByteWriteChannel 用于写入数据
 */
suspend fun OutputStream.toByteWriteChannelSuspend(): ByteWriteChannel {
    val scope = CoroutineScope(coroutineContext)
    val outputStream = this
    return scope.reader(Dispatchers.IO, autoFlush = true) {
        val buffer = ByteArray(4096)
        try {
            while (!channel.isClosedForRead) {
                val count = channel.readAvailable(buffer)
                if (count == -1) break
                try {
                    outputStream.write(buffer, 0, count)
                    outputStream.flush()
                } catch (e: java.io.IOException) {
                    Logger.e("ByteWriteChannel", "I/O error writing to stream: ${e.message}", e)
                    break
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            // 协程取消是正常行为，重新抛出以保持协程取消语义
            throw e
        } catch (e: Exception) {
            Logger.e("ByteWriteChannel", "Unexpected error in write channel: ${e.message}", e)
        }
        // 注意：不在此处关闭 OutputStream，由调用者通过 closeConnection 回调管理
        // 这与原始 toByteWriteChannel 的行为保持一致，避免蓝牙连接提前断开
    }.channel
}

actual class AudioEngine actual constructor() {
    init {
        // 注意：此单例模式用于通知栏快捷操作，不支持多实例场景
        // 在测试环境中需要注意清理或使用 ViewModel 管理实例
        activeEngine = this
    }

    companion object {
        @Volatile
        private var activeEngine: AudioEngine? = null

        fun requestDisconnectFromNotification() {
            activeEngine?.stop()
        }

        fun isStreaming(): Boolean {
            val state = activeEngine?.currentStreamState()
            return state == StreamState.Streaming || state == StreamState.Connecting
        }
    }

    // 用于测试或清理场景，显式移除 active 引用
    // 定义为实例方法，可直接使用 this 而无需传参
    private fun clearActiveEngine() {
        if (activeEngine == this) {
            activeEngine = null
        }
    }
    private val _state = MutableStateFlow(StreamState.Idle)
    actual val streamState: Flow<StreamState> = _state

    fun currentStreamState(): StreamState = _state.value
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

    private var job: Job? = null
    private val startStopMutex = Mutex()
    private val proto = ProtoBuf { }
    
    // CompletableDeferred for connection status
    private var connectionComplete: CompletableDeferred<Unit>? = null
    
    // Channel for outgoing messages (Audio + Control)
    private var sendChannel: Channel<MessageWrapper>? = null
    
    private var udpSocket: DatagramSocket? = null
    private var udpServerAddress: InetSocketAddress? = null

    @Volatile
    private var enableStreamingNotification: Boolean = true

    @Volatile
    private var enableNS: Boolean = false
    @Volatile
    private var enableAGC: Boolean = false
    @Volatile
    private var audioSource: AndroidAudioSource = AndroidAudioSource.Mic

    private var noiseSuppressor: NoiseSuppressor? = null
    private var automaticGainControl: AutomaticGainControl? = null

    // Saved connection params for restart
    private var savedIp: String = ""
    private var savedPort: Int = 0
    private var savedMode: ConnectionMode = ConnectionMode.Wifi
    private var savedSampleRate: SampleRate = SampleRate.Rate44100
    private var savedChannelCount: ChannelCount = ChannelCount.Mono
    private var savedAudioFormat: com.lanrhyme.micyou.AudioFormat = com.lanrhyme.micyou.AudioFormat.PCM_16BIT
    private var isRunning: Boolean = false

    private val CHECK_1 = "MicYouCheck1"
    private val CHECK_2 = "MicYouCheck2"

    actual suspend fun start(
        ip: String, 
        port: Int, 
        mode: ConnectionMode, 
        isClient: Boolean,
        sampleRate: SampleRate,
        channelCount: ChannelCount,
        audioFormat: com.lanrhyme.micyou.AudioFormat
    ) {
        if (!isClient) return
        Logger.i("AudioEngine", "Starting Android AudioEngine: mode=$mode, ip=$ip, port=$port, sampleRate=${sampleRate.value}, channels=${channelCount.label}, format=${audioFormat.label}")
        _lastError.value = null

        // Save connection params for potential restart
        savedIp = ip
        savedPort = port
        savedMode = mode
        savedSampleRate = sampleRate
        savedChannelCount = channelCount
        savedAudioFormat = audioFormat
        isRunning = true

        // Create CompletableDeferred to track connection status
        connectionComplete = CompletableDeferred()
        
        val jobToJoin = startStopMutex.withLock {
            val currentJob = job
            if (currentJob != null && !currentJob.isCompleted) {
                Logger.w("AudioEngine", "AudioEngine already running, ignoring start request")
                connectionComplete?.complete(Unit)  // Mark as complete since already running
                null
            } else {
                _state.value = StreamState.Connecting
                CoroutineScope(Dispatchers.IO).launch {
                    var socket: Socket? = null
                    var recorder: AudioRecord? = null
                    val channel = Channel<MessageWrapper>(capacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)
                    sendChannel = channel
                    
                    // 连接抽象
                    var input: ByteReadChannel
                    var output: ByteWriteChannel
                    var closeConnection: () -> Unit = {}
                    
                    try {
                        // 音频设置
                        val androidSampleRate = sampleRate.value
                        val androidChannelConfig = if (channelCount == ChannelCount.Stereo) 
                            AudioFormat.CHANNEL_IN_STEREO 
                        else 
                            AudioFormat.CHANNEL_IN_MONO
                            
                        val androidAudioFormat = when(audioFormat) {
                            com.lanrhyme.micyou.AudioFormat.PCM_8BIT -> AudioFormat.ENCODING_PCM_8BIT
                            com.lanrhyme.micyou.AudioFormat.PCM_16BIT -> AudioFormat.ENCODING_PCM_16BIT
                            com.lanrhyme.micyou.AudioFormat.PCM_FLOAT -> AudioFormat.ENCODING_PCM_FLOAT
                            else -> AudioFormat.ENCODING_PCM_16BIT // Default fallback
                        }
                        
                        val minBufSize = AudioRecord.getMinBufferSize(androidSampleRate, androidChannelConfig, androidAudioFormat)

                        // 检查 minBufSize 是否有效，某些设备可能不支持 PCM_FLOAT
                        if (minBufSize <= 0 || minBufSize == AudioRecord.ERROR || minBufSize == AudioRecord.ERROR_BAD_VALUE) {
                            val msg = "音频格式不支持: 设备不支持 ${audioFormat.label} (${androidAudioFormat}) 格式或当前采样率 ${androidSampleRate}Hz"
                            Logger.e("AudioEngine", msg + ", minBufSize=$minBufSize")
                            _state.value = StreamState.Error
                            _lastError.value = msg
                            return@launch
                        }

                        try {
                            // 使用用户选择的音频源
                            val sourceId = audioSource.sourceId

                            Logger.d("AudioEngine", "Initializing AudioRecord with source ${audioSource.name} (id=$sourceId)")
                            recorder = try {
                                // 使用 minBufSize * 3 作为缓冲区，提供足够的缓冲避免音频丢包和杂音
                                // 较大的缓冲区可以应对网络延迟和处理波动，减少音频断续
                                AudioRecord(
                                    sourceId,
                                    androidSampleRate,
                                    androidChannelConfig,
                                    androidAudioFormat,
                                    minBufSize * 3
                                )
                            } catch (e: Exception) {
                                Logger.w("AudioEngine", "${audioSource.name} failed, falling back to MIC: ${e.message}")
                                AudioRecord(
                                    MediaRecorder.AudioSource.MIC,
                                    androidSampleRate,
                                    androidChannelConfig,
                                    androidAudioFormat,
                                    minBufSize * 3
                                )
                            }
                        } catch (e: SecurityException) {
                            Logger.e("AudioEngine", "Record permission denied", e)
                            _state.value = StreamState.Error
                            _lastError.value = "录音权限不足"
                            return@launch
                        }
                        
                        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                            val msg = "AudioRecord 初始化失败"
                            Logger.e("AudioEngine", msg)
                            _state.value = StreamState.Error
                            _lastError.value = msg
                            return@launch
                        }

                        // 初始化音频效果
                        try {
                            if (NoiseSuppressor.isAvailable()) {
                                noiseSuppressor = NoiseSuppressor.create(recorder.audioSessionId)
                                noiseSuppressor?.enabled = enableNS
                                Logger.d("AudioEngine", "NoiseSuppressor initialized, enabled=$enableNS")
                            } else {
                                Logger.d("AudioEngine", "NoiseSuppressor not available")
                            }
                            
                            if (AutomaticGainControl.isAvailable()) {
                                automaticGainControl = AutomaticGainControl.create(recorder.audioSessionId)
                                automaticGainControl?.enabled = enableAGC
                                Logger.d("AudioEngine", "AutomaticGainControl initialized, enabled=$enableAGC")
                            } else {
                                Logger.d("AudioEngine", "AutomaticGainControl not available")
                            }
                        } catch (e: Exception) {
                             Logger.w("AudioEngine", "Failed to initialize audio effects: ${e.message}")
                        }
                        
                        // 网络设置
                        val selectorManager = SelectorManager(Dispatchers.IO)
                        var tcpSocket: Socket? = null
                        
                        if (mode == ConnectionMode.Bluetooth) {
                            Logger.i("AudioEngine", "Connecting via Bluetooth to $ip")

                            // 显式检查蓝牙权限，提供友好的错误提示
                            val context = ContextHelper.getContext()
                                ?: throw IllegalStateException("应用上下文不可用，无法检查蓝牙权限")
                            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                                val hasBluetoothConnect = androidx.core.content.ContextCompat.checkSelfPermission(
                                    context, android.Manifest.permission.BLUETOOTH_CONNECT
                                ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                                if (!hasBluetoothConnect) {
                                    throw SecurityException("缺少蓝牙连接权限 (BLUETOOTH_CONNECT)，请在应用设置中授予蓝牙权限")
                                }
                            }

                            val adapter = android.bluetooth.BluetoothAdapter.getDefaultAdapter()
                                ?: throw UnsupportedOperationException("设备不支持蓝牙")

                            if (!android.bluetooth.BluetoothAdapter.checkBluetoothAddress(ip)) {
                                throw IllegalArgumentException("无效的蓝牙 MAC 地址: $ip")
                            }

                            val device = adapter.getRemoteDevice(ip)
                            // SPP UUID
                            val uuid = java.util.UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

                            val btSocket = device.createRfcommSocketToServiceRecord(uuid)
                            btSocket.connect()
                            Logger.i("AudioEngine", "Bluetooth connected to $ip")

                            input = btSocket.inputStream.toByteReadChannel()
                            output = btSocket.outputStream.toByteWriteChannelSuspend()
                            closeConnection = { btSocket.close() }
                            
                        } else {
                            val targetIp = if (mode == ConnectionMode.Usb) "127.0.0.1" else ip
                            Logger.i("AudioEngine", "Connecting via TCP to $targetIp:$port")
                            val socketBuilder = aSocket(selectorManager)
                            tcpSocket = socketBuilder.tcp().connect(targetIp, port) {
                                // 优化 Socket 参数以应对 Wi-Fi 环境下的连接不稳
                                keepAlive = true
                                // 允许更长的等待时间
                                socketTimeout = 10000L
                                // 禁用 Nagle 算法，降低音频包延迟
                                noDelay = true
                            }
                            Logger.i("AudioEngine", "TCP connected to $targetIp:$port")
                            input = tcpSocket.openReadChannel()
                            output = tcpSocket.openWriteChannel(autoFlush = true)
                            
                            // Wifi 模式：同时建立 UDP 音频通道
                            if (mode == ConnectionMode.Wifi) {
                                val udpPort = port + UDP_PORT_OFFSET
                                Logger.i("AudioEngine", "Connecting via UDP to $targetIp:$udpPort")
                                udpSocket = DatagramSocket()
                                udpServerAddress = InetSocketAddress(targetIp, udpPort)
                                Logger.i("AudioEngine", "UDP connected to $targetIp:$udpPort")
                            }
                            
                            closeConnection = { 
                                tcpSocket?.close()
                                udpSocket?.close()
                                udpSocket = null
                                udpServerAddress = null
                            }
                        }

                        // 握手
                        Logger.d("AudioEngine", "Starting handshake")
                        output.writeFully(CHECK_1.encodeToByteArray())
                        output.flush()

                        val responseBuffer = ByteArray(CHECK_2.length)
                        input.readFully(responseBuffer, 0, responseBuffer.size)

                        if (!responseBuffer.decodeToString().equals(CHECK_2)) {
                            val msg = "握手失败"
                            Logger.e("AudioEngine", msg)
                            _state.value = StreamState.Error
                            _lastError.value = msg
                            closeConnection()
                            return@launch
                        }
                        Logger.i("AudioEngine", "Handshake successful")

                        recorder.startRecording()
                        _state.value = StreamState.Streaming
                        _lastError.value = null
                        
                        // Notify connection success
                        connectionComplete?.complete(Unit)

                        if (enableStreamingNotification) {
                            val context = ContextHelper.getContext()
                            if (context != null) {
                                val intent = Intent(context, AudioService::class.java).apply {
                                    action = AudioService.ACTION_START
                                }
                                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                                    context.startForegroundService(intent)
                                } else {
                                    context.startService(intent)
                                }
                            }
                        }

                        // --- Writer Loop (Send Channel -> TCP Socket for control messages) ---
                        val writerJob = launch {
                            Logger.d("AudioEngine", "Writer loop started")
                            for (msg in channel) {
                                try {
                                    // 仅通过 TCP 发送控制消息（静音/连接/插件同步）
                                    if (msg.hasControlMessage()) {
                                        val packetBytes = proto.encodeToByteArray(MessageWrapper.serializer(), msg)
                                        val length = packetBytes.size
                                        output.writeInt(PACKET_MAGIC)
                                        output.writeInt(length)
                                        output.writeFully(packetBytes)
                                        output.flush()
                                    }
                                    // 音频包通过 UDP 发送（在音频采集循环中处理）
                                } catch (e: Exception) {
                                    Logger.e("AudioEngine", "Error writing to socket", e)
                                    break
                                }
                            }
                            Logger.d("AudioEngine", "Writer loop stopped")
                        }

                        // --- Reader Loop (Socket -> Receive Channel/Action) ---
                        val readerJob = launch {
                            Logger.d("AudioEngine", "Reader loop started")
                            try {
                                while (isActive) {
                                    val magic = try {
                                        input.readInt()
                                    } catch (e: Exception) {
                                        if (isActive && _state.value == StreamState.Streaming && !isNormalDisconnect(e)) {
                                            Logger.d("AudioEngine", "Reader loop: socket closed or EOF: ${e.message}")
                                        }
                                        break // Exit loop on EOF/IOException
                                    }
                                    
                                    if (magic != PACKET_MAGIC) {
                                        Logger.w("AudioEngine", "Invalid Magic: ${magic.toString(16)}")
                                        throw java.io.IOException("Invalid Packet Magic")
                                    }
                                    
                                    val length = input.readInt()

                                    if (length > 0) {
                                        val packetBytes = ByteArray(length)
                                        input.readFully(packetBytes)
                                        try {
                                            val wrapper = proto.decodeFromByteArray(MessageWrapper.serializer(), packetBytes)
                                            if (wrapper.mute != null) {
                                                _isMuted.value = wrapper.mute.isMuted
                                                Logger.i("AudioEngine", "Received Mute Command: ${wrapper.mute.isMuted}")
                                            }
                                        } catch (e: Exception) {
                                            Logger.e("AudioEngine", "Error decoding incoming message", e)
                                        }
                                    }
                                }
                            } catch (e: Exception) {
                                if (isActive && _state.value == StreamState.Streaming && !isNormalDisconnect(e)) {
                                    Logger.e("AudioEngine", "Error reading from socket", e)
                                }
                            }
                            Logger.d("AudioEngine", "Reader loop stopped")
                        }
                        
                        // 发送初始静音状态
                        sendChannel?.send(MessageWrapper(mute = MuteMessage(_isMuted.value)))

                        val buffer = ByteArray(minBufSize)
                        val floatBuffer = if (androidAudioFormat == AudioFormat.ENCODING_PCM_FLOAT) FloatArray(minBufSize / 4) else null
                        
                        var sequenceNumber = 0

                        while (isActive) {
                            // 检查写入器/读取器是否仍然存活
                            if (writerJob.isCancelled || writerJob.isCompleted) throw Exception("Writer job failed")
                            // 读取器失败对发送来说不那么重要，但通常表示连接丢失
                            
                            var readBytes = 0
                            val audioData: ByteArray

                            if (androidAudioFormat == AudioFormat.ENCODING_PCM_FLOAT && floatBuffer != null) {
                                val readFloats = recorder.read(floatBuffer, 0, floatBuffer.size, AudioRecord.READ_BLOCKING)
                                if (readFloats > 0) {
                                    readBytes = readFloats * 4
                                    audioData = ByteArray(readBytes)
                                    ByteBuffer.wrap(audioData).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().put(floatBuffer, 0, readFloats)
                                } else {
                                    audioData = ByteArray(0)
                                }
                            } else {
                                readBytes = recorder.read(buffer, 0, buffer.size)
                                audioData = if (readBytes > 0) buffer.copyOfRange(0, readBytes) else ByteArray(0)
                            }

                            if (readBytes > 0) {
                                // 计算电平数据
                                val levelData = calculateAudioLevelData(audioData, audioFormat)
                                _audioLevels.value = levelData.rms
                                _audioLevelData.value = levelData

                                if (!_isMuted.value) {
                                    // 创建数据包
                                    val packet = AudioPacketMessage(
                                        buffer = audioData,
                                        sampleRate = androidSampleRate,
                                        channelCount = if (channelCount == ChannelCount.Stereo) 2 else 1,
                                        audioFormat = audioFormat.value
                                    )
                                    
                                    val wrapper = MessageWrapper(
                                        audioPacket = AudioPacketMessageOrdered(sequenceNumber++, packet)
                                    )
                                    
                                    // Wifi 模式：音频包通过 UDP 发送
                                    if (udpSocket != null && udpServerAddress != null) {
                                        sendAudioPacketViaUdp(wrapper)
                                    } else {
                                        // 非 Wifi 模式（USB/蓝牙）：通过 TCP 发送
                                        sendChannel?.send(wrapper)
                                    }
                                }
                            }
                        }
} catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        if (isActive && !isNormalDisconnect(e)) {
                            Logger.e("AudioEngine", "Connection lost", e)
                            _state.value = StreamState.Error
                            
                            val errorMsg = when {
                                e is java.net.ConnectException && e.message?.contains("Connection refused", ignoreCase = true) == true -> 
                                    "连接被拒绝: 请确保电脑端已开启并处于连接中状态，且防火墙已放行 TCP $port 端口。"
                                e is java.net.SocketTimeoutException -> 
                                    "连接超时: 请检查网络连接或 IP 地址是否正确。"
                                e is java.net.NoRouteToHostException ->
                                    "无法到达主机: 请确保手机和电脑在同一个 Wi-Fi 网络下。"
                                else -> "连接断开: ${e.message}"
                            }
                            _lastError.value = errorMsg
                            
                            // Notify connection failure
                            connectionComplete?.completeExceptionally(Exception(errorMsg, e))
                        }
                    } finally {
                        Logger.d("AudioEngine", "Cleaning up resources")
                        try {
                            noiseSuppressor?.release()
                            automaticGainControl?.release()
                            noiseSuppressor = null
                            automaticGainControl = null
                            
                            sendChannel?.close()
                            recorder?.stop()
                            recorder?.release()
                            closeConnection()
                            
                            // 停止前台服务
                            val context = ContextHelper.getContext()
                            if (context != null) {
                                val intent = Intent(context, AudioService::class.java).apply {
                                    action = AudioService.ACTION_STOP
                                }
                                context.startService(intent)
                            }
                        } catch (e: Exception) {
                            Logger.w("AudioEngine", "Error during cleanup: ${e.message}")
                        }
                        _state.value = StreamState.Idle
                        Logger.i("AudioEngine", "AudioEngine stopped")
                    }
                }.also { job = it }
            }
        }
        
        // Wait for connection to complete or fail
        try {
            connectionComplete?.await()
        } catch (e: Exception) {
            // Cancel job on failure
            job?.cancel()
            throw e
        }
    }
    
    /**
     * 通过 UDP 发送音频数据包
     */
    @OptIn(ExperimentalSerializationApi::class)
    private fun sendAudioPacketViaUdp(wrapper: MessageWrapper) {
        try {
            val packetBytes = proto.encodeToByteArray(MessageWrapper.serializer(), wrapper)
            val length = packetBytes.size
            // Magic (4B) + Length (4B) + Payload
            val header = ByteArray(8).apply {
                this[0] = (UDP_PACKET_MAGIC shr 24).toByte()
                this[1] = (UDP_PACKET_MAGIC shr 16).toByte()
                this[2] = (UDP_PACKET_MAGIC shr 8).toByte()
                this[3] = UDP_PACKET_MAGIC.toByte()
                this[4] = (length shr 24).toByte()
                this[5] = (length shr 16).toByte()
                this[6] = (length shr 8).toByte()
                this[7] = length.toByte()
            }
            val udpPacket = DatagramPacket(header + packetBytes, 8 + length, udpServerAddress)
            udpSocket?.send(udpPacket)
        } catch (e: Exception) {
            Logger.w("AudioEngine", "UDP 发送失败: ${e.message}")
        }
    }
    
    actual fun stop() {
        job?.cancel()
        job = null
        _state.value = StreamState.Idle
        isRunning = false

        // 清理 active 引用（如果当前实例是 activeEngine）
        clearActiveEngine()

        val context = ContextHelper.getContext()
        if (context != null) {
            val intent = Intent(context, AudioService::class.java).apply {
                action = AudioService.ACTION_STOP
            }
            context.startService(intent)
        }
    }
    
    actual fun setMonitoring(enabled: Boolean) {
        // Android client typically doesn't need monitoring as it's the source
        // Implementation can be empty or added if local feedback is needed
    }

    actual val installProgress: Flow<String?> = MutableStateFlow(null)
    
    actual suspend fun installDriver() {
        // No driver installation needed on Android
    }

    actual suspend fun setMute(muted: Boolean) {
        _isMuted.value = muted
        // 如果已连接，发送消息
        if (_state.value == StreamState.Streaming || _state.value == StreamState.Connecting) {
             try {
                 sendChannel?.send(MessageWrapper(mute = MuteMessage(muted)))
             } catch (e: Exception) {
                 println("Failed to send mute message: ${e.message}")
             }
        }
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
        val nsChanged = this.enableNS != enableNS
        val agcChanged = this.enableAGC != enableAGC

        this.enableNS = enableNS
        this.enableAGC = enableAGC

        try {
            noiseSuppressor?.enabled = enableNS
            automaticGainControl?.enabled = enableAGC
        } catch (e: Exception) {
            println("Error updating audio effects: ${e.message}")
        }

        // Restart audio stream if hardware processing state changed and stream is running
        if ((nsChanged || agcChanged) && isRunning && _state.value == StreamState.Streaming) {
            Logger.i("AudioEngine", "Hardware processing changed, restarting audio stream...")
            CoroutineScope(Dispatchers.IO).launch {
                stop()
                delay(500)
                start(savedIp, savedPort, savedMode, true, savedSampleRate, savedChannelCount, savedAudioFormat)
            }
        }
    }

    actual fun setAudioSource(sourceName: String) {
        val source = try {
            AndroidAudioSource.valueOf(sourceName)
        } catch (e: Exception) {
            AndroidAudioSource.Mic
        }

        // Only restart if source actually changed and engine is running
        if (this.audioSource != source) {
            this.audioSource = source
            Logger.d("AudioEngine", "Audio source changed to: ${source.name}")

            // Restart audio stream if currently running
            if (isRunning && _state.value == StreamState.Streaming) {
                Logger.i("AudioEngine", "Restarting audio stream with new source...")
                CoroutineScope(Dispatchers.IO).launch {
                    stop()
                    delay(500) // Wait for cleanup
                    start(savedIp, savedPort, savedMode, true, savedSampleRate, savedChannelCount, savedAudioFormat)
                }
            }
        }
    }

    actual fun setStreamingNotificationEnabled(enabled: Boolean) {
        enableStreamingNotification = enabled
        val context = ContextHelper.getContext() ?: return

        if (!enabled) {
            val intent = Intent(context, AudioService::class.java).apply { action = AudioService.ACTION_STOP }
            context.startService(intent)
            return
        }

        if (_state.value == StreamState.Streaming) {
            val intent = Intent(context, AudioService::class.java).apply { action = AudioService.ACTION_START }
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }

    private fun isNormalDisconnect(e: Throwable): Boolean {
        if (e is kotlinx.coroutines.CancellationException) return true
        if (e is EOFException) return true
        if (e is io.ktor.utils.io.errors.EOFException) return true
        if (e is java.io.IOException) {
            val msg = e.message ?: ""
            if (msg.contains("Socket closed", ignoreCase = true)) return true
            if (msg.contains("Connection reset", ignoreCase = true)) return true
            if (msg.contains("Broken pipe", ignoreCase = true)) return true
        }
        return false
    }
    
    /**
     * 计算音频数据的 RMS 电平值。
     * 支持多种格式：PCM_FLOAT, PCM_8BIT, PCM_16BIT。
     * 注意：JVM 端有独立的 calculateRMS 实现，只处理 16-bit 格式，
     * 因为 AudioProcessorPipeline 已将所有格式统一转换。
     */
    /**
     * 计算音频电平数据（RMS、峰值、dB）
     */
    private fun calculateAudioLevelData(buffer: ByteArray, format: com.lanrhyme.micyou.AudioFormat): AudioLevelData {
        if (buffer.isEmpty()) return AudioLevelData.SILENT

        var sum = 0.0
        var maxSample = 0.0
        var sampleCount = 0

        when (format) {
            com.lanrhyme.micyou.AudioFormat.PCM_FLOAT -> {
                sampleCount = buffer.size / 4
                for (i in 0 until sampleCount) {
                    val byteIndex = i * 4
                    val bits = (buffer[byteIndex].toInt() and 0xFF) or
                               ((buffer[byteIndex + 1].toInt() and 0xFF) shl 8) or
                               ((buffer[byteIndex + 2].toInt() and 0xFF) shl 16) or
                               ((buffer[byteIndex + 3].toInt() and 0xFF) shl 24)
                    val sample = Float.fromBits(bits)
                    sum += sample * sample
                    maxSample = maxOf(maxSample, kotlin.math.abs(sample.toDouble()))
                }
            }
            com.lanrhyme.micyou.AudioFormat.PCM_8BIT -> {
                sampleCount = buffer.size
                for (i in 0 until sampleCount) {
                    val sample = (buffer[i].toInt() and 0xFF) - 128
                    val normalized = sample / 128.0
                    sum += normalized * normalized
                    maxSample = maxOf(maxSample, kotlin.math.abs(normalized))
                }
            }
            else -> { // 16-bit (including PCM_16BIT and PCM_24BIT which is downscaled)
                sampleCount = buffer.size / 2
                for (i in 0 until sampleCount) {
                    val byteIndex = i * 2
                    val sample = (buffer[byteIndex].toInt() and 0xFF) or
                                 ((buffer[byteIndex + 1].toInt()) shl 8)
                    val normalized = sample / 32768.0
                    sum += normalized * normalized
                    maxSample = maxOf(maxSample, kotlin.math.abs(normalized))
                }
            }
        }

        if (sampleCount == 0) return AudioLevelData.SILENT

        val rms = Math.sqrt(sum / sampleCount).toFloat().coerceIn(0f, 1f)
        val peak = maxSample.toFloat().coerceIn(0f, 1f)

        return AudioLevelData.fromRmsAndPeak(rms, peak)
    }

    /**
     * 更新性能配置（Android 端暂不支持动态调整）
     */
    actual fun updatePerformanceConfig(config: PerformanceConfig) {
        Logger.d("AudioEngine", "Android 端不支持动态性能配置调整")
    }
}
