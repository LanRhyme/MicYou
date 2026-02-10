package com.lanrhyme.micyou

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.core.app.ActivityCompat
import io.ktor.network.selector.*
import io.ktor.network.sockets.*
import io.ktor.utils.io.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.protobuf.*

import java.nio.ByteBuffer
import java.nio.ByteOrder

import android.content.Intent
import io.ktor.utils.io.jvm.javaio.toByteReadChannel
import io.ktor.utils.io.*
import kotlinx.coroutines.*
import java.io.OutputStream

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
        _lastError.value = null

        val jobToJoin = startStopMutex.withLock {
            val currentJob = job
            if (currentJob != null && !currentJob.isCompleted) {
                null
            } else {
                _state.value = StreamState.Connecting
                CoroutineScope(Dispatchers.IO).launch {
                    var socket: Socket? = null
                    var recorder: AudioRecord? = null
                    sendChannel = Channel(Channel.UNLIMITED)
                    
                    // Connection Abstractions
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

                        try {
                            recorder = try {
                                // 尝试使用 UNPROCESSED
                                AudioRecord(
                                    MediaRecorder.AudioSource.UNPROCESSED,
                                    androidSampleRate,
                                    androidChannelConfig,
                                    androidAudioFormat,
                                    minBufSize * 2
                                )
                            } catch (e: Exception) {
                                println("UNPROCESSED source failed, falling back to MIC: ${e.message}")
                                AudioRecord(
                                    MediaRecorder.AudioSource.MIC,
                                    androidSampleRate,
                                    androidChannelConfig,
                                    androidAudioFormat,
                                    minBufSize * 2
                                )
                            }
                        } catch (e: SecurityException) {
                            e.printStackTrace()
                            _state.value = StreamState.Error
                            _lastError.value = "录音权限不足"
                            return@launch
                        }
                        
                        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                            val msg = "AudioRecord 初始化失败"
                            println(msg)
                            _state.value = StreamState.Error
                            _lastError.value = msg
                            return@launch
                        }
                        
                        // 网络设置
                        val selectorManager = SelectorManager(Dispatchers.IO)
                        
                        if (mode == ConnectionMode.Bluetooth) {
                            val adapter = android.bluetooth.BluetoothAdapter.getDefaultAdapter()
                                ?: throw UnsupportedOperationException("设备不支持蓝牙")
                            
                            if (!android.bluetooth.BluetoothAdapter.checkBluetoothAddress(ip)) {
                                throw IllegalArgumentException("无效的蓝牙 MAC 地址: $ip")
                            }
                            
                            val device = adapter.getRemoteDevice(ip)
                            // SPP UUID
                            val uuid = java.util.UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
                            
                            // Note: Missing explicit permission check here, relying on SecurityException if missing
                            val btSocket = device.createRfcommSocketToServiceRecord(uuid)
                            btSocket.connect()
                            
                            input = btSocket.inputStream.toByteReadChannel()
                            output = btSocket.outputStream.toByteWriteChannel()
                            closeConnection = { btSocket.close() }
                            
                        } else {
                            val socketBuilder = aSocket(selectorManager)
                            val socket = socketBuilder.tcp().connect(ip, port)
                            input = socket.openReadChannel()
                            output = socket.openWriteChannel(autoFlush = true)
                            closeConnection = { socket.close() }
                        }

                        // Start Foreground Service
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

                        // 握手
                        output.writeFully(CHECK_1.encodeToByteArray())
                        output.flush()

                        val responseBuffer = ByteArray(CHECK_2.length)
                        input.readFully(responseBuffer, 0, responseBuffer.size)

                        if (!responseBuffer.decodeToString().equals(CHECK_2)) {
                            val msg = "握手失败"
                            println(msg)
                            _state.value = StreamState.Error
                            _lastError.value = msg
                            closeConnection()
                            return@launch
                        }

                        recorder.startRecording()
                        _state.value = StreamState.Streaming
                        _lastError.value = null

                        // --- Writer Loop (Send Channel -> Socket) ---
                        val writerJob = launch {
                            for (msg in sendChannel!!) {
                                try {
                                    val packetBytes = proto.encodeToByteArray(MessageWrapper.serializer(), msg)
                                    val length = packetBytes.size
                                    output.writeInt(PACKET_MAGIC)
                                    output.writeInt(length)
                                    output.writeFully(packetBytes)
                                    output.flush()
                                } catch (e: Exception) {
                                    println("Error writing to socket: ${e.message}")
                                    break
                                }
                            }
                        }

                        // --- Reader Loop (Socket -> Receive Channel/Action) ---
                        val readerJob = launch {
                            try {
                                while (isActive) {
                                    val magic = input.readInt()
                                    if (magic != PACKET_MAGIC) {
                                        println("Invalid Magic: ${magic.toString(16)}")
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
                                                println("Received Mute Command: ${wrapper.mute.isMuted}")
                                            }
                                        } catch (e: Exception) {
                                            println("Error decoding incoming message: ${e.message}")
                                        }
                                    }
                                }
                            } catch (e: Exception) {
                                if (isActive && _state.value == StreamState.Streaming) {
                                    println("Error reading from socket: ${e.message}")
                                    // Connection issue will be handled by main loop cancellation or detection
                                }
                            }
                        }
                        
                        // Send initial mute state
                        sendChannel?.send(MessageWrapper(mute = MuteMessage(_isMuted.value)))

                        val buffer = ByteArray(minBufSize)
                        val floatBuffer = if (androidAudioFormat == AudioFormat.ENCODING_PCM_FLOAT) FloatArray(minBufSize / 4) else null
                        
                        var sequenceNumber = 0

                        while (isActive) {
                            // Check if writer/reader are still alive
                            if (writerJob.isCancelled || writerJob.isCompleted) throw Exception("Writer job failed")
                            // Reader failure is less critical for sending, but indicates connection loss usually.

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
                        if (isActive) {
                            val msg = e.message ?: ""
                            val isDisconnect = msg.contains("broken pipe", ignoreCase = true) ||
                                              msg.contains("connection reset", ignoreCase = true) ||
                                              msg.contains("closed", ignoreCase = true) ||
                                              e is java.io.EOFException

                            if (!isDisconnect) {
                                e.printStackTrace()
                                _state.value = StreamState.Error
                                _lastError.value = "连接断开: ${e.message}"
                            } else {
                                println("Disconnected by remote: ${e.message}")
                            }
                        }
                    } finally {
                        try {
                            sendChannel?.close()
                            recorder?.stop()
                            recorder?.release()
                            closeConnection()
                            
                            // Stop Foreground Service
                            val context = ContextHelper.getContext()
                            if (context != null) {
                                val intent = Intent(context, AudioService::class.java).apply {
                                    action = AudioService.ACTION_STOP
                                }
                                context.startService(intent)
                            }
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                        _state.value = StreamState.Idle
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
        // If connected, send message
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
        // Android 端无需处理，功能仅存在于桌面端
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
