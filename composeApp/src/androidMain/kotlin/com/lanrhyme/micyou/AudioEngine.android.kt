package com.lanrhyme.micyou

import android.content.Intent
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import com.lanrhyme.micyou.audio.AecEffect
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
import micyou.composeapp.generated.resources.Res
import micyou.composeapp.generated.resources.connectionDisconnected
import micyou.composeapp.generated.resources.connectionRejected
import micyou.composeapp.generated.resources.connectionTimeout
import micyou.composeapp.generated.resources.connectionUnreachable
import micyou.composeapp.generated.resources.errorAudioFormatNotSupported
import micyou.composeapp.generated.resources.errorAudioRecordInitFailed
import micyou.composeapp.generated.resources.errorHandshakeFailedDetailed
import micyou.composeapp.generated.resources.errorRecordingPermissionDenied
import org.jetbrains.compose.resources.getString

/**
 * Converts OutputStream to ByteWriteChannel using the current coroutine context.
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
            throw e
        } catch (e: Exception) {
            Logger.e("ByteWriteChannel", "Unexpected error in write channel: ${e.message}", e)
        }
    }.channel
}

actual class AudioEngine actual constructor() {
    init {
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
    
    private var connectionComplete: CompletableDeferred<Unit>? = null
    private var sendChannel: Channel<MessageWrapper>? = null
    
    private var udpSocket: DatagramSocket? = null
    private var udpServerAddress: InetSocketAddress? = null

    @Volatile
    private var enableStreamingNotification: Boolean = true

    @Volatile
    private var enableAEC: Boolean = false
    @Volatile
    private var audioSource: AndroidAudioSource = AndroidAudioSource.Mic

    private var echoCanceler: AcousticEchoCanceler? = null

    // 软件 AEC（当硬件 AcousticEchoCanceler 不可用时使用）
    private val softwareAec = AecEffect()

    actual var onLoopbackAudioReceived: ((LoopbackAudioMessage) -> Unit)? = null

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

        savedIp = ip
        savedPort = port
        savedMode = mode
        savedSampleRate = sampleRate
        savedChannelCount = channelCount
        savedAudioFormat = audioFormat
        isRunning = true

        connectionComplete = CompletableDeferred()
        startStopMutex.withLock {
            job?.let { oldJob ->
                if (!oldJob.isCompleted) {
                    Logger.i("AudioEngine", "Waiting for previous audio job to finish...")
                    oldJob.cancel()
                    oldJob.join()
                    Logger.d("AudioEngine", "Previous job finished")
                }
            }
            
            _state.value = StreamState.Connecting
            job = CoroutineScope(Dispatchers.IO).launch {
                var recorder: AudioRecord? = null
                val channel = Channel<MessageWrapper>(capacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)
                sendChannel = channel
                
                var input: ByteReadChannel
                var output: ByteWriteChannel
                var closeConnection: () -> Unit = {}
                
                try {
                    val androidSampleRate = sampleRate.value
                    val androidChannelConfig = if (channelCount == ChannelCount.Stereo) 
                        AudioFormat.CHANNEL_IN_STEREO 
                    else 
                        AudioFormat.CHANNEL_IN_MONO
                        
                    val androidAudioFormat = when(audioFormat) {
                        com.lanrhyme.micyou.AudioFormat.PCM_8BIT -> AudioFormat.ENCODING_PCM_8BIT
                        com.lanrhyme.micyou.AudioFormat.PCM_16BIT -> AudioFormat.ENCODING_PCM_16BIT
                        com.lanrhyme.micyou.AudioFormat.PCM_FLOAT -> AudioFormat.ENCODING_PCM_FLOAT
                        else -> AudioFormat.ENCODING_PCM_16BIT
                    }
                    val minBufSize = AudioRecord.getMinBufferSize(androidSampleRate, androidChannelConfig, androidAudioFormat)

                    if (minBufSize <= 0 || minBufSize == AudioRecord.ERROR || minBufSize == AudioRecord.ERROR_BAD_VALUE) {
                        val msg = String.format(getString(Res.string.errorAudioFormatNotSupported), audioFormat.label, androidAudioFormat.toString(), androidSampleRate)
                        Logger.e("AudioEngine", msg + ", minBufSize=$minBufSize")
                        _state.value = StreamState.Error
                        _lastError.value = msg
                        return@launch
                    }

                    try {
                        val sourceId = audioSource.sourceId
                        Logger.d("AudioEngine", "Initializing AudioRecord with source ${audioSource.name} (id=$sourceId)")
                        recorder = try {
                            AudioRecord(
                                sourceId,
                                androidSampleRate,
                                androidChannelConfig,
                                androidAudioFormat,
                                minBufSize * 3
                            )
                        } catch (e: Exception) {
                            Logger.w("AudioEngine", "Primary source ($sourceId) failed, falling back to MIC: ${e.message}")
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
                        _lastError.value = getString(Res.string.errorRecordingPermissionDenied)
                        return@launch
                    }
                    
                    if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                        val msg = getString(Res.string.errorAudioRecordInitFailed)
                        Logger.e("AudioEngine", msg)
                        _state.value = StreamState.Error
                        _lastError.value = msg
                        return@launch
                    }

                    try {
                        if (enableAEC && AcousticEchoCanceler.isAvailable()) {
                            echoCanceler = AcousticEchoCanceler.create(recorder.audioSessionId)
                            echoCanceler?.enabled = true
                            Logger.d("AudioEngine", "AcousticEchoCanceler initialized and enabled")
                        } else {
                            Logger.d("AudioEngine", "AcousticEchoCanceler not available or not enabled")
                        }
                    } catch (e: Exception) {
                         Logger.w("AudioEngine", "Failed to initialize audio effects: ${e.message}")
                    }
                    
                    val selectorManager = SelectorManager(Dispatchers.IO)
                    var tcpSocket: Socket? = null
                    
                    if (mode == ConnectionMode.Bluetooth) {
                        Logger.i("AudioEngine", "Connecting via Bluetooth to $ip")
                        val context = ContextHelper.getContext() ?: throw IllegalStateException("Context unavailable")
                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                            val hasBluetoothConnect = androidx.core.content.ContextCompat.checkSelfPermission(
                                context, android.Manifest.permission.BLUETOOTH_CONNECT
                            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                            if (!hasBluetoothConnect) {
                                throw SecurityException("Missing BLUETOOTH_CONNECT permission")
                            }
                        }
                        val adapter = android.bluetooth.BluetoothAdapter.getDefaultAdapter() ?: throw UnsupportedOperationException("Bluetooth not supported")
                        if (!android.bluetooth.BluetoothAdapter.checkBluetoothAddress(ip)) {
                            throw IllegalArgumentException("Invalid Bluetooth MAC address: $ip")
                        }
                        val device = adapter.getRemoteDevice(ip)
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
                            keepAlive = true
                            socketTimeout = 10000L
                            noDelay = true
                        }
                        Logger.i("AudioEngine", "TCP connected to $targetIp:$port")
                        input = tcpSocket.openReadChannel()
                        output = tcpSocket.openWriteChannel(autoFlush = true)
                        
                        if (mode == ConnectionMode.Wifi) {
                            val udpPort = calculateUdpPort(port)
                            Logger.i("AudioEngine", "Connecting via UDP to $targetIp:$udpPort")
                            udpSocket = DatagramSocket()
                            udpServerAddress = InetSocketAddress(targetIp, udpPort)
                            Logger.i("AudioEngine", "UDP connected to $targetIp:$udpPort")

                            // 启动 UDP 接收循环（用于接收 PC 端回传的回环音频）
                            launch(Dispatchers.IO) {
                                val udpBuffer = ByteArray(Constants.MAX_PACKET_SIZE)
                                val udpPacket = DatagramPacket(udpBuffer, udpBuffer.size)
                                Logger.d("AudioEngine", "UDP reader loop started")
                                var udpPacketCount = 0
                                try {
                                    while (isActive) {
                                        try {
                                            udpSocket?.receive(udpPacket)
                                        } catch (e: java.net.SocketException) {
                                            if (isActive) Logger.d("AudioEngine", "UDP socket closed: ${e.message}")
                                            break
                                        }

                                        val data = udpPacket.data
                                        val offset = udpPacket.offset
                                        val length = udpPacket.length
                                        if (length < 8) continue

                                        // 解析魔数
                                        val magic = ((data[offset].toInt() and 0xFF) shl 24) or
                                                    ((data[offset + 1].toInt() and 0xFF) shl 16) or
                                                    ((data[offset + 2].toInt() and 0xFF) shl 8) or
                                                    (data[offset + 3].toInt() and 0xFF)

                                        if (magic == UDP_PACKET_MAGIC_LOOPBACK) {
                                            if (!enableAEC) continue // 如果未启用 AEC，直接忽略回环信号
                                            
                                            val payloadLen = ((data[offset + 4].toInt() and 0xFF) shl 24) or
                                                            ((data[offset + 5].toInt() and 0xFF) shl 16) or
                                                            ((data[offset + 6].toInt() and 0xFF) shl 8) or
                                                            (data[offset + 7].toInt() and 0xFF)
                                            
                                            if (payloadLen > 0 && payloadLen <= length - 8) {
                                                val payload = data.copyOfRange(offset + 8, offset + 8 + payloadLen)
                                                udpPacketCount++
                                                if (udpPacketCount % 100 == 1) {
                                                    Logger.d("AudioEngine", "UDP loopback packets received: $udpPacketCount")
                                                }
                                                // 在 IO 线程池中并行处理解码，避免阻塞接收循环
                                                launch(Dispatchers.Default) {
                                                    try {
                                                        val wrapper = proto.decodeFromByteArray(MessageWrapper.serializer(), payload)
                                                        if (wrapper.loopbackAudio != null) {
                                                            onLoopbackAudioReceived?.invoke(wrapper.loopbackAudio)
                                                        }
                                                    } catch (e: Exception) {
                                                        Logger.e("AudioEngine", "Error decoding UDP loopback audio", e)
                                                    }
                                                }
                                            }
                                        }
                                    }
                                } catch (e: Exception) {
                                    if (isActive) Logger.w("AudioEngine", "UDP reader loop stopped unexpectedly: ${e.message}")
                                } finally {
                                    Logger.d("AudioEngine", "UDP reader loop finished")
                                }
                            }
                        }
                        
                        closeConnection = { 
                            tcpSocket?.close()
                            udpSocket?.close()
                            udpSocket = null
                            udpServerAddress = null
                        }
                    }

                    // Handshake
                    Logger.d("AudioEngine", "Starting handshake")
                    output.writeFully(CHECK_1.encodeToByteArray())
                    output.flush()
                    val responseBuffer = ByteArray(CHECK_2.length)
                    input.readFully(responseBuffer, 0, responseBuffer.size)

                    if (!responseBuffer.decodeToString().equals(CHECK_2)) {
                        val msg = getString(Res.string.errorHandshakeFailedDetailed)
                        Logger.e("AudioEngine", "Handshake failed: received ${responseBuffer.decodeToString()}")
                        _state.value = StreamState.Error
                        _lastError.value = msg
                        closeConnection()
                        return@launch
                    }
                    Logger.i("AudioEngine", "Handshake successful")

                    recorder.startRecording()
                    _state.value = StreamState.Streaming
                    _lastError.value = null
                    connectionComplete?.complete(Unit)

                    // 发送当前 AEC 状态给服务器，以便服务器决定是否开启回环采集
                    sendAecState(enableAEC)

                    // 设置软件 AEC 回环音频回调
                    var loopbackPacketCount = 0
                    onLoopbackAudioReceived = { loopbackMsg ->
                        loopbackPacketCount++
                        if (loopbackPacketCount % 100 == 1) {
                            Logger.d("AudioEngine", "AEC Reference signal received: ${loopbackMsg.buffer.size} bytes, total packets: $loopbackPacketCount")
                        }
                        // 将 ByteArray 转换为 ShortArray 作为 AEC 参考信号
                        val shorts = ShortArray(loopbackMsg.buffer.size / 2)
                        for (i in shorts.indices) {
                            val lo = loopbackMsg.buffer[i * 2].toInt() and 0xFF
                            val hi = loopbackMsg.buffer[i * 2 + 1].toInt()
                            shorts[i] = ((hi shl 8) or lo).toShort()
                        }
                        softwareAec.setReferenceSignal(shorts, loopbackMsg.timestamp)
                    }

                    if (enableStreamingNotification) {
                        val context = ContextHelper.getContext()
                        if (context != null) {
                            val intent = Intent(context, AudioService::class.java).apply { action = AudioService.ACTION_START }
                            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                                context.startForegroundService(intent)
                            } else {
                                context.startService(intent)
                            }
                        }
                    }

                    val writerJob = launch {
                        Logger.d("AudioEngine", "Writer loop started")
                        for (msg in channel) {
                            try {
                                // Non-WiFi mode: Send everything via TCP
                                // WiFi mode: ONLY send control messages via TCP (audio goes via UDP)
                                val isWifi = mode == ConnectionMode.Wifi
                                if (!isWifi || msg.hasControlMessage() || udpSocket == null) {
                                    val packetBytes = proto.encodeToByteArray(MessageWrapper.serializer(), msg)
                                    val length = packetBytes.size
                                    output.writeInt(PACKET_MAGIC)
                                    output.writeInt(length)
                                    output.writeFully(packetBytes)
                                    output.flush()
                                }
                            } catch (e: Exception) {
                                Logger.e("AudioEngine", "Error writing to socket", e)
                                break
                            }
                        }
                        Logger.d("AudioEngine", "Writer loop stopped")
                    }

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
                                    break
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

                                        if (wrapper.ping != null) {
                                            sendChannel?.send(MessageWrapper(pong = PongMessage(wrapper.ping.timestamp)))
                                        }

                                        if (wrapper.loopbackAudio != null) {
                                            Logger.d("AudioEngine", "Received loopback audio via TCP: ${wrapper.loopbackAudio.buffer.size} bytes")
                                            onLoopbackAudioReceived?.invoke(wrapper.loopbackAudio)
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
                    
                    sendChannel?.send(MessageWrapper(mute = MuteMessage(_isMuted.value)))
                    val buffer = ByteArray(minBufSize)
                    val floatBuffer = if (androidAudioFormat == AudioFormat.ENCODING_PCM_FLOAT) FloatArray(minBufSize / 4) else null
                    
                    var sequenceNumber = 0
                    Logger.i("AudioEngine", "Audio loop started: enableAEC=$enableAEC, hardwareAec=${echoCanceler != null}")

                    while (isActive) {
                        if (writerJob.isCancelled || writerJob.isCompleted) throw Exception("Writer job failed")
                        
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
                            // 应用软件 AEC 处理（如果启用且硬件 AEC 不可用）
                            // 仅支持 16-bit PCM
                            val useSoftwareAec = enableAEC && echoCanceler == null && audioFormat == com.lanrhyme.micyou.AudioFormat.PCM_16BIT
                            val processedAudio = if (useSoftwareAec) {
                                applySoftwareAec(audioData)
                            } else {
                                audioData
                            }

                            val levelData = calculateAudioLevelData(processedAudio, audioFormat)
                            _audioLevels.value = levelData.rms
                            _audioLevelData.value = levelData

                            if (!_isMuted.value) {
                                val packet = AudioPacketMessage(
                                    buffer = processedAudio,
                                    sampleRate = androidSampleRate,
                                    channelCount = if (channelCount == ChannelCount.Stereo) 2 else 1,
                                    audioFormat = audioFormat.value
                                )
                                val wrapper = MessageWrapper(
                                    audioPacket = AudioPacketMessageOrdered(sequenceNumber++, packet, System.currentTimeMillis())
                                )
                                
                                if (udpSocket != null && udpServerAddress != null) {
                                    sendAudioPacketViaUdp(wrapper)
                                } else {
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
                                String.format(getString(Res.string.connectionRejected), port)
                            e is java.net.SocketTimeoutException ->
                                getString(Res.string.connectionTimeout)
                            e is java.net.NoRouteToHostException ->
                                getString(Res.string.connectionUnreachable)
                            else -> getString(Res.string.connectionDisconnected)
                        }
                        _lastError.value = errorMsg
                        connectionComplete?.completeExceptionally(Exception(errorMsg, e))
                    }
                } finally {
                    Logger.d("AudioEngine", "Cleaning up resources")
                    try {
                        echoCanceler?.release()
                        softwareAec.release()
                        onLoopbackAudioReceived = null
                        echoCanceler = null
                        
                        sendChannel?.close()
                        recorder?.stop()
                        recorder?.release()
                        closeConnection()
                        
                        val context = ContextHelper.getContext()
                        if (context != null) {
                            val intent = Intent(context, AudioService::class.java).apply { action = AudioService.ACTION_STOP }
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
        
        try {
            connectionComplete?.await()
        } catch (e: Exception) {
            job?.cancel()
            throw e
        }
    }
    
    @OptIn(ExperimentalSerializationApi::class)
    private fun sendAudioPacketViaUdp(wrapper: MessageWrapper) {
        try {
            val packetBytes = proto.encodeToByteArray(MessageWrapper.serializer(), wrapper)
            val length = packetBytes.size
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
            Logger.w("AudioEngine", "UDP send failed: ${e.message}")
        }
    }
    
    actual fun stop() {
        job?.cancel()
        job = null
        _state.value = StreamState.Idle
        isRunning = false

        clearActiveEngine()
        val context = ContextHelper.getContext()
        if (context != null) {
            val intent = Intent(context, AudioService::class.java).apply { action = AudioService.ACTION_STOP }
            context.startService(intent)
        }
    }
    
    actual fun setMonitoring(enabled: Boolean) { }

    actual val installProgress: Flow<String?> = MutableStateFlow(null)
    
    actual suspend fun installDriver() { }

    actual suspend fun setMute(muted: Boolean) {
        _isMuted.value = muted
        if (_state.value == StreamState.Streaming || _state.value == StreamState.Connecting) {
             try {
                 sendChannel?.send(MessageWrapper(mute = MuteMessage(muted)))
             } catch (e: Exception) {
                 Logger.e("AudioEngine", "Failed to send mute message: ${e.message}")
             }
        }
    }

    private suspend fun sendAecState(enabled: Boolean) {
        if (_state.value == StreamState.Streaming || _state.value == StreamState.Connecting) {
            try {
                sendChannel?.send(MessageWrapper(aecState = AecStateMessage(enabled)))
                Logger.d("AudioEngine", "Sent AEC state to server: $enabled")
            } catch (e: Exception) {
                Logger.e("AudioEngine", "Failed to send AEC state message: ${e.message}")
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
        amplification: Float,
        enableAEC: Boolean
    ) {
        val aecChanged = this.enableAEC != enableAEC

        this.enableAEC = enableAEC
        softwareAec.enabled = enableAEC
        Logger.i("AudioEngine", "updateConfig: enableAEC=$enableAEC, aecChanged=$aecChanged, hardwareAec=${echoCanceler != null}")

        if (aecChanged) {
            CoroutineScope(Dispatchers.IO).launch {
                sendAecState(enableAEC)
            }
        }

        try {
            echoCanceler?.enabled = enableAEC
        } catch (e: Exception) {
            Logger.e("AudioEngine", "Error updating hardware AEC: ${e.message}")
        }

        if (aecChanged && isRunning && _state.value == StreamState.Streaming) {
            Logger.i("AudioEngine", "AEC changed to $enableAEC, restarting audio stream...")
            CoroutineScope(Dispatchers.IO).launch {
                stop()
                delay(500)
                Logger.i("AudioEngine", "Restarting stream after AEC change...")
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

        if (this.audioSource != source) {
            this.audioSource = source
            Logger.d("AudioEngine", "Audio source changed to: ${source.name}")

            if (isRunning && _state.value == StreamState.Streaming) {
                Logger.i("AudioEngine", "Restarting audio stream with new source...")
                CoroutineScope(Dispatchers.IO).launch {
                    stop()
                    delay(500)
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

    /**
     * 对 16-bit PCM 音频应用软件 AEC 处理
     */
    private fun applySoftwareAec(audioData: ByteArray): ByteArray {
        if (!enableAEC || audioData.size < 2) return audioData
        val channelCount = if (savedChannelCount == ChannelCount.Stereo) 2 else 1
        val shorts = ShortArray(audioData.size / 2)
        for (i in shorts.indices) {
            val lo = audioData[i * 2].toInt() and 0xFF
            val hi = audioData[i * 2 + 1].toInt()
            shorts[i] = ((hi shl 8) or lo).toShort()
        }
        val processed = softwareAec.process(shorts, channelCount)
        val result = ByteArray(processed.size * 2)
        for (i in processed.indices) {
            result[i * 2] = (processed[i].toInt() and 0xFF).toByte()
            result[i * 2 + 1] = (processed[i].toInt() shr 8).toByte()
        }
        return result
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
            else -> {
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

    actual fun updatePerformanceConfig(config: PerformanceConfig) {
        Logger.d("AudioEngine", "Android does not support dynamic performance config adjustment")
    }
}
