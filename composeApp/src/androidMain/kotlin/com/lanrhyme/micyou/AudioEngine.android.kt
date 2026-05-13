package com.lanrhyme.micyou

import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
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
import kotlinx.coroutines.CancellationException
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

    private val _enableSpeakerMode = MutableStateFlow(false)
    actual val enableSpeakerMode: Flow<Boolean> = _enableSpeakerMode

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
    private var enableAEC: Boolean = false
    @Volatile
    private var isSpeakerModeActive: Boolean = false
    @Volatile
    private var audioSource: AndroidAudioSource = AndroidAudioSource.Mic

    private var noiseSuppressor: NoiseSuppressor? = null
    private var automaticGainControl: AutomaticGainControl? = null
    private var acousticEchoCanceler: AcousticEchoCanceler? = null
    @Volatile
    private var audioTrack: AudioTrack? = null

    private var savedIp: String = ""
    private var savedPort: Int = 0
    private var savedMode: ConnectionMode = ConnectionMode.Wifi
    private var savedSampleRate: SampleRate = SampleRate.Rate44100
    private var savedChannelCount: ChannelCount = ChannelCount.Mono
    private var savedAudioFormat: com.lanrhyme.micyou.AudioFormat = com.lanrhyme.micyou.AudioFormat.PCM_16BIT
    private var isRunning: Boolean = false

    private val CHECK_1 = "MicYouCheck1"
    private val CHECK_2 = "MicYouCheck2"

    actual fun setAEC(enabled: Boolean) {
        val changed = this.enableAEC != enabled
        this.enableAEC = enabled
        try {
            if (acousticEchoCanceler != null) {
                acousticEchoCanceler!!.enabled = enabled
                Logger.d("AudioEngine", "AEC effect ${if (enabled) "enabled" else "disabled"}, hasControl=${acousticEchoCanceler!!.hasControl()}")
            } else if (enabled) {
                Logger.w("AudioEngine", "AEC enabled but AcousticEchoCanceler is null - effect not available")
            }

            val context = ContextHelper.getContext()
            val audioManager = context?.getSystemService(android.content.Context.AUDIO_SERVICE) as? android.media.AudioManager
            if (isRunning) {
                if (enabled) {
                    audioManager?.mode = android.media.AudioManager.MODE_IN_COMMUNICATION
                    audioManager?.isSpeakerphoneOn = true
                    audioManager?.isBluetoothScoOn = false
                } else {
                    audioManager?.mode = android.media.AudioManager.MODE_NORMAL
                    audioManager?.isSpeakerphoneOn = false
                }
            }
        } catch (e: Exception) {
            Logger.e("AudioEngine", "Error toggling AEC effect: ${e.message}")
        }

        // AEC requires VOICE_COMMUNICATION audio source to work properly.
        // Restart the stream so that the AudioRecord is recreated with the correct source.
        if (changed && isRunning && _state.value == StreamState.Streaming) {
            Logger.i("AudioEngine", "AEC changed, restarting audio stream...")
            CoroutineScope(Dispatchers.IO).launch {
                stop()
                delay(500)
                start(savedIp, savedPort, savedMode, true, savedSampleRate, savedChannelCount, savedAudioFormat)
            }
        }
    }

    private val jitterBuffer = PlaybackJitterBuffer(capacity = 256, minPrebuffer = 20)

    actual fun setSpeakerMode(enabled: Boolean) {
        if (this.isSpeakerModeActive != enabled) {
            this.isSpeakerModeActive = enabled
            _enableSpeakerMode.value = enabled
            Logger.i("AudioEngine", "Speaker mode ${if (enabled) "enabled" else "disabled"}")
            
            if (!enabled) {
                // Clear the queue
                CoroutineScope(Dispatchers.IO).launch {
                    jitterBuffer.clear()
                }
                audioTrack?.stop()
                audioTrack?.release()
                audioTrack = null
            }

            if (_state.value == StreamState.Streaming || _state.value == StreamState.Connecting) {
                try {
                    sendChannel?.trySend(MessageWrapper(speakerMode = SpeakerModeMessage(enabled)))
                } catch (e: Exception) {
                    Logger.e("AudioEngine", "Failed to send speaker mode message: ${e.message}")
                }
            }
        }
    }

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
        Logger.i("AudioEngine", "Starting Android AudioEngine: mode=$mode, ip=$ip, port=$port, sampleRate=${sampleRate.value}, channels=${channelCount.label}, format=${audioFormat.label}, AEC=$enableAEC")
        _lastError.value = null

        // Clear JitterBuffer BEFORE starting to ensure stale lastSeq from a previous
        // session doesn't cause all new packets (starting from seq=0) to be dropped.
        // This is critical: stop() doesn't wait for the old coroutine's cleanup, so
        // jitterBuffer.clear() in the finally block may not have run yet.
        jitterBuffer.clear()

        savedIp = ip
        savedPort = port
        savedMode = mode
        savedSampleRate = sampleRate
        savedChannelCount = channelCount
        savedAudioFormat = audioFormat
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
                    
                    var input: ByteReadChannel
                    var output: ByteWriteChannel
                    var closeConnection: () -> Unit = {}
                    
                    try {
                        audioTrack?.stop()
                        audioTrack?.release()
                        audioTrack = null

                        // When AEC is enabled, override the audio format to 16kHz mono 16-bit PCM.
                        // Android's AcousticEchoCanceler is designed for the VOICE_COMMUNICATION
                        // audio path, which on most devices operates at 16kHz mono. Using 48kHz
                        // stereo float with VOICE_COMMUNICATION causes the AEC to silently fail
                        // on many devices because the internal voice path doesn't support those
                        // parameters.
                        var aecOverride = false
                        var androidSampleRate = sampleRate.value
                        var androidChannelConfig = if (channelCount == ChannelCount.Stereo)
                            AudioFormat.CHANNEL_IN_STEREO
                        else
                            AudioFormat.CHANNEL_IN_MONO
                        var androidAudioFormat = when(audioFormat) {
                            com.lanrhyme.micyou.AudioFormat.PCM_8BIT -> AudioFormat.ENCODING_PCM_8BIT
                            com.lanrhyme.micyou.AudioFormat.PCM_16BIT -> AudioFormat.ENCODING_PCM_16BIT
                            com.lanrhyme.micyou.AudioFormat.PCM_FLOAT -> AudioFormat.ENCODING_PCM_FLOAT
                            else -> AudioFormat.ENCODING_PCM_16BIT
                        }

                        if (enableAEC && (androidSampleRate != 16000 || androidChannelConfig != AudioFormat.CHANNEL_IN_MONO || androidAudioFormat != AudioFormat.ENCODING_PCM_16BIT)) {
                            Logger.i("AudioEngine", "AEC enabled: overriding audio format from ${androidSampleRate}Hz/${if(androidChannelConfig == AudioFormat.CHANNEL_IN_STEREO) "stereo" else "mono"}/${androidAudioFormat} to 16000Hz/mono/16bit for voice call path compatibility")
                            androidSampleRate = 16000
                            androidChannelConfig = AudioFormat.CHANNEL_IN_MONO
                            androidAudioFormat = AudioFormat.ENCODING_PCM_16BIT
                            aecOverride = true
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
                            // Set AudioManager mode BEFORE creating AudioRecord.
                            // Some devices require MODE_IN_COMMUNICATION to be active when
                            // the AudioRecord is initialized, otherwise AEC won't work.
                            val context = ContextHelper.getContext()
                            val audioManager = context?.getSystemService(android.content.Context.AUDIO_SERVICE) as? android.media.AudioManager
                            if (enableAEC) {
                                audioManager?.mode = android.media.AudioManager.MODE_IN_COMMUNICATION
                                audioManager?.isSpeakerphoneOn = true
                                audioManager?.isBluetoothScoOn = false
                                Logger.i("AudioEngine", "AudioManager set to MODE_IN_COMMUNICATION + speakerphone ON + bluetooth SCO OFF")
                            } else {
                                audioManager?.mode = android.media.AudioManager.MODE_NORMAL
                                audioManager?.isSpeakerphoneOn = false
                            }

                            val sourceId = if (enableAEC) {
                                MediaRecorder.AudioSource.VOICE_COMMUNICATION
                            } else {
                                audioSource.sourceId
                            }
                            Logger.i("AudioEngine", "Creating AudioRecord: source=$sourceId (AEC=$enableAEC), rate=$androidSampleRate, ch=$androidChannelConfig, fmt=$androidAudioFormat")
                            recorder = try {
                                AudioRecord(
                                    sourceId,
                                    androidSampleRate,
                                    androidChannelConfig,
                                    androidAudioFormat,
                                    minBufSize * 3
                                )
                            } catch (e: Exception) {
                                Logger.w("AudioEngine", "Source $sourceId failed, falling back to MIC (AEC may not work): ${e.message}")
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
                                Logger.d("AudioEngine", "AutomaticGainControl initialized, enabled=$enableAGC, sessionId=${recorder.audioSessionId}")
                            } else {
                                Logger.d("AudioEngine", "AutomaticGainControl not available")
                            }

                            // AEC is NOT created here — it is deferred until the AudioTrack
                            // starts playing. AcousticEchoCanceler needs the AudioTrack's
                            // reference signal (playback output) to be active so it can
                            // correlate it with the microphone input. Creating AEC before
                            // the AudioTrack exists means the AEC has no reference signal
                            // and silently fails to cancel echo.
                            if (enableAEC) {
                                if (AcousticEchoCanceler.isAvailable()) {
                                    Logger.i("AudioEngine", "AEC will be created after AudioTrack starts playing (sessionId=${recorder.audioSessionId})")
                                } else {
                                    Logger.w("AudioEngine", "AcousticEchoCanceler NOT available on this device - AEC will not work")
                                }
                            }
                        } catch (e: Exception) {
                             Logger.w("AudioEngine", "Failed to initialize audio effects: ${e.message}")
                        }
                        
                        val selectorManager = SelectorManager(Dispatchers.IO)
    var tcpSocket: Socket? = null

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
                            val localUdpSocket = DatagramSocket().also {
                                it.sendBufferSize = 512 * 1024 // 512KB send buffer
                                it.receiveBufferSize = 2 * 1024 * 1024 // 2MB receive buffer (match desktop)
                                it.soTimeout = 1000 // 1s read timeout for responsive cancellation
                                Logger.d("AudioEngine", "UDP send buffer: ${it.sendBufferSize / 1024}KB, receive buffer: ${it.receiveBufferSize / 1024}KB")
                            }
                            udpSocket = localUdpSocket
                            udpServerAddress = InetSocketAddress(targetIp, udpPort)
                            Logger.i("AudioEngine", "UDP connected to $targetIp:$udpPort")

                            closeConnection = {
                                tcpSocket?.close()
                                // Use captured local reference, NOT the class field.
                                // If we reference the class field and a new session has
                                // already started, we'd close the new session's socket.
                                localUdpSocket.close()
                            }
                        } else {
                            closeConnection = {
                                tcpSocket?.close()
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

                        // Sync initial state
                        sendChannel?.send(MessageWrapper(speakerMode = SpeakerModeMessage(isSpeakerModeActive)))

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

                        val playbackJob = launch {
                            Logger.d("AudioEngine", "Playback consumer loop started")
                            var lastLogTime = 0L
                            var packetCount = 0
                            try {
                                while (isActive) {
                                    val playback = jitterBuffer.popSuspend()
                                    if (!isSpeakerModeActive) continue
                                    packetCount++
                                    val now = System.currentTimeMillis()
                                    if (now - lastLogTime > 60000) {
                                        Logger.d("AudioEngine", "Playing back audio packets. Received $packetCount packets recently. Buffer size: ${playback.buffer.size}, format: ${playback.audioFormat}")
                                        lastLogTime = now
                                        packetCount = 0
                                    }
                                    try {
                                        var currentTrack = audioTrack

                                        val playbackFormat = when(playback.audioFormat) {
                                            com.lanrhyme.micyou.AudioFormat.PCM_8BIT.value -> AudioFormat.ENCODING_PCM_8BIT
                                            com.lanrhyme.micyou.AudioFormat.PCM_FLOAT.value -> AudioFormat.ENCODING_PCM_FLOAT
                                            else -> AudioFormat.ENCODING_PCM_16BIT
                                        }

                                        // When AEC is enabled, AudioTrack MUST match AudioRecord format
                                        // (16kHz mono 16-bit). The AcousticEchoCanceler correlates the
                                        // reference signal (AudioTrack output) with the captured signal
                                        // (AudioRecord input). If the formats differ (e.g. 48kHz stereo
                                        // vs 16kHz mono), the AEC cannot match the signals and silently
                                        // fails to cancel any echo.
                                        val targetSampleRate: Int
                                        val targetChannelConfig: Int
                                        val targetFormat: Int

                                        if (enableAEC) {
                                            targetSampleRate = 16000
                                            targetChannelConfig = AudioFormat.CHANNEL_OUT_MONO
                                            targetFormat = AudioFormat.ENCODING_PCM_16BIT
                                        } else {
                                            targetSampleRate = playback.sampleRate
                                            targetChannelConfig = if (playback.channelCount == 2)
                                                AudioFormat.CHANNEL_OUT_STEREO
                                            else
                                                AudioFormat.CHANNEL_OUT_MONO
                                            targetFormat = playbackFormat
                                        }

                                        // Check if we need to (re)initialize the AudioTrack
                                        val needsReinit = currentTrack == null ||
                                                          currentTrack.sampleRate != targetSampleRate ||
                                                          currentTrack.audioFormat != targetFormat ||
                                                          currentTrack.channelCount != if (targetChannelConfig == AudioFormat.CHANNEL_OUT_STEREO) 2 else 1

                                        if (needsReinit) {
                                            Logger.i("AudioEngine", "Initializing AudioTrack: rate=$targetSampleRate, channels=${if (targetChannelConfig == AudioFormat.CHANNEL_OUT_STEREO) 2 else 1}, format=$targetFormat (AEC=$enableAEC)")
                                            currentTrack?.stop()
                                            currentTrack?.release()

                                            val mBufSize = AudioTrack.getMinBufferSize(targetSampleRate, targetChannelConfig, targetFormat)
                                            currentTrack = AudioTrack.Builder()
                                                .setAudioAttributes(AudioAttributes.Builder()
                                                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                                                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                                                    .build())
                                                .setAudioFormat(AudioFormat.Builder()
                                                    .setEncoding(targetFormat)
                                                    .setSampleRate(targetSampleRate)
                                                    .setChannelMask(targetChannelConfig)
                                                    .build())
                                                .setBufferSizeInBytes(mBufSize * 2)
                                                .setSessionId(recorder?.audioSessionId ?: android.media.AudioManager.AUDIO_SESSION_ID_GENERATE)
                                                .setTransferMode(AudioTrack.MODE_STREAM)
                                                .build()
                                            currentTrack.play()
                                            audioTrack = currentTrack

                                            // Create AEC AFTER AudioTrack starts playing.
                                            // AcousticEchoCanceler correlates the AudioTrack's playback
                                            // (reference signal) with the AudioRecord's microphone input.
                                            // It must be created while both are active in the same session.
                                            if (enableAEC && acousticEchoCanceler == null && AcousticEchoCanceler.isAvailable()) {
                                                val recSessionId = recorder?.audioSessionId ?: 0
                                                val trackSessionId = currentTrack.audioSessionId
                                                try {
                                                    acousticEchoCanceler = AcousticEchoCanceler.create(recSessionId)
                                                    if (acousticEchoCanceler != null) {
                                                        acousticEchoCanceler!!.enabled = true
                                                        Logger.i("AudioEngine", "AEC created after AudioTrack play: recordSession=$recSessionId, trackSession=$trackSessionId, hasControl=${acousticEchoCanceler!!.hasControl()}")
                                                        if (recSessionId != trackSessionId) {
                                                            Logger.w("AudioEngine", "AEC WARNING: recordSession=$recSessionId != trackSession=$trackSessionId, AEC may not work!")
                                                        }
                                                    } else {
                                                        Logger.w("AudioEngine", "AcousticEchoCanceler.create() returned null after AudioTrack play - device reports available but creation failed")
                                                    }
                                                } catch (e: Exception) {
                                                    Logger.w("AudioEngine", "Failed to create AEC after AudioTrack play: ${e.message}")
                                                }
                                            }

                                            // Re-apply audio routing - some devices reset it
                                            // when a new AudioTrack starts playing.
                                            if (enableAEC) {
                                                try {
                                                    val ctx = ContextHelper.getContext()
                                                    val am = ctx?.getSystemService(android.content.Context.AUDIO_SERVICE) as? android.media.AudioManager
                                                    am?.mode = android.media.AudioManager.MODE_IN_COMMUNICATION
                                                    am?.isSpeakerphoneOn = true
                                                    am?.isBluetoothScoOn = false
                                                } catch (e: Exception) {
                                                    Logger.w("AudioEngine", "Failed to re-apply audio routing: ${e.message}")
                                                }
                                            }
                                        }

                                        // Write audio data, converting format if AEC requires it
                                        if (enableAEC && (playback.sampleRate != targetSampleRate || playback.channelCount != 1 || playbackFormat != targetFormat)) {
                                            val converted = convertPlaybackForAec(playback)
                                            currentTrack.write(converted, 0, converted.size)
                                        } else if (playbackFormat == AudioFormat.ENCODING_PCM_FLOAT) {
                                            val floatBuffer = FloatArray(playback.buffer.size / 4)
                                            ByteBuffer.wrap(playback.buffer).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(floatBuffer)
                                            currentTrack.write(floatBuffer, 0, floatBuffer.size, AudioTrack.WRITE_BLOCKING)
                                        } else {
                                            currentTrack.write(playback.buffer, 0, playback.buffer.size)
                                        }
                                    } catch (e: Exception) {
                                        if (isSpeakerModeActive) {
                                            Logger.e("AudioEngine", "Error writing to AudioTrack: ${e.message}")
                                        }
                                    }
                                }
                            } catch (e: CancellationException) {
                                // Normal
                            } finally {
                                Logger.d("AudioEngine", "Playback consumer loop stopped")
                            }
                        }

                        val udpReaderJob = launch {
                            if (mode != ConnectionMode.Wifi || udpSocket == null) return@launch
                            Logger.d("AudioEngine", "UDP reader loop started")
                            val udpBuffer = ByteArray(65536) // Must be large enough for full audio playback packets
                            val packet = DatagramPacket(udpBuffer, udpBuffer.size)
                            while (isActive) {
                                try {
                                    udpSocket?.receive(packet)
                                    val data = packet.data
                                    val length = packet.length
                                    if (length < 8) continue

                                    val magic = ((data[0].toInt() and 0xFF) shl 24) or
                                                ((data[1].toInt() and 0xFF) shl 16) or
                                                ((data[2].toInt() and 0xFF) shl 8) or
                                                (data[3].toInt() and 0xFF)

                                    if (magic != UDP_PACKET_MAGIC) continue

                                    val payloadLength = ((data[4].toInt() and 0xFF) shl 24) or
                                                        ((data[5].toInt() and 0xFF) shl 16) or
                                                        ((data[6].toInt() and 0xFF) shl 8) or
                                                        (data[7].toInt() and 0xFF)

                                    if (payloadLength <= 0 || payloadLength > length - 8) continue

                                    val payload = data.copyOfRange(8, 8 + payloadLength)
                                    val wrapper = proto.decodeFromByteArray(MessageWrapper.serializer(), payload)

                                    if (wrapper.audioPlayback != null && isSpeakerModeActive) {
                                        jitterBuffer.push(wrapper.audioPlayback)
                                    }
                                } catch (e: java.net.SocketTimeoutException) {
                                    // Normal — soTimeout allows periodic cancellation check
                                } catch (e: Exception) {
                                    if (isActive && !isNormalDisconnect(e)) {
                                        Logger.w("AudioEngine", "UDP receive error: ${e.message}")
                                    }
                                }
                            }
                            Logger.d("AudioEngine", "UDP reader loop stopped")
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
                                                lastPingReceivedTime = System.currentTimeMillis()
                                                sendChannel?.send(MessageWrapper(pong = PongMessage(wrapper.ping.timestamp)))
                                            }
                                            
                                            if (wrapper.audioPlayback != null && isSpeakerModeActive) {
                                                jitterBuffer.push(wrapper.audioPlayback)
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
                        // Use read buffer sized to avoid IP fragmentation on WiFi
                        // Path MTU = 1500, minus IP(20)+UDP(8)+header(8)+ProtoBuf(~30) ≈ 1434 safe payload
                        val udpSafePayloadSize = 1400
                        val readBufSize = if (androidAudioFormat == AudioFormat.ENCODING_PCM_FLOAT) {
                            minOf(minBufSize, udpSafePayloadSize).coerceAtLeast(256)
                        } else {
                            minOf(minBufSize, udpSafePayloadSize).coerceAtLeast(512)
                        }
                        val buffer = ByteArray(readBufSize)
                        val floatBuffer = if (androidAudioFormat == AudioFormat.ENCODING_PCM_FLOAT) FloatArray(readBufSize / 4) else null
                        
                        var sequenceNumber = 0
                        lastPingReceivedTime = System.currentTimeMillis()

                        while (isActive) {
                            if (writerJob.isCancelled || writerJob.isCompleted) throw Exception("Writer job failed")
                            if (readerJob.isCancelled || readerJob.isCompleted) throw Exception("Reader job failed - connection lost")
                            if (System.currentTimeMillis() - lastPingReceivedTime > HEARTBEAT_TIMEOUT_MS) {
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
                            // Capture current references to prevent race condition:
                            // stop() doesn't wait for this finally block to complete,
                            // so a new start() may have already set these class fields
                            // to new objects. We must only release the objects we created,
                            // not the new session's objects.
                            val capturedNS = noiseSuppressor
                            val capturedAGC = automaticGainControl
                            val capturedAEC = acousticEchoCanceler
                            val capturedTrack = audioTrack
                            val capturedRecorder = recorder
                            val capturedSendChannel = sendChannel
                            val capturedUdpSocket = udpSocket

                            capturedNS?.release()
                            capturedAGC?.release()
                            capturedAEC?.release()
                            capturedTrack?.stop()
                            capturedTrack?.release()
                            jitterBuffer.clear()

                            // Null out class fields only if they still point to our objects.
                            // If a new session has already started, leave the new values intact.
                            if (noiseSuppressor === capturedNS) noiseSuppressor = null
                            if (automaticGainControl === capturedAGC) automaticGainControl = null
                            if (acousticEchoCanceler === capturedAEC) acousticEchoCanceler = null
                            if (audioTrack === capturedTrack) audioTrack = null
                            if (recorder === capturedRecorder) recorder = null
                            if (sendChannel === capturedSendChannel) sendChannel = null
                            if (udpSocket === capturedUdpSocket) {
                                udpSocket = null
                                udpServerAddress = null
                            }

                            // Reset AudioManager mode
                            val ctx = ContextHelper.getContext()
                            val audioManager = ctx?.getSystemService(android.content.Context.AUDIO_SERVICE) as? android.media.AudioManager
                            audioManager?.mode = android.media.AudioManager.MODE_NORMAL
                            audioManager?.isSpeakerphoneOn = false

                            capturedSendChannel?.close()
                            capturedRecorder?.stop()
                            capturedRecorder?.release()
                            closeConnection()

                            if (ctx != null) {
                                val intent = Intent(ctx, AudioService::class.java).apply { action = AudioService.ACTION_STOP }
                                ctx.startService(intent)
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

    /**
     * Converts playback audio to 16kHz mono 16-bit PCM for AEC compatibility.
     *
     * Android's AcousticEchoCanceler requires the AudioTrack (reference signal)
     * to match the AudioRecord format (16kHz mono 16-bit). This method performs:
     * 1. Decode input to float samples
     * 2. Mix stereo to mono
     * 3. Resample to 16kHz (linear interpolation)
     * 4. Convert to 16-bit PCM
     */
    private fun convertPlaybackForAec(playback: AudioPlaybackMessage): ShortArray {
        val inputSampleRate = playback.sampleRate
        val inputChannelCount = playback.channelCount
        val inputFormat = playback.audioFormat
        val inputBuffer = playback.buffer

        // Step 1: Decode to float samples (interleaved if stereo)
        val samplesPerChannel: Int
        val floatSamples: FloatArray

        when (inputFormat) {
            com.lanrhyme.micyou.AudioFormat.PCM_FLOAT.value -> {
                samplesPerChannel = inputBuffer.size / (4 * inputChannelCount)
                floatSamples = FloatArray(samplesPerChannel * inputChannelCount)
                ByteBuffer.wrap(inputBuffer).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(floatSamples)
            }
            com.lanrhyme.micyou.AudioFormat.PCM_16BIT.value -> {
                samplesPerChannel = inputBuffer.size / (2 * inputChannelCount)
                floatSamples = FloatArray(samplesPerChannel * inputChannelCount)
                for (i in floatSamples.indices) {
                    val byteIndex = i * 2
                    val sample = ((inputBuffer[byteIndex].toInt() and 0xFF) or
                                 (inputBuffer[byteIndex + 1].toInt() shl 8)).toShort()
                    floatSamples[i] = sample.toFloat() / 32768f
                }
            }
            else -> { // PCM_8BIT
                samplesPerChannel = inputBuffer.size / inputChannelCount
                floatSamples = FloatArray(samplesPerChannel * inputChannelCount)
                for (i in floatSamples.indices) {
                    floatSamples[i] = ((inputBuffer[i].toInt() and 0xFF) - 128) / 128f
                }
            }
        }

        // Step 2: Mix to mono
        val monoSamples: FloatArray
        if (inputChannelCount == 2) {
            monoSamples = FloatArray(samplesPerChannel)
            for (i in 0 until samplesPerChannel) {
                monoSamples[i] = (floatSamples[i * 2] + floatSamples[i * 2 + 1]) / 2f
            }
        } else {
            monoSamples = floatSamples
        }

        // Step 3: Resample to 16kHz using linear interpolation
        val outputSamples: FloatArray
        if (inputSampleRate != 16000) {
            val ratio = inputSampleRate.toDouble() / 16000.0
            val outputLength = (monoSamples.size.toDouble() / ratio).toInt().coerceAtLeast(1)
            outputSamples = FloatArray(outputLength)
            for (i in 0 until outputLength) {
                val srcPos = i * ratio
                val srcIdx = srcPos.toInt()
                val frac = srcPos - srcIdx
                if (srcIdx + 1 < monoSamples.size) {
                    outputSamples[i] = (monoSamples[srcIdx] * (1f - frac.toFloat()) +
                                        monoSamples[srcIdx + 1] * frac.toFloat())
                } else if (srcIdx < monoSamples.size) {
                    outputSamples[i] = monoSamples[srcIdx]
                }
            }
        } else {
            outputSamples = monoSamples
        }

        // Step 4: Convert to 16-bit PCM
        val result = ShortArray(outputSamples.size)
        for (i in outputSamples.indices) {
            result[i] = (outputSamples[i] * 32767f).toInt().coerceIn(-32768, 32767).toShort()
        }
        return result
    }

    actual fun updatePerformanceConfig(config: PerformanceConfig) {
        Logger.d("AudioEngine", "Android does not support dynamic performance config adjustment")
    }

    actual val webUrl: Flow<String> = MutableStateFlow("")
    actual val webClientCount: Flow<Int> = MutableStateFlow(0)
}

private class UdpCircuitBreakerException(message: String) : Exception(message)
