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
        private const val MAX_UDP_CONSECUTIVE_FAILURES = 500
        private const val HEARTBEAT_TIMEOUT_MS = 5000L
        private const val FEC_GROUP_SIZE = 12 // 每 12 个包生成一个 FEC 包（约 87ms @44100Hz）

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

    private val _rawSpectrum = MutableStateFlow(FloatArray(0))
    actual val rawSpectrum: Flow<FloatArray> = _rawSpectrum

    private val _processedSpectrum = MutableStateFlow(FloatArray(0))
    actual val processedSpectrum: Flow<FloatArray> = _processedSpectrum
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
    private var udpConsecutiveFailures: Int = 0
    private var lastPingReceivedTime: Long = System.currentTimeMillis()

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

    private var savedIp: String = ""
    private var savedPort: Int = 0
    private var savedMode: ConnectionMode = ConnectionMode.Wifi
    private var savedSampleRate: SampleRate = SampleRate.Rate44100
    private var savedChannelCount: ChannelCount = ChannelCount.Mono
    private var savedAudioFormat: com.lanrhyme.micyou.AudioFormat = com.lanrhyme.micyou.AudioFormat.PCM_16BIT
    private var savedTransportProtocol: TransportProtocol = TransportProtocol.Both
    private var isRunning: Boolean = false

    // FEC (前向纠错) 相关
    private var fecGroupBuffer = mutableListOf<ByteArray>()
    private var fecGroupStartSeq = 0

    private val CHECK_1 = "MicYouCheck1"
    private val CHECK_2 = "MicYouCheck2"

    actual suspend fun start(
        ip: String, 
        port: Int, 
        mode: ConnectionMode, 
        isClient: Boolean,
        sampleRate: SampleRate,
        channelCount: ChannelCount,
        audioFormat: com.lanrhyme.micyou.AudioFormat,
        transportProtocol: TransportProtocol
    ) {
        if (!isClient) return
        Logger.i("AudioEngine", "Starting Android AudioEngine: mode=$mode, protocol=$transportProtocol, ip=$ip, port=$port, sampleRate=${sampleRate.value}, channels=${channelCount.label}, format=${audioFormat.label}")
        _lastError.value = null

        savedIp = ip
        savedPort = port
        savedMode = mode
        savedSampleRate = sampleRate
        savedChannelCount = channelCount
        savedAudioFormat = audioFormat
        savedTransportProtocol = transportProtocol
        isRunning = true

        connectionComplete = CompletableDeferred()
    val jobToJoin = startStopMutex.withLock {
            val currentJob = job
            if (currentJob != null && !currentJob.isCompleted) {
                Logger.w("AudioEngine", "AudioEngine already running, ignoring start request")
                connectionComplete?.complete(Unit)
                null
            } else {
                _state.value = StreamState.Connecting
                CoroutineScope(Dispatchers.IO).launch {
                    var socket: Socket? = null
                    var recorder: AudioRecord? = null
                    val channel = Channel<MessageWrapper>(capacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)
                    sendChannel = channel
                    
                    var input: ByteReadChannel? = null
                    var output: ByteWriteChannel? = null
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
                        
                        val selectorManager = SelectorManager(Dispatchers.IO)
    var tcpSocket: Socket? = null

                        val targetIp = if (mode == ConnectionMode.Usb) "127.0.0.1" else ip
                        Logger.i("AudioEngine", "Connecting with protocol $transportProtocol to $targetIp:$port")

                        // TCP-only 模式或 Both 模式：需要 TCP 连接进行握手
                        if (transportProtocol == TransportProtocol.Tcp || transportProtocol == TransportProtocol.Both) {
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
                        }

                        // UDP 音频传输：WiFi 模式下且协议为 TCP+UDP
                        if (mode == ConnectionMode.Wifi && transportProtocol == TransportProtocol.Both) {
                            val udpPort = calculateUdpPort(port)
                            Logger.i("AudioEngine", "Connecting via UDP to $targetIp:$udpPort")
                            udpSocket = DatagramSocket().also {
                                it.sendBufferSize = 256 * 1024 // 256KB send buffer
                                Logger.d("AudioEngine", "UDP send buffer: ${it.sendBufferSize / 1024}KB")
                            }
                            udpServerAddress = InetSocketAddress(targetIp, udpPort)
                            Logger.i("AudioEngine", "UDP connected to $targetIp:$udpPort")
                        }

                        closeConnection = {
                            tcpSocket?.close()
                            udpSocket?.close()
                            udpSocket = null
                            udpServerAddress = null
                        }

                        // Handshake (always via TCP if available, otherwise skip)
                        if (tcpSocket != null) {
                            val out = output ?: return@launch
                            val inChannel = input ?: return@launch
                            Logger.d("AudioEngine", "Starting handshake")
                            out.writeFully(CHECK_1.encodeToByteArray())
                            out.flush()
                            val responseBuffer = ByteArray(CHECK_2.length)
                            inChannel.readFully(responseBuffer, 0, responseBuffer.size)

                            if (!responseBuffer.decodeToString().equals(CHECK_2)) {
                                val msg = getString(Res.string.errorHandshakeFailedDetailed)
                                Logger.e("AudioEngine", "Handshake failed: received ${responseBuffer.decodeToString()}")
                                _state.value = StreamState.Error
                                _lastError.value = msg
                                closeConnection()
                                return@launch
                            }
                            Logger.i("AudioEngine", "Handshake successful")
                        } else {
                            // UDP-only 模式不需要握手（但这可能会有连接问题）
                            Logger.w("AudioEngine", "UDP-only mode: skipping handshake")
                        }

                        recorder.startRecording()
                        _state.value = StreamState.Streaming
                        _lastError.value = null
                        connectionComplete?.complete(Unit)

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
                                    // 根据传输协议决定发送方式
                                    val shouldUseUdp = when (transportProtocol) {
                                        TransportProtocol.Tcp -> false // 仅使用 TCP
                                        TransportProtocol.Both -> mode == ConnectionMode.Wifi && !msg.hasControlMessage() // Both 模式下，WiFi 模式下音频走 UDP
                                    }

                                    if (shouldUseUdp && udpSocket != null && udpServerAddress != null) {
                                        // 通过 UDP 发送
                                        sendAudioPacketViaUdp(msg)
                                    } else {
                                        val out = output
                                        if (out != null && !out.isClosedForWrite) {
                                            // 通过 TCP 发送
                                            val packetBytes = proto.encodeToByteArray(MessageWrapper.serializer(), msg)
                                            val length = packetBytes.size
                                            out.writeInt(PACKET_MAGIC)
                                            out.writeInt(length)
                                            out.writeFully(packetBytes)
                                            out.flush()
                                        }
                                    }
                                } catch (e: Exception) {
                                    Logger.e("AudioEngine", "Error writing to socket", e)
                                    break
                                }
                            }
                            Logger.d("AudioEngine", "Writer loop stopped")
                        }

                        val readerJob = if (tcpSocket != null) {
                            launch {
                                val inChannel = input ?: return@launch
                                Logger.d("AudioEngine", "Reader loop started")
                                try {
                                    while (isActive) {
                                        val magic = try {
                                            inChannel.readInt()
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
                                        val length = inChannel.readInt()

                                        if (length > 0) {
                                            val packetBytes = ByteArray(length)
                                            inChannel.readFully(packetBytes)
                                            try {
                                                val wrapper = proto.decodeFromByteArray(MessageWrapper.serializer(), packetBytes)
                                                if (wrapper.mute != null) {
                                                    _isMuted.value = wrapper.mute.isMuted
                                                    Logger.i("AudioEngine", "Received Mute Command: ${wrapper.mute.isMuted}")
                                                }

                                                if (wrapper.ping != null) {
                                                    lastPingReceivedTime = System.currentTimeMillis()
                                                    sendChannel?.send(MessageWrapper(pong = PongMessage(wrapper.ping.timestamp)))
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
                        } else {
                            // UDP-only 模式：不需要 readerJob
                            Logger.d("AudioEngine", "UDP-only mode: skipping reader loop")
                            null
                        }

                        sendChannel?.send(MessageWrapper(mute = MuteMessage(_isMuted.value)))
                        // Use read buffer sized to avoid IP fragmentation on WiFi
                        // Path MTU = 1500, minus IP(20)+UDP(8)+header(8)+ProtoBuf(~30) ≈ 1434 safe payload
    val udpSafePayloadSize = 1400
                        val bytesPerSample = when (androidAudioFormat) {
                            AudioFormat.ENCODING_PCM_8BIT -> 1
                            AudioFormat.ENCODING_PCM_16BIT -> 2
                            AudioFormat.ENCODING_PCM_FLOAT -> 4
                            else -> 2
                        }
                        val frameAlignBytes = 480 * bytesPerSample * channelCount.value
                        val alignedPayloadSize = (udpSafePayloadSize / frameAlignBytes) * frameAlignBytes
                        val readBufSize = minOf(minBufSize, alignedPayloadSize).coerceAtLeast(frameAlignBytes)
                        val buffer = ByteArray(readBufSize)
                        val floatBuffer = if (androidAudioFormat == AudioFormat.ENCODING_PCM_FLOAT) FloatArray(readBufSize / 4) else null
                        var sequenceNumber = 0
                        var lastAudioData: ByteArray? = null
                        fecGroupBuffer = mutableListOf()
                        fecGroupStartSeq = 0
                        var lastSequenceNumber = -1
                        lastPingReceivedTime = System.currentTimeMillis()

                        while (isActive) {
                            if (writerJob.isCancelled || writerJob.isCompleted) throw Exception("Writer job failed")
                            if (readerJob != null && (readerJob.isCancelled || readerJob.isCompleted)) throw Exception("Reader job failed - connection lost")
                            if (readerJob != null && System.currentTimeMillis() - lastPingReceivedTime > HEARTBEAT_TIMEOUT_MS) {
                                throw Exception("Heartbeat timeout - server unreachable ($HEARTBEAT_TIMEOUT_MS ms)")
                            }

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
                                val levelData = calculateAudioLevelData(audioData, audioFormat)
                                _audioLevels.value = levelData.rms
                                _audioLevelData.value = levelData

                                if (!_isMuted.value) {
                                    val packet = AudioPacketMessage(
                                        buffer = audioData,
                                        sampleRate = androidSampleRate,
                                        channelCount = if (channelCount == ChannelCount.Stereo) 2 else 1,
                                        audioFormat = audioFormat.value
                                    )
    val wrapper = MessageWrapper(
                                        audioPacket = AudioPacketMessageOrdered(sequenceNumber++, packet, System.currentTimeMillis())
                                    )

                                    if (udpSocket != null && udpServerAddress != null) {
                                        sendAudioPacketViaUdp(wrapper)

                                        // FEC: 收集音频 buffer，满一组后生成 FEC 包
                                        fecGroupBuffer.add(audioData)
                                        if (fecGroupBuffer.size >= FEC_GROUP_SIZE) {
                                            val xorResult = xorBuffers(fecGroupBuffer)
                                            val fecPacket = AudioPacketMessage(
                                                buffer = xorResult,
                                                sampleRate = androidSampleRate,
                                                channelCount = if (channelCount == ChannelCount.Stereo) 2 else 1,
                                                audioFormat = audioFormat.value
                                            )
                                            val fecWrapper = MessageWrapper(
                                                audioPacket = AudioPacketMessageOrdered(
                                                    sequenceNumber++,
                                                    fecPacket,
                                                    System.currentTimeMillis(),
                                                    fecSequenceNumber = fecGroupStartSeq
                                                )
                                            )
                                            sendAudioPacketViaUdp(fecWrapper)
                                            fecGroupBuffer = mutableListOf()
                                            fecGroupStartSeq = sequenceNumber
                                        }
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
                                e is UdpCircuitBreakerException -> e.message ?: getString(Res.string.connectionDisconnected)
                                e is java.net.ConnectException && e.message?.contains("Connection refused", ignoreCase = true) == true ->
                                    String.format(getString(Res.string.connectionRejected), port)
                                e is java.net.SocketTimeoutException ->
                                    getString(Res.string.connectionTimeout)
                                e is java.net.NoRouteToHostException ->
                                    getString(Res.string.connectionUnreachable)
                                e.message?.contains("Heartbeat timeout", ignoreCase = true) == true ->
                                    e.message ?: getString(Res.string.connectionUnreachable)
                                e.message?.contains("Reader job failed", ignoreCase = true) == true ->
                                    getString(Res.string.connectionDisconnected)
                                else -> getString(Res.string.connectionDisconnected)
                            }
                            _lastError.value = errorMsg
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
        }
        
        try {
            connectionComplete?.await()
        } catch (e: Exception) {
            job?.cancel()
            throw e
        }
    }
    
    /**
     * XOR 多个 buffer（处理不同长度：以最长的为准，短的用 0 填充）
     */
    private fun xorBuffers(buffers: List<ByteArray>): ByteArray {
        val maxLen = buffers.maxOf { it.size }
        val result = ByteArray(maxLen)
        for (buf in buffers) {
            for (i in buf.indices) {
                result[i] = (result[i].toInt() xor buf[i].toInt()).toByte()
            }
        }
        return result
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
            udpConsecutiveFailures = 0
        } catch (e: Exception) {
            Logger.w("AudioEngine", "UDP send failed: ${e.message}")
            udpConsecutiveFailures++
            if (udpConsecutiveFailures >= MAX_UDP_CONSECUTIVE_FAILURES) {
                val err = UdpCircuitBreakerException("UDP send failed $udpConsecutiveFailures consecutive times, triggering disconnect")
                Logger.e("AudioEngine", err.message!!)
                throw err
            }
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
        val nsChanged = this.enableNS != enableNS
        val agcChanged = this.enableAGC != enableAGC

        this.enableNS = enableNS
        this.enableAGC = enableAGC
        // Note: nsIntensity, agcAttackRate, agcDecayRate, dereverbLevel, amplification,
        // processingChain, and equalizerConfig are currently ignored on Android
        // as it uses hardware-based processing.

        try {
            noiseSuppressor?.enabled = enableNS
            automaticGainControl?.enabled = enableAGC
        } catch (e: Exception) {
            Logger.e("AudioEngine", "Error updating audio effects: ${e.message}")
        }

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

    actual val webUrl: Flow<String> = MutableStateFlow("")
    actual val webClientCount: Flow<Int> = MutableStateFlow(0)
}

private class UdpCircuitBreakerException(message: String) : Exception(message)
