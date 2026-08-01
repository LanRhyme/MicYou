package com.lanrhyme.micyou.audio
import com.lanrhyme.micyou.R

import android.content.Intent
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
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withTimeoutOrNull
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
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.coroutineContext
import com.lanrhyme.micyou.audio.AndroidAudioSource
import com.lanrhyme.micyou.audio.AudioLevelData
import com.lanrhyme.micyou.audio.AudioMetrics
import com.lanrhyme.micyou.network.AudioPacketMessage
import com.lanrhyme.micyou.network.AudioPacketMessageOrdered
import com.lanrhyme.micyou.network.calculateUdpPort
import com.lanrhyme.micyou.network.MessageWrapper
import com.lanrhyme.micyou.network.PACKET_MAGIC
import com.lanrhyme.micyou.network.UDP_PACKET_MAGIC
import com.lanrhyme.micyou.service.AudioService
import com.lanrhyme.micyou.util.ContextHelper
import com.lanrhyme.micyou.util.getString
import com.lanrhyme.micyou.util.Logger
import com.lanrhyme.micyou.util.PerformanceConfig
import com.lanrhyme.micyou.viewmodel.ConnectionMode
import com.lanrhyme.micyou.viewmodel.NoiseReductionType
import com.lanrhyme.micyou.viewmodel.StreamState
import com.lanrhyme.micyou.viewmodel.TransportProtocol
import com.lanrhyme.micyou.network.hasControlMessage
import com.lanrhyme.micyou.network.MuteMessage
import com.lanrhyme.micyou.network.PongMessage
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

class AudioEngine constructor() {
    init {
        activeEngine = this
    }

