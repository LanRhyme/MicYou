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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.protobuf.ProtoBuf
import java.io.EOFException
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.coroutines.CoroutineContext

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
    }
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
    
    // Channel for outgoing messages (Audio + Control)
    private var sendChannel: Channel<MessageWrapper>? = null

    @Volatile
    private var enableStreamingNotification: Boolean = true

    @Volatile
    private var enableNS: Boolean = false
    @Volatile
    private var enableAGC: Boolean = false

    private var noiseSuppressor: NoiseSuppressor? = null
    private var automaticGainControl: AutomaticGainControl? = null
    
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

        val jobToJoin = startStopMutex.withLock {
            val currentJob = job
            if (currentJob != null && !currentJob.isCompleted) {
                Logger.w("AudioEngine", "AudioEngine already running, ignoring start request")
                null
            } else {
                _state.value = StreamState.Connecting
                CoroutineScope(Dispatchers.IO).launch {
                    var socket: Socket? = null
                    var recorder: AudioRecord? = null
                    sendChannel = Channel(capacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)
                    
                    // 连接抽象
                    var input: ByteReadChannel? = null
                    var output: ByteWriteChannel? = null
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

                        try {
                            val useProcessing = enableNS || enableAGC
                            // 如果启用了处理，优先使用 MIC，因为 UNPROCESSED 可能会绕过系统效果
                            val preferredSource = if (useProcessing) MediaRecorder.AudioSource.MIC else MediaRecorder.AudioSource.UNPROCESSED

                            Logger.d("AudioEngine", "Initializing AudioRecord with source $preferredSource")
                            recorder = try {
                                AudioRecord(
                                    preferredSource,
                                    androidSampleRate,
                                    androidChannelConfig,
                                    androidAudioFormat,
                                    minBufSize * 2
                                )
                            } catch (e: Exception) {
                                Logger.w("AudioEngine", "Preferred source $preferredSource failed, falling back to MIC: ${e.message}")
                                if (preferredSource != MediaRecorder.AudioSource.MIC) {
                                    AudioRecord(
                                        MediaRecorder.AudioSource.MIC,
                                        androidSampleRate,
                                        androidChannelConfig,
                                        androidAudioFormat,
                                        minBufSize * 2
                                    )
                                } else {
                                    throw e
                                }
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
                                                val (maxAttempts, baseDelayMs) = when (mode) {
                            ConnectionMode.Wifi -> 3 to 600L
                            ConnectionMode.Usb -> 4 to 400L
                            ConnectionMode.Bluetooth -> 3 to 1200L
                        }
                        var connected = false
                        var lastConnectError: Exception? = null

                        for (attempt in 1..maxAttempts) {
                            var selectorManager: SelectorManager? = null
                            try {
                                if (mode == ConnectionMode.Bluetooth) {
                                    Logger.i("AudioEngine", "Connecting via Bluetooth to $ip (attempt $attempt/$maxAttempts)")
                                    val adapter = android.bluetooth.BluetoothAdapter.getDefaultAdapter()
                                        ?: throw UnsupportedOperationException("Bluetooth not supported")

                                    if (!android.bluetooth.BluetoothAdapter.checkBluetoothAddress(ip)) {
                                        throw IllegalArgumentException("Invalid Bluetooth MAC address: $ip")
                                    }

                                    val device = adapter.getRemoteDevice(ip)
                                    val uuid = java.util.UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
                                    val btSocket = device.createRfcommSocketToServiceRecord(uuid)
                                    btSocket.connect()
                                    input = btSocket.inputStream.toByteReadChannel()
                                    output = btSocket.outputStream.toByteWriteChannel()
                                    closeConnection = { btSocket.close() }
                                } else {
                                    selectorManager = SelectorManager(Dispatchers.IO)
                                    val targetIp = if (mode == ConnectionMode.Usb) "127.0.0.1" else ip
                                    Logger.i("AudioEngine", "Connecting via TCP to $targetIp:$port (attempt $attempt/$maxAttempts)")
                                    val socket = aSocket(selectorManager).tcp().connect(targetIp, port) {
                                        keepAlive = true
                                        socketTimeout = 10000L
                                        noDelay = true
                                    }
                                    input = socket.openReadChannel()
                                    output = socket.openWriteChannel(autoFlush = true)
                                    closeConnection = {
                                        socket.close()
                                        selectorManager?.close()
                                    }
                                }

                                Logger.d("AudioEngine", "Starting handshake")
                                output!!.writeFully(CHECK_1.encodeToByteArray())
                                output!!.flush()

                                val responseBuffer = ByteArray(CHECK_2.length)
                                input!!.readFully(responseBuffer, 0, responseBuffer.size)
                                if (!responseBuffer.decodeToString().equals(CHECK_2)) {
                                    throw java.io.IOException("Handshake failed")
                                }

                                val connectToken = PlatformAdaptor.getAuthToken().ifBlank { null }
                                val connectWrapper = MessageWrapper(
                                    connect = ConnectMessage(
                                        protocolVersion = PROTOCOL_VERSION,
                                        token = connectToken
                                    )
                                )
                                val connectBytes = proto.encodeToByteArray(MessageWrapper.serializer(), connectWrapper)
                                output!!.writeInt(PACKET_MAGIC)
                                output!!.writeInt(connectBytes.size)
                                output!!.writeFully(connectBytes)
                                output!!.flush()

                                connected = true
                                if (attempt > 1) {
                                    Logger.i("AudioEngine", "Connection recovered after retry")
                                } else {
                                    Logger.i("AudioEngine", "Handshake successful")
                                }
                                break
                            } catch (e: Exception) {
                                lastConnectError = e
                                closeConnection()
                                selectorManager?.close()
                                if (attempt < maxAttempts) {
                                    val delayMs = baseDelayMs * (1L shl (attempt - 1))
                                    Logger.w("AudioEngine", "Connect attempt $attempt failed (${e.message}), retrying in ${delayMs}ms")
                                    kotlinx.coroutines.delay(delayMs)
                                }
                            }
                        }

                        if (!connected) {
                            throw lastConnectError ?: java.io.IOException("Connection failed")
                        }

                        recorder.startRecording()
                        _state.value = StreamState.Streaming
                        _lastError.value = null

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

                        val connectedInput = input ?: throw IllegalStateException("Input channel is not initialized")
                        val connectedOutput = output ?: throw IllegalStateException("Output channel is not initialized")

                        // --- Writer Loop (Send Channel -> Socket) ---
                        val writerJob = launch {
                            Logger.d("AudioEngine", "Writer loop started")
                            for (msg in sendChannel!!) {
                                try {
                                    val packetBytes = proto.encodeToByteArray(MessageWrapper.serializer(), msg)
                                    val length = packetBytes.size
                                    connectedOutput.writeInt(PACKET_MAGIC)
                                    connectedOutput.writeInt(length)
                                    connectedOutput.writeFully(packetBytes)
                                    connectedOutput.flush()
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
                                        connectedInput.readInt()
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
                                    
                                    val length = connectedInput.readInt()

                                    if (length > 0) {
                                        val packetBytes = ByteArray(length)
                                        connectedInput.readFully(packetBytes)
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
                                // 计算电平
                                val rms = calculateRMS(audioData, audioFormat)
                                _audioLevels.value = rms

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
                                    
                                    sendChannel?.send(wrapper)
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
                                    "连接被拒绝: 请确保电脑端已开启并处于“连接中”状态，且防火墙已放行 TCP $port 端口。"
                                e is java.net.SocketTimeoutException -> 
                                    "连接超时: 请检查网络连接或 IP 地址是否正确。"
                                e is java.net.NoRouteToHostException ->
                                    "无法到达主机: 请确保手机和电脑在同一个 Wi-Fi 网络下。"
                                else -> "连接断开: ${e.message}"
                            }
                            val modeHint = when (mode) {
                                ConnectionMode.Wifi -> "[Wi-Fi]"
                                ConnectionMode.Usb -> "[USB]"
                                ConnectionMode.Bluetooth -> "[Bluetooth]"
                            }
                            _lastError.value = "$modeHint $errorMsg"
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
        jobToJoin?.join()
    }
    
    actual fun stop() {
        job?.cancel()
        job = null
        _state.value = StreamState.Idle

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
        this.enableNS = enableNS
        this.enableAGC = enableAGC
        
        try {
            noiseSuppressor?.enabled = enableNS
            automaticGainControl?.enabled = enableAGC
        } catch (e: Exception) {
            println("Error updating audio effects: ${e.message}")
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
    
    private fun calculateRMS(buffer: ByteArray, format: com.lanrhyme.micyou.AudioFormat): Float {
        var sum = 0.0
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
                }
            }
            com.lanrhyme.micyou.AudioFormat.PCM_8BIT -> {
                sampleCount = buffer.size
                for (i in 0 until sampleCount) {
                    val sample = (buffer[i].toInt() and 0xFF) - 128
                    val normalized = sample / 128.0
                    sum += normalized * normalized
                }
            }
            else -> { // 16-bit
                sampleCount = buffer.size / 2
                for (i in 0 until sampleCount) {
                    val byteIndex = i * 2
                    val sample = (buffer[byteIndex].toInt() and 0xFF) or
                                 ((buffer[byteIndex + 1].toInt()) shl 8)
                    val normalized = sample / 32768.0
                    sum += normalized * normalized
                }
            }
        }
        return if (sampleCount > 0) Math.sqrt(sum / sampleCount).toFloat() else 0f
    }
}
