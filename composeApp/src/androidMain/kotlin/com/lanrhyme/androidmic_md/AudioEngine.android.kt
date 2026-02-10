package com.lanrhyme.androidmic_md

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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.protobuf.*

actual class AudioEngine actual constructor() {
    private val _state = MutableStateFlow(StreamState.Idle)
    actual val streamState: Flow<StreamState> = _state
    private val _audioLevels = MutableStateFlow(0f)
    actual val audioLevels: Flow<Float> = _audioLevels
    private var job: Job? = null
    private val proto = ProtoBuf { }

    private val CHECK_1 = "AndroidMic1"
    private val CHECK_2 = "AndroidMic2"

    actual suspend fun start(ip: String, port: Int, mode: ConnectionMode, isClient: Boolean) {
        if (!isClient) return

        _state.value = StreamState.Connecting
        job = CoroutineScope(Dispatchers.IO).launch {
            var socket: Socket? = null
            var recorder: AudioRecord? = null
            
            try {
                // 音频设置
                val sampleRate = 44100
                val channelConfig = AudioFormat.CHANNEL_IN_MONO
                val audioFormat = AudioFormat.ENCODING_PCM_16BIT
                val minBufSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)

                recorder = try {
                    AudioRecord(
                        MediaRecorder.AudioSource.MIC,
                        sampleRate,
                        channelConfig,
                        audioFormat,
                        minBufSize * 2
                    )
                } catch (e: SecurityException) {
                    e.printStackTrace()
                    _state.value = StreamState.Error
                    return@launch
                }

                if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                    println("AudioRecord 初始化失败")
                    _state.value = StreamState.Error
                    return@launch
                }

                // 网络设置
                val selectorManager = SelectorManager(Dispatchers.IO)
                socket = aSocket(selectorManager).tcp().connect(ip, port)
                val output = socket.openWriteChannel(autoFlush = true)
                val input = socket.openReadChannel()

                // 握手
                output.writeFully(CHECK_1.encodeToByteArray())
                output.flush()

                val responseBuffer = ByteArray(CHECK_2.length)
                val read = input.readAvailable(responseBuffer)
                
                if (read != CHECK_2.length || !responseBuffer.decodeToString().equals(CHECK_2)) {
                    println("握手失败")
                    _state.value = StreamState.Error
                    socket.close()
                    return@launch
                }

                recorder.startRecording()
                _state.value = StreamState.Streaming

                val buffer = ByteArray(minBufSize)
                
                while (isActive) {
                    val readBytes = recorder.read(buffer, 0, buffer.size)
                    if (readBytes > 0) {
                        val audioData = buffer.copyOfRange(0, readBytes)
                        
                        // 创建数据包
                        val packet = AudioPacketMessage(
                            buffer = audioData,
                            sampleRate = sampleRate,
                            channelCount = 1,
                            audioFormat = 16 
                        )
                        
                        // 序列化
                        val packetBytes = proto.encodeToByteArray(AudioPacketMessage.serializer(), packet)
                        
                        // 写入长度 (大端序)
                        val length = packetBytes.size
                        output.writeByte((length shr 24).toByte())
                        output.writeByte((length shr 16).toByte())
                        output.writeByte((length shr 8).toByte())
                        output.writeByte(length.toByte())
                        
                        // 写入数据
                        output.writeFully(packetBytes)
                        
                        // 计算电平
                        val rms = calculateRMS(audioData)
                        _audioLevels.value = rms
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                _state.value = StreamState.Error
            } finally {
                try {
                    recorder?.stop()
                    recorder?.release()
                    socket?.close()
                } catch (e: Exception) {
                    e.printStackTrace()
                }
                _state.value = StreamState.Idle
            }
        }
        job?.join()
    }
    
    private fun calculateRMS(buffer: ByteArray): Float {
        var sum = 0.0
        for (i in 0 until buffer.size step 2) {
             if (i+1 >= buffer.size) break
             val sample = ((buffer[i+1].toInt() shl 8) or (buffer[i].toInt() and 0xFF)).toShort()
             sum += sample * sample
        }
        val mean = sum / (buffer.size / 2)
        val root = kotlin.math.sqrt(mean)
        return (root / 32768.0).toFloat().coerceIn(0f, 1f)
    }

    actual fun stop() {
        job?.cancel()
    }
}
