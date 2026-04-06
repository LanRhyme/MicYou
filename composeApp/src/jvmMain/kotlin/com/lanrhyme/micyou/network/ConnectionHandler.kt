package com.lanrhyme.micyou.network

import com.lanrhyme.micyou.*
import io.ktor.utils.io.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray
import kotlinx.serialization.protobuf.ProtoBuf
import java.io.EOFException
import java.io.IOException

/**
 * 处理单个活动网络连接（TCP 或蓝牙）。
 * 职责包括：
 * 1. 握手 (Check1/Check2)
 * 2. 接收和解析数据包（协议循环）
 * 3. 发送控制消息
 * 4. 将接收到的音频包分发给监听器
 */
class ConnectionHandler(
    private val input: ByteReadChannel,
    private val output: ByteWriteChannel,
    private val onAudioPacketReceived: suspend (AudioPacketMessage) -> Unit,
    private val onMuteStateChanged: (Boolean) -> Unit,
    private val onPluginSyncReceived: ((PluginSyncMessage) -> Unit)? = null,
    private val onError: (String) -> Unit
) {
    private val CHECK_1 = "MicYouCheck1"
    private val CHECK_2 = "MicYouCheck2"
    
    @OptIn(ExperimentalSerializationApi::class)
    private val proto = ProtoBuf { }
    
    private var sendChannel: Channel<MessageWrapper>? = null
    private var writerJob: Job? = null

    /**
     * 启动连接处理循环。
     * 此函数会挂起，直到连接关闭或发生错误。
     */
    suspend fun run() {
        try {
            // 1. 握手
            if (!performHandshake()) {
                onError("握手失败：与目标设备的握手验证失败。这可能是由于版本不匹配或协议错误。请确保两端使用相同版本的 MicYou。")
                return
            }

            // 2. 设置发送通道（限制容量防止内存无限增长）
            // 使用固定容量 + DROP_OLDEST 策略，避免消息队列无限增长导致内存溢出
            sendChannel = Channel(64)
            
            coroutineScope {
                // 启动写入任务
                writerJob = launch(Dispatchers.IO) {
                    processSendQueue()
                }

                // 3. 启动读取循环
                try {
                    processReceiveLoop()
                } finally {
                    writerJob?.cancel()
                }
            }
        } catch (e: Exception) {
            if (!isNormalDisconnect(e)) {
                val errorMsg = when {
                    e is EOFException -> "连接意外关闭：远程设备断开了连接。"
                    e is ClosedReceiveChannelException -> "连接通道已关闭。"
                    e is IOException -> when {
                        e.message?.contains("Connection reset", ignoreCase = true) == true -> 
                            "连接被重置：远程设备强制关闭了连接。"
                        e.message?.contains("Broken pipe", ignoreCase = true) == true -> 
                            "管道中断：无法向已关闭的连接发送数据。"
                        e.message?.contains("Socket closed", ignoreCase = true) == true -> 
                            "套接字已关闭。"
                        else -> "连接错误: ${e.message}"
                    }
                    else -> "连接错误: ${e.message}"
                }
                Logger.e("ConnectionHandler", errorMsg, e)
                onError(errorMsg)
            }
        } finally {
            cleanup()
        }
    }

    private suspend fun performHandshake(): Boolean {
        try {
            val check1Packet = input.readPacket(CHECK_1.length)
            val check1String = check1Packet.readText()
            
            if (check1String != CHECK_1) {
                Logger.e("ConnectionHandler", "握手失败: 收到 $check1String")
                return false
            }

            output.writeFully(CHECK_2.encodeToByteArray())
            output.flush()
            return true
        } catch (e: EOFException) {
            Logger.e("ConnectionHandler", "握手失败：连接提前关闭", e)
            return false
        } catch (e: IOException) {
            Logger.e("ConnectionHandler", "握手 IO 错误: ${e.message}", e)
            return false
        } catch (e: Exception) {
            Logger.e("ConnectionHandler", "握手未知错误", e)
            return false
        }
    }

    private suspend fun processSendQueue() {
        val channel = sendChannel ?: return
        for (msg in channel) {
            try {
                @OptIn(ExperimentalSerializationApi::class)
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

    @OptIn(ExperimentalSerializationApi::class)
    private suspend fun processReceiveLoop() {
        while (currentCoroutineContext().isActive) {
            val magic = input.readInt()
            if (magic != PACKET_MAGIC) {
                // Resync
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

            if (length > 2 * 1024 * 1024) { // 2MB limit
                continue
            }

            if (length <= 0) continue

            val packetBytes = ByteArray(length)
            input.readFully(packetBytes)

            try {
                val wrapper: MessageWrapper = proto.decodeFromByteArray(MessageWrapper.serializer(), packetBytes)

                if (wrapper.mute != null) {
                    onMuteStateChanged(wrapper.mute.isMuted)
                }

                val audioPacket = wrapper.audioPacket?.audioPacket
                if (audioPacket != null) {
                    onAudioPacketReceived(audioPacket)
                }
                
                if (wrapper.pluginSync != null && onPluginSyncReceived != null) {
                    onPluginSyncReceived(wrapper.pluginSync)
                }
            } catch (e: Exception) {
                Logger.e("ConnectionHandler", "Failed to decode packet", e)
            }
        }
    }

    suspend fun sendMuteState(muted: Boolean) {
        try {
            sendChannel?.send(MessageWrapper(mute = MuteMessage(muted)))
        } catch (e: Exception) {
            Logger.e("ConnectionHandler", "Failed to send mute message", e)
        }
    }

    suspend fun sendPluginSync(plugins: List<PluginInfoMessage>, platform: String) {
        try {
            sendChannel?.send(MessageWrapper(pluginSync = PluginSyncMessage(plugins, platform)))
        } catch (e: Exception) {
            Logger.e("ConnectionHandler", "Failed to send plugin sync message", e)
        }
    }

    private fun cleanup() {
        writerJob?.cancel()
        sendChannel?.close()
        sendChannel = null
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
}