    companion object {
        private const val MAX_UDP_CONSECUTIVE_FAILURES = 500
        private const val HEARTBEAT_TIMEOUT_MS = 5000L
        private const val STOP_TIMEOUT_MS = 5000L
        private const val FEC_GROUP_SIZE = 12 // 每 12 个包生成一个 FEC 包（约 87ms @44100Hz）
        private val sessionCounter = AtomicLong(System.currentTimeMillis().coerceAtLeast(1L))

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
    val streamState: Flow<StreamState> = _state

    fun currentStreamState(): StreamState = _state.value
    private val _audioLevels = MutableStateFlow(0f)
    val audioLevels: Flow<Float> = _audioLevels

    private val _rawSpectrum = MutableStateFlow(FloatArray(0))
    val rawSpectrum: Flow<FloatArray> = _rawSpectrum

    private val _processedSpectrum = MutableStateFlow(FloatArray(0))
    val processedSpectrum: Flow<FloatArray> = _processedSpectrum
    private val _audioLevelData = MutableStateFlow(AudioLevelData.SILENT)
    val audioLevelData: Flow<AudioLevelData> = _audioLevelData
    private val _audioMetrics = MutableStateFlow<AudioMetrics?>(null)
    val audioMetrics: Flow<AudioMetrics?> = _audioMetrics
    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: Flow<String?> = _lastError

    private val _isMuted = MutableStateFlow(false)
    val isMuted: Flow<Boolean> = _isMuted

    private var job: Job? = null
    private var stopTimedOutJob: Job? = null
    private var configRestartJob: Job? = null
    private var configRestartRequest: Long = 0
    private val startStopMutex = Mutex()
    private val lifecycleScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val proto = ProtoBuf { }
    
    @Volatile
    private var sendChannel: Channel<MessageWrapper>? = null
    @Volatile
    private var activeRecorder: AudioRecord? = null
    @Volatile
    private var activeTcpSocket: Socket? = null
    @Volatile
    private var activeInput: ByteReadChannel? = null
    @Volatile
    private var activeOutput: ByteWriteChannel? = null
    
    @Volatile
    private var udpSocket: DatagramSocket? = null
    @Volatile
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

    private var savedIp: String = ""
    private var savedPort: Int = 0
    private var savedMode: ConnectionMode = ConnectionMode.Wifi
    private var savedSampleRate: SampleRate = SampleRate.Rate44100
    private var savedChannelCount: ChannelCount = ChannelCount.Mono
    private var savedAudioFormat: AudioFormat = AudioFormat.PCM_16BIT
    private var savedTransportProtocol: TransportProtocol = TransportProtocol.Both
    @Volatile
    private var desiredRunning: Boolean = false
    @Volatile
    private var lifecycleGeneration: Long = 0
    private var startRequestGeneration: Long = 0

    private val CHECK_1 = "MicYouCheck1"
    private val CHECK_2 = "MicYouCheck2"

    suspend fun start(
        ip: String, 
        port: Int, 
        mode: ConnectionMode, 
        isClient: Boolean,
        sampleRate: SampleRate,
        channelCount: ChannelCount,
        audioFormat: AudioFormat,
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

        val connectionComplete = CompletableDeferred<Unit>()
        var launchedJob: Job? = null
        var stoppingJob: Job? = null
        var requestToken = 0L
        var firstAttempt = true
        var startIgnored = false
        val sessionId = sessionCounter.updateAndGet { current ->
            if (current == Long.MAX_VALUE) 1L else current + 1L
        }
        while (launchedJob == null && !startIgnored) {
            stoppingJob = null
            startStopMutex.withLock {
                if (firstAttempt) {
                    configRestartJob?.takeIf { it !== coroutineContext[Job] }?.cancel()
                    val wasDesiredRunning = desiredRunning
                    val timedOutJob = stopTimedOutJob
                    if (timedOutJob != null && !timedOutJob.isCompleted) {
                        throw IllegalStateException("Previous audio session did not stop within $STOP_TIMEOUT_MS ms")
                    }
                    if (timedOutJob?.isCompleted == true) stopTimedOutJob = null
                    if (wasDesiredRunning && job?.isCompleted == false) {
                        Logger.w("AudioEngine", "AudioEngine already running, ignoring start request")
                        connectionComplete.complete(Unit)
                        startIgnored = true
                        return@withLock
                    }
                    desiredRunning = true
                    startRequestGeneration++
                    requestToken = startRequestGeneration
                } else if (!desiredRunning || requestToken != startRequestGeneration) {
                    throw CancellationException("Audio start request superseded while waiting for previous session")
                }

                val currentJob = job
                if (currentJob != null && !currentJob.isCompleted) {
                    Logger.i("AudioEngine", "Waiting for the previous stopped generation before starting")
                    stoppingJob = currentJob
                } else {
                lifecycleGeneration++
                val sessionGeneration = lifecycleGeneration
                activeEngine = this
                _state.value = StreamState.Connecting
                val sessionJob = lifecycleScope.launch(start = CoroutineStart.LAZY) {
                    var recorder: AudioRecord? = null
                    var sessionUdpSocket: DatagramSocket? = null
                    var sessionUdpAddress: InetSocketAddress? = null
                    var sessionNoiseSuppressor: NoiseSuppressor? = null
                    var sessionAutomaticGainControl: AutomaticGainControl? = null
                    var sessionUdpConsecutiveFailures = 0
                    var sessionLastPingReceivedTime = System.currentTimeMillis()
                    val channel = Channel<MessageWrapper>(capacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)
                    startStopMutex.withLock {
                        if (lifecycleGeneration == sessionGeneration && desiredRunning) {
                            sendChannel = channel
                        }
                    }

                    var tcpSocket: Socket? = null
                    var input: ByteReadChannel? = null
                    var output: ByteWriteChannel? = null
                    var selectorManager: SelectorManager? = null
                    var closeConnection: () -> Unit = {}
                    
                    try {
                        if (lifecycleGeneration != sessionGeneration || !desiredRunning) {
                            throw CancellationException("Audio session superseded before initialization")
                        }
                        val androidSampleRate = sampleRate.value
                        val androidChannelConfig = if (channelCount == ChannelCount.Stereo) 
                            android.media.AudioFormat.CHANNEL_IN_STEREO 
                        else 
                            android.media.AudioFormat.CHANNEL_IN_MONO
                            
                        val androidAudioFormat = when(audioFormat) {
                            AudioFormat.PCM_8BIT -> android.media.AudioFormat.ENCODING_PCM_8BIT
                            AudioFormat.PCM_16BIT -> android.media.AudioFormat.ENCODING_PCM_16BIT
                            AudioFormat.PCM_FLOAT -> android.media.AudioFormat.ENCODING_PCM_FLOAT
                            else -> android.media.AudioFormat.ENCODING_PCM_16BIT
                        }
    val minBufSize = AudioRecord.getMinBufferSize(androidSampleRate, androidChannelConfig, androidAudioFormat)

                        if (minBufSize <= 0 || minBufSize == AudioRecord.ERROR || minBufSize == AudioRecord.ERROR_BAD_VALUE) {
                            val msg = String.format(getString(R.string.errorAudioFormatNotSupported), audioFormat.label, androidAudioFormat.toString(), androidSampleRate)
                            Logger.e("AudioEngine", msg + ", minBufSize=$minBufSize")
                            throw IllegalStateException(msg)
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
                            throw IllegalStateException(getString(R.string.errorRecordingPermissionDenied), e)
                        }

                        val sessionRecorder = requireNotNull(recorder)
                        startStopMutex.withLock {
                            if (lifecycleGeneration != sessionGeneration || !desiredRunning) {
                                throw CancellationException("Audio session superseded during recorder initialization")
                            }
                            activeRecorder = sessionRecorder
                        }
                        if (sessionRecorder.state != AudioRecord.STATE_INITIALIZED) {
                            val msg = getString(R.string.errorAudioRecordInitFailed)
                            Logger.e("AudioEngine", msg)
                            throw IllegalStateException(msg)
                        }

                        try {
                            if (NoiseSuppressor.isAvailable()) {
                                sessionNoiseSuppressor = NoiseSuppressor.create(sessionRecorder.audioSessionId)
                                sessionNoiseSuppressor?.enabled = enableNS
                                noiseSuppressor = sessionNoiseSuppressor
                                Logger.d("AudioEngine", "NoiseSuppressor initialized, enabled=$enableNS")
                            } else {
                                Logger.d("AudioEngine", "NoiseSuppressor not available")
                            }
                            
                            if (AutomaticGainControl.isAvailable()) {
                                sessionAutomaticGainControl = AutomaticGainControl.create(sessionRecorder.audioSessionId)
                                sessionAutomaticGainControl?.enabled = enableAGC
                                automaticGainControl = sessionAutomaticGainControl
                                Logger.d("AudioEngine", "AutomaticGainControl initialized, enabled=$enableAGC")
                            } else {
                                Logger.d("AudioEngine", "AutomaticGainControl not available")
                            }
                        } catch (e: Exception) {
                             Logger.w("AudioEngine", "Failed to initialize audio effects: ${e.message}")
                        }
                        
                        val sessionSelectorManager = SelectorManager(Dispatchers.IO)
                        selectorManager = sessionSelectorManager

                        val targetIp = if (mode == ConnectionMode.Usb) "127.0.0.1" else ip
                        Logger.i("AudioEngine", "Connecting with protocol $transportProtocol to $targetIp:$port")

                        // TCP-only 模式或 Both 模式：需要 TCP 连接进行握手
                        if (transportProtocol == TransportProtocol.Tcp || transportProtocol == TransportProtocol.Both) {
                            Logger.i("AudioEngine", "Connecting via TCP to $targetIp:$port")
                            val socketBuilder = aSocket(sessionSelectorManager)
                            tcpSocket = socketBuilder.tcp().connect(targetIp, port) {
                                keepAlive = true
                                socketTimeout = 10000L
                                noDelay = true
                            }
                            Logger.i("AudioEngine", "TCP connected to $targetIp:$port")
                            input = tcpSocket.openReadChannel()
                            output = tcpSocket.openWriteChannel(autoFlush = true)
                            startStopMutex.withLock {
                                if (lifecycleGeneration != sessionGeneration || !desiredRunning) {
                                    throw CancellationException("Audio session superseded during TCP connection")
                                }
                                activeTcpSocket = tcpSocket
                                activeInput = input
                                activeOutput = output
                            }
                        }

                        // UDP 音频传输：WiFi 模式下且协议为 TCP+UDP
                        if (mode == ConnectionMode.Wifi && transportProtocol == TransportProtocol.Both) {
                            val udpPort = calculateUdpPort(port)
                            Logger.i("AudioEngine", "Connecting via UDP to $targetIp:$udpPort")
                            sessionUdpSocket = DatagramSocket().also {
                                it.sendBufferSize = 256 * 1024 // 256KB send buffer
                                Logger.d("AudioEngine", "UDP send buffer: ${it.sendBufferSize / 1024}KB")
                            }
                            sessionUdpAddress = InetSocketAddress(targetIp, udpPort)
                            startStopMutex.withLock {
                                if (lifecycleGeneration != sessionGeneration || !desiredRunning) {
                                    throw CancellationException("Audio session superseded during UDP setup")
                                }
                                udpSocket = sessionUdpSocket
                                udpServerAddress = sessionUdpAddress
                            }
                            Logger.i("AudioEngine", "UDP connected to $targetIp:$udpPort")
                        }

                        closeConnection = {
                            tcpSocket?.close()
                            sessionUdpSocket?.close()
                        }

                        // Handshake (always via TCP if available, otherwise skip)
                        if (tcpSocket != null) {
                            val out = output ?: throw IllegalStateException("TCP output channel unavailable")
                            val inChannel = input ?: throw IllegalStateException("TCP input channel unavailable")
                            Logger.d("AudioEngine", "Starting handshake")
                            out.writeFully(CHECK_1.encodeToByteArray())
                            out.flush()
                            val responseBuffer = ByteArray(CHECK_2.length)
                            inChannel.readFully(responseBuffer, 0, responseBuffer.size)

                            if (!responseBuffer.decodeToString().equals(CHECK_2)) {
                                val msg = getString(R.string.errorHandshakeFailedDetailed)
                                Logger.e("AudioEngine", "Handshake failed: received ${responseBuffer.decodeToString()}")
                                throw IllegalStateException(msg)
                            }
                            Logger.i("AudioEngine", "Handshake successful")
                        } else {
                            // UDP-only 模式不需要握手（但这可能会有连接问题）
                            Logger.w("AudioEngine", "UDP-only mode: skipping handshake")
                        }

                        if (lifecycleGeneration != sessionGeneration || !desiredRunning) {
                            throw CancellationException("Audio session superseded before recording started")
                        }
                        recorder.startRecording()
                        if (lifecycleGeneration != sessionGeneration || !desiredRunning) {
                            throw CancellationException("Audio session superseded while recording started")
                        }
                        _state.value = StreamState.Streaming
                        _lastError.value = null
                        connectionComplete.complete(Unit)

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

                                    val localUdpSocket = sessionUdpSocket
                                    val localUdpAddress = sessionUdpAddress
                                    if (shouldUseUdp && localUdpSocket != null && localUdpAddress != null) {
                                        // 通过 UDP 发送
                                        sessionUdpConsecutiveFailures = sendAudioPacketViaUdp(msg, localUdpSocket, localUdpAddress, sessionUdpConsecutiveFailures)
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
                                val inChannel = input ?: throw IllegalStateException("TCP input channel unavailable for reader")
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
                                                    sessionLastPingReceivedTime = System.currentTimeMillis()
                                                    channel.send(MessageWrapper(pong = PongMessage(wrapper.ping.timestamp)))
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

                        channel.send(MessageWrapper(mute = MuteMessage(_isMuted.value)))
                        // Use read buffer sized to avoid IP fragmentation on WiFi
                        // Path MTU = 1500, minus IP(20)+UDP(8)+header(8)+ProtoBuf(~30) ≈ 1434 safe payload
    val udpSafePayloadSize = 1400
                        val bytesPerSample = when (androidAudioFormat) {
                            android.media.AudioFormat.ENCODING_PCM_8BIT -> 1
                            android.media.AudioFormat.ENCODING_PCM_16BIT -> 2
                            android.media.AudioFormat.ENCODING_PCM_FLOAT -> 4
                            else -> 2
                        }
                        val frameAlignBytes = bytesPerSample * channelCount.value
                        val alignedPayloadSize = (udpSafePayloadSize / frameAlignBytes) * frameAlignBytes
                        val readBufSize = minOf(minBufSize, alignedPayloadSize).coerceAtLeast(frameAlignBytes)
                        val buffer = ByteArray(readBufSize)
                        val floatBuffer = if (androidAudioFormat == android.media.AudioFormat.ENCODING_PCM_FLOAT) FloatArray(readBufSize / 4) else null
                        var sequenceNumber = 0
                        var lastAudioData: ByteArray? = null
                        var fecGroupBuffer = mutableListOf<ByteArray>()
                        var fecGroupStartSeq = 0
                        var lastSequenceNumber = -1
                        sessionLastPingReceivedTime = System.currentTimeMillis()

                        while (isActive) {
                            if (writerJob.isCancelled || writerJob.isCompleted) throw Exception("Writer job failed")
                            if (readerJob != null && (readerJob.isCancelled || readerJob.isCompleted)) throw Exception("Reader job failed - connection lost")
                            if (readerJob != null && System.currentTimeMillis() - sessionLastPingReceivedTime > HEARTBEAT_TIMEOUT_MS) {
                                throw Exception("Heartbeat timeout - server unreachable ($HEARTBEAT_TIMEOUT_MS ms)")
                            }

                            var readBytes = 0
                            val audioData: ByteArray

                            if (androidAudioFormat == android.media.AudioFormat.ENCODING_PCM_FLOAT && floatBuffer != null) {
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
                                        audioPacket = AudioPacketMessageOrdered(
                                            sequenceNumber = sequenceNumber++,
                                            audioPacket = packet,
                                            timestamp = System.currentTimeMillis(),
                                            sessionId = sessionId
                                        )
                                    )

                                    val localUdpSocket = sessionUdpSocket
                                    val localUdpAddress = sessionUdpAddress
                                    if (localUdpSocket != null && localUdpAddress != null) {
                                        sessionUdpConsecutiveFailures = sendAudioPacketViaUdp(wrapper, localUdpSocket, localUdpAddress, sessionUdpConsecutiveFailures)

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
                                                    fecSequenceNumber = fecGroupStartSeq,
                                                    sessionId = sessionId
                                                )
                                            )
                                            sessionUdpConsecutiveFailures = sendAudioPacketViaUdp(fecWrapper, localUdpSocket, localUdpAddress, sessionUdpConsecutiveFailures)
                                            fecGroupBuffer = mutableListOf()
                                            fecGroupStartSeq = sequenceNumber
                                        }
                                    } else {
                                        channel.send(wrapper)
                                    }
                                }
                            }
                        }
                    } catch (e: CancellationException) {
                        connectionComplete.completeExceptionally(e)
                        throw e
                    } catch (e: Exception) {
                        val errorMsg = when {
                            e is UdpCircuitBreakerException -> e.message ?: getString(R.string.connectionDisconnected)
                            e is java.net.ConnectException && e.message?.contains("Connection refused", ignoreCase = true) == true ->
                                String.format(getString(R.string.connectionRejected), port)
                            e is java.net.SocketTimeoutException ->
                                getString(R.string.connectionTimeout)
                            e is java.net.NoRouteToHostException ->
                                getString(R.string.connectionUnreachable)
                            e.message?.contains("Heartbeat timeout", ignoreCase = true) == true ->
                                e.message ?: getString(R.string.connectionUnreachable)
                            e.message?.contains("Reader job failed", ignoreCase = true) == true ->
                                getString(R.string.connectionDisconnected)
                            else -> e.message ?: getString(R.string.connectionDisconnected)
                        }
                        Logger.e("AudioEngine", "Connection lost", e)
                        if (lifecycleGeneration == sessionGeneration) {
                            _state.value = StreamState.Error
                            _lastError.value = errorMsg
                        }
                        connectionComplete.completeExceptionally(Exception(errorMsg, e))
                    } finally {
                        connectionComplete.completeExceptionally(CancellationException("Audio session ended before startup completed"))
                        Logger.d("AudioEngine", "Cleaning up resources for generation $sessionGeneration")
                        channel.close()
                        try {
                            recorder?.stop()
                        } catch (_: Exception) {
                        }
                        try {
                            recorder?.release()
                        } catch (e: Exception) {
                            Logger.w("AudioEngine", "Failed to release recorder: ${e.message}")
                        }
                        try {
                            closeConnection()
                        } catch (e: Exception) {
                            Logger.w("AudioEngine", "Failed to close connection: ${e.message}")
                        }
                        try {
                            selectorManager?.close()
                        } catch (e: Exception) {
                            Logger.w("AudioEngine", "Failed to close selector manager: ${e.message}")
                        }
                        try {
                            sessionNoiseSuppressor?.release()
                            sessionAutomaticGainControl?.release()
                        } catch (e: Exception) {
                            Logger.w("AudioEngine", "Failed to release audio effects: ${e.message}")
                        }

                        startStopMutex.withLock {
                            if (sendChannel === channel) sendChannel = null
                            if (activeRecorder === recorder) activeRecorder = null
                            if (activeTcpSocket === tcpSocket) activeTcpSocket = null
                            if (activeInput === input) activeInput = null
                            if (activeOutput === output) activeOutput = null
                            if (udpSocket === sessionUdpSocket) {
                                udpSocket = null
                                udpServerAddress = null
                            }
                            if (noiseSuppressor === sessionNoiseSuppressor) noiseSuppressor = null
                            if (automaticGainControl === sessionAutomaticGainControl) automaticGainControl = null
                            if (job === coroutineContext[Job]) job = null
                            if (stopTimedOutJob === coroutineContext[Job]) stopTimedOutJob = null
                            if (lifecycleGeneration == sessionGeneration) {
                                if (_state.value != StreamState.Error) _state.value = StreamState.Idle
                                if (!desiredRunning) clearActiveEngine()
                            }
                        }

                        if (lifecycleGeneration == sessionGeneration) {
                            val context = ContextHelper.getContext()
                            if (context != null) {
                                val intent = Intent(context, AudioService::class.java).apply { action = AudioService.ACTION_STOP }
                                context.startService(intent)
                            }
                            Logger.i("AudioEngine", "AudioEngine stopped")
                        }
                    }
                }
                job = sessionJob
                launchedJob = sessionJob
                sessionJob.start()
                }
            }

            val previousJob = stoppingJob
            if (previousJob != null) {
                val previousStopped = withTimeoutOrNull(STOP_TIMEOUT_MS) {
                    previousJob.join()
                    true
                } == true
                if (!previousStopped) {
                    val error = IllegalStateException("Previous audio session did not stop within $STOP_TIMEOUT_MS ms")
                    startStopMutex.withLock {
                        if (requestToken == startRequestGeneration) {
                            desiredRunning = false
                            startRequestGeneration++
                            stopTimedOutJob = previousJob
                            _state.value = StreamState.Error
                            _lastError.value = error.message
                        }
                    }
                    throw error
                }
                firstAttempt = false
            }
        }

        try {
            connectionComplete.await()
        } catch (e: Exception) {
            launchedJob?.cancel()
            if (launchedJob != null) {
                withTimeoutOrNull(STOP_TIMEOUT_MS) { launchedJob?.join() }
            }
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
    private fun sendAudioPacketViaUdp(
        wrapper: MessageWrapper,
        socket: DatagramSocket,
        serverAddress: InetSocketAddress,
        consecutiveFailures: Int
    ): Int {
        return try {
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
            val udpPacket = DatagramPacket(header + packetBytes, 8 + length, serverAddress)
            socket.send(udpPacket)
            0
        } catch (e: Exception) {
            Logger.w("AudioEngine", "UDP send failed: ${e.message}")
            val updatedFailures = consecutiveFailures + 1
            if (updatedFailures >= MAX_UDP_CONSECUTIVE_FAILURES) {
                val err = UdpCircuitBreakerException("UDP send failed $updatedFailures consecutive times, triggering disconnect")
                Logger.e("AudioEngine", err.message!!)
                throw err
            }
            updatedFailures
        }
    }
    
    fun stop() {
        lifecycleScope.launch {
            try {
                stopAndWait()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.e("AudioEngine", "Failed to stop audio stream", e)
            }
        }
    }

    suspend fun stopAndWait(userInitiated: Boolean = true): Boolean {
        var currentJob: Job? = null
        var currentRecorder: AudioRecord? = null
        var currentSocket: Socket? = null
        var currentInput: ByteReadChannel? = null
        var currentOutput: ByteWriteChannel? = null
        var currentUdpSocket: DatagramSocket? = null
        var currentChannel: Channel<MessageWrapper>? = null
        var stopGeneration = 0L

        startStopMutex.withLock {
            if (userInitiated) {
                desiredRunning = false
                lifecycleGeneration++
                startRequestGeneration++
                configRestartRequest++
                configRestartJob?.cancel()
                configRestartJob = null
            }
            stopGeneration = lifecycleGeneration
            currentJob = job
            currentRecorder = activeRecorder
            currentSocket = activeTcpSocket
            currentInput = activeInput
            currentOutput = activeOutput
            currentUdpSocket = udpSocket
            currentChannel = sendChannel
            currentJob?.cancel(CancellationException("AudioEngine stopping"))
        }

        // Keep interruption independent from the caller: AudioRecord.stop() may block and
        // must neither run on Main nor prevent this suspend function's timeout from firing.
        val interruptJob = lifecycleScope.launch(Dispatchers.IO) {
            try {
                currentInput?.cancel(CancellationException("AudioEngine stopping"))
            } catch (_: Exception) {
            }
            try {
                currentChannel?.close()
            } catch (_: Exception) {
            }
            try {
                currentUdpSocket?.close()
            } catch (_: Exception) {
            }
            try {
                currentSocket?.close()
            } catch (_: Exception) {
            }
            try {
                currentRecorder?.stop()
            } catch (_: Exception) {
            }
        }

        val stopCompletionJob = lifecycleScope.launch {
            interruptJob.join()
            currentJob?.join()
        }
        val stopped = withTimeoutOrNull(STOP_TIMEOUT_MS) {
            stopCompletionJob.join()
            true
        } == true

        startStopMutex.withLock {
            if (!stopped) stopTimedOutJob = stopCompletionJob
            if (stopped) {
                if (stopTimedOutJob === currentJob || stopTimedOutJob === stopCompletionJob) stopTimedOutJob = null
                if (job === currentJob) job = null
                if (activeRecorder === currentRecorder) activeRecorder = null
                if (activeTcpSocket === currentSocket) activeTcpSocket = null
                if (activeInput === currentInput) activeInput = null
                if (activeOutput === currentOutput) activeOutput = null
                if (udpSocket === currentUdpSocket) {
                    udpSocket = null
                    udpServerAddress = null
                }
                if (sendChannel === currentChannel) sendChannel = null
            }

            if (lifecycleGeneration == stopGeneration && (userInitiated || !desiredRunning)) {
                if (stopped) {
                    _state.value = StreamState.Idle
                } else {
                    _state.value = StreamState.Error
                    _lastError.value = "AudioEngine stop timed out after $STOP_TIMEOUT_MS ms"
                }
            }
            if (userInitiated && stopped) clearActiveEngine()
        }

        if (userInitiated || !stopped) stopStreamingNotification()
        if (!stopped) {
            val error = IllegalStateException("AudioEngine stop timed out after $STOP_TIMEOUT_MS ms")
            Logger.e("AudioEngine", "Timed out waiting $STOP_TIMEOUT_MS ms for audio session to stop")
            throw error
        }
        return true
    }

    private fun stopStreamingNotification() {
        val context = ContextHelper.getContext() ?: return
        try {
            val intent = Intent(context, AudioService::class.java).apply { action = AudioService.ACTION_STOP }
            context.startService(intent)
        } catch (e: Exception) {
            Logger.w("AudioEngine", "Failed to stop streaming notification: ${e.message}")
        }
    }
    
    fun setMonitoring(enabled: Boolean) { }

    val installProgress: Flow<String?> = MutableStateFlow(null)
    
    suspend fun installDriver() { }

    suspend fun setMute(muted: Boolean) {
        _isMuted.value = muted
        if (_state.value == StreamState.Streaming || _state.value == StreamState.Connecting) {
             try {
                 sendChannel?.send(MessageWrapper(mute = MuteMessage(muted)))
             } catch (e: Exception) {
                 Logger.e("AudioEngine", "Failed to send mute message: ${e.message}")
             }
        }
    }

    fun updateConfig(
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

        if ((nsChanged || agcChanged) && desiredRunning && _state.value == StreamState.Streaming) {
            Logger.i("AudioEngine", "Hardware processing changed, restarting audio stream...")
            scheduleConfigRestart()
        }
    }

    fun setAudioSource(sourceName: String) {
        val source = try {
            AndroidAudioSource.valueOf(sourceName)
        } catch (e: Exception) {
            AndroidAudioSource.Mic
        }

        if (this.audioSource != source) {
            this.audioSource = source
            Logger.d("AudioEngine", "Audio source changed to: ${source.name}")

            if (desiredRunning && _state.value == StreamState.Streaming) {
                Logger.i("AudioEngine", "Restarting audio stream with new source...")
                scheduleConfigRestart()
            }
        }
    }

    private fun scheduleConfigRestart() {
        val request = ++configRestartRequest
        configRestartJob?.cancel()
        configRestartJob = lifecycleScope.launch {
            val capturedGeneration = startStopMutex.withLock {
                if (!desiredRunning || request != configRestartRequest) return@launch
                lifecycleGeneration
            }

            try {
                stopAndWait(userInitiated = false)

                val shouldRestart = startStopMutex.withLock {
                    desiredRunning &&
                        lifecycleGeneration == capturedGeneration &&
                        request == configRestartRequest
                }
                if (!shouldRestart) return@launch

                start(savedIp, savedPort, savedMode, true, savedSampleRate, savedChannelCount, savedAudioFormat, savedTransportProtocol)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.e("AudioEngine", "Failed to restart audio stream after config change", e)
            }
        }
    }

    fun setStreamingNotificationEnabled(enabled: Boolean) {
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

    private fun calculateAudioLevelData(buffer: ByteArray, format: AudioFormat): AudioLevelData {
        if (buffer.isEmpty()) return AudioLevelData.SILENT
        var sum = 0.0
        var maxSample = 0.0
        var sampleCount = 0
        when (format) {
            AudioFormat.PCM_FLOAT -> {
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
            AudioFormat.PCM_8BIT -> {
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

    fun updatePerformanceConfig(config: PerformanceConfig) {
        Logger.d("AudioEngine", "Android does not support dynamic performance config adjustment")
    }
}

private class UdpCircuitBreakerException(message: String) : Exception(message)