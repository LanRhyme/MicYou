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
import java.io.EOFException
import java.io.IOException
import javax.sound.sampled.*
import kotlin.math.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import de.maxhenkel.rnnoise4j.Denoiser
import javax.bluetooth.*
import javax.microedition.io.*
import io.ktor.utils.io.jvm.javaio.*
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
    
    // Channel for outgoing messages (Control)
    private var sendChannel: Channel<MessageWrapper>? = null
    
    @Volatile
    private var isMonitoring = false
    
    // Config State
    @Volatile private var enableNS: Boolean = false
    @Volatile private var nsType: NoiseReductionType = NoiseReductionType.RNNoise
    @Volatile private var enableAGC: Boolean = false
    @Volatile private var agcTargetLevel: Int = 32000
    @Volatile private var enableVAD: Boolean = false
    @Volatile private var vadThreshold: Int = 10
    @Volatile private var enableDereverb: Boolean = false
    @Volatile private var dereverbLevel: Float = 0.5f
    @Volatile private var amplification: Float = 10.0f
    
    // Internal Audio Processing State
    private var agcEnvelope: Float = 0f
    
    // RNNoise instances
    private var denoiserLeft: Denoiser? = null
    private var denoiserRight: Denoiser? = null

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
        
        println("Config Updated: Amp=$amplification, VAD=$enableVAD ($vadThreshold), AGC=$enableAGC ($agcTargetLevel), NS=$enableNS ($nsType)")
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
        _lastError.value = null // 清除之前的错误
        val jobToJoin = startStopMutex.withLock {
            val currentJob = job
            if (currentJob != null && !currentJob.isCompleted) {
                null
            } else {
                _state.value = StreamState.Connecting
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        if (mode == ConnectionMode.Bluetooth) {
                            try {
                                val localDevice = LocalDevice.getLocalDevice()
                                localDevice.discoverable = DiscoveryAgent.GIAC
                                println("本机蓝牙名称: ${localDevice.friendlyName}, 地址: ${localDevice.bluetoothAddress}")
                                
                                val uuid = javax.bluetooth.UUID("0000110100001000800000805F9B34FB", false)
                                val url = "btspp://localhost:$uuid;name=MicYouServer"
                                
                                btNotifier = Connector.open(url) as StreamConnectionNotifier
                                println("蓝牙服务已启动: $url")
                                
                                while (isActive) {
                                    val connection = btNotifier?.acceptAndOpen() ?: break
                                    activeBtConnection = connection
                                    println("接受来自蓝牙的连接")
                                    _state.value = StreamState.Streaming
                                    _lastError.value = null
                                    
                                    try {
                                        val input = connection.openInputStream().toByteReadChannel()
                                        val output = connection.openOutputStream().toByteWriteChannel()
                                        handleConnection(input, output)
                                    } catch (e: Exception) {
                                        if (!isNormalDisconnect(e)) {
                                            e.printStackTrace()
                                            _lastError.value = "蓝牙连接错误: ${e.message}"
                                        }
                                    } finally {
                                        activeBtConnection = null
                                        connection.close()
                                        _state.value = StreamState.Connecting
                                    }
                                }
                            } catch (e: Exception) { // Bluetooth specific errors
                                if (isActive) {
                                    e.printStackTrace()
                                    _state.value = StreamState.Error
                                    _lastError.value = "蓝牙初始化失败: ${e.message}. 请确保电脑支持蓝牙且已开启。"
                                }
                            }
                        } else {
                            // TCP Logic
                            selectorManager = SelectorManager(Dispatchers.IO)
                            
                            try {
                                serverSocket = aSocket(selectorManager!!).tcp().bind(port = port)
                                val msg = "监听端口 $port"
                                println(msg)
                                
                                while (isActive) {
                                    val socket = serverSocket?.accept() ?: break
                                    activeSocket = socket
                                    println("接受来自 ${socket.remoteAddress} 的连接")
                                    _state.value = StreamState.Streaming
                                    _lastError.value = null
                                    
                                    try {
                                        val input = socket.openReadChannel()
                                        val output = socket.openWriteChannel(autoFlush = true)
                                        handleConnection(input, output)
                                    } catch (e: Exception) {
                                        if (!isNormalDisconnect(e)) {
                                            e.printStackTrace()
                                            _lastError.value = "连接处理错误: ${e.message}"
                                        }
                                    } finally {
                                        activeSocket = null
                                        socket.close()
                                        _state.value = StreamState.Connecting
                                    }
                                }
                            } catch (e: BindException) {
                                if (isActive) {
                                    val msg = "端口 $port 已被占用。请关闭其他 AndroidMic 实例。"
                                    println(msg)
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
                                e.printStackTrace()
                                _state.value = StreamState.Error
                                _lastError.value = "服务器错误: ${e.message}"
                            }
                        }
                    } finally {
                        serverSocket?.close()
                        serverSocket = null
                        btNotifier?.close()
                        btNotifier = null
                        selectorManager?.close()
                        selectorManager = null
                        if (_state.value != StreamState.Error) {
                            _state.value = StreamState.Idle
                        }
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
            println("握手失败: 收到 $check1String")
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
                                val audioFormat = javax.sound.sampled.AudioFormat(
                                    audioPacket.sampleRate.toFloat(),
                                    16,
                                    audioPacket.channelCount,
                                    true,
                                    false 
                                )

                                val info = DataLine.Info(SourceDataLine::class.java, audioFormat)

                                val mixers = AudioSystem.getMixerInfo()
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
                                } else {
                                    monitoringLine = AudioSystem.getLine(info) as SourceDataLine
                                    isUsingCable = false
                                }

                                monitoringLine?.open(audioFormat)
                                monitoringLine?.start()
                            }

                            val processedBuffer = processAudio(audioPacket.buffer, audioPacket.audioFormat, audioPacket.channelCount)

                            if (processedBuffer != null) {
                                if (!isUsingCable && !isMonitoring) {
                                    processedBuffer.fill(0.toByte())
                                }

                                monitoringLine?.write(processedBuffer, 0, processedBuffer.size)

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
            println("Failed to send mute message: ${e.message}")
        }
    }
    
    actual fun setMonitoring(enabled: Boolean) {
        isMonitoring = enabled
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
        // Convert to ShortArray for processing
        val shorts: ShortArray
        
        when (format) {
            4, 32 -> { // PCM_FLOAT (32-bit Float)
                shorts = ShortArray(buffer.size / 4)
                for (i in shorts.indices) {
                    val byteIndex = i * 4
                    // Little Endian
                    val bits = (buffer[byteIndex].toInt() and 0xFF) or
                               ((buffer[byteIndex + 1].toInt() and 0xFF) shl 8) or
                               ((buffer[byteIndex + 2].toInt() and 0xFF) shl 16) or
                               ((buffer[byteIndex + 3].toInt() and 0xFF) shl 24)
                    val sample = Float.fromBits(bits)
                    // Clamp and convert to 16-bit PCM
                    shorts[i] = (sample * 32767.0f).toInt().coerceIn(-32768, 32767).toShort()
                }
            }
            3, 8 -> { // PCM_8BIT (Unsigned 8-bit)
                 shorts = ShortArray(buffer.size)
                 for (i in shorts.indices) {
                     // 8-bit PCM is usually unsigned 0-255. 128 is 0.
                     val sample = (buffer[i].toInt() and 0xFF) - 128
                     shorts[i] = (sample * 256).toShort()
                 }
            }
            else -> { // PCM_16BIT (Default)
                shorts = ShortArray(buffer.size / 2)
                for (i in shorts.indices) {
                     val byteIndex = i * 2
                     val sample = (buffer[byteIndex].toInt() and 0xFF) or
                                  ((buffer[byteIndex + 1].toInt()) shl 8)
                     shorts[i] = sample.toShort()
                }
            }
        }
        
        // --- Audio Processing Chain ---
        var processedShorts = shorts
        
        // Amplification
        if (amplification != 1.0f) {
            for (i in processedShorts.indices) {
                 val sample = processedShorts[i].toInt()
                 val amplified = (sample * amplification).toInt()
                 processedShorts[i] = amplified.coerceIn(-32768, 32767).toShort()
            }
        }

        // Convert back to ByteArray
        val resultBuffer = ByteArray(processedShorts.size * 2)
        ByteBuffer.wrap(resultBuffer).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(processedShorts)
        return resultBuffer
    }

    private fun calculateRMS(buffer: ByteArray): Float {
        var sum = 0.0
        val shorts = ShortArray(buffer.size / 2)
        ByteBuffer.wrap(buffer).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(shorts)
        
        for (sample in shorts) {
            val normalized = sample / 32768.0
            sum += normalized * normalized
        }
        return if (shorts.isNotEmpty()) Math.sqrt(sum / shorts.size).toFloat() else 0f
    }
}
