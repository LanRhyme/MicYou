package com.lanrhyme.micyou.network

import com.lanrhyme.micyou.*
import kotlinx.coroutines.*
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.protobuf.ProtoBuf
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap

/**
 * 处理 UDP 音频数据连接（桌面端服务器）。
 * 职责包括：
 * 1. 接收 UDP 音频数据包
 * 2. 解析并验证包格式
 * 3. 利用序列号检测丢包/乱序
 * 4. 将音频包分发给监听器
 * 
 * 与 ConnectionHandler 不同，UDP 处理器：
 * - 无需握手（UDP 无连接）
 * - 不发送控制消息（控制消息通过 TCP 通道）
 * - 容忍丢包（音频可容忍少量丢失）
 */
class UdpConnectionHandler(
    private val port: Int,
    private val onAudioPacketReceived: suspend (AudioPacketMessage) -> Unit,
    private val onError: (String) -> Unit
) {
    @OptIn(ExperimentalSerializationApi::class)
    private val proto = ProtoBuf { }

    private var udpSocket: DatagramSocket? = null
    private var handlerJob: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // 客户端地址映射（当前仅支持单客户端）
    @Volatile
    private var clientAddress: InetSocketAddress? = null

    // 序列号跟踪（用于丢包统计）
    @Volatile
    private var expectedSequenceNumber = 0
    @Volatile
    private var packetsReceived = 0
    @Volatile
    private var packetsLost = 0

    /**
     * 启动 UDP 接收循环。
     * 此函数非阻塞，在后台协程中运行。
     */
    fun start() {
        handlerJob?.takeIf { it.isActive }?.let {
            Logger.w("UdpConnectionHandler", "UDP 处理器已在运行")
            return
        }

        handlerJob = scope.launch {
            runUdpReceiver()
        }
    }

    /**
     * 停止 UDP 接收器。
     */
    suspend fun stop() {
        handlerJob?.cancel()
        withTimeoutOrNull(2000) {
            handlerJob?.join()
        }
        cleanup()
    }

    /**
     * 获取统计信息（用于调试/监控）
     */
    fun getStats(): UdpStats = UdpStats(
        packetsReceived = packetsReceived,
        packetsLost = packetsLost,
        clientAddress = clientAddress
    )

    private suspend fun runUdpReceiver() {
        try {
            udpSocket = DatagramSocket(port).also { socket ->
                // 设置接收缓冲区大小，适应音频流
                socket.receiveBufferSize = 256 * 1024 // 256KB
                Logger.i("UdpConnectionHandler", "UDP 接收器已启动，端口 $port")
            }

            val buffer = ByteArray(Constants.MAX_PACKET_SIZE)

            while (currentCoroutineContext().isActive) {
                val packet = DatagramPacket(buffer, buffer.size)
                try {
                    udpSocket?.receive(packet)
                } catch (e: java.net.SocketException) {
                    // Socket 关闭时会抛出此异常，正常退出
                    if (currentCoroutineContext().isActive) {
                        Logger.e("UdpConnectionHandler", "UDP 接收错误", e)
                    }
                    break
                }

                val senderAddress = InetSocketAddress(packet.address, packet.port)

                // 记录首个客户端地址
                if (clientAddress == null) {
                    clientAddress = senderAddress
                    Logger.i("UdpConnectionHandler", "UDP 客户端已连接: ${senderAddress.address.hostAddress}:${senderAddress.port}")
                }

                processUdpPacket(packet.data, packet.offset, packet.length)
            }
        } catch (e: Exception) {
            if (currentCoroutineContext().isActive) {
                Logger.e("UdpConnectionHandler", "UDP 接收器致命错误", e)
                onError("UDP 接收器错误: ${e.message}")
            }
        } finally {
            cleanup()
        }
    }

    @OptIn(ExperimentalSerializationApi::class)
    private suspend fun processUdpPacket(data: ByteArray, offset: Int, length: Int) {
        if (length < 8) {
            // 包太小，跳过
            return
        }

        // 解析魔数（4 字节大端）
        val magic = ((data[offset].toInt() and 0xFF) shl 24) or
                    ((data[offset + 1].toInt() and 0xFF) shl 16) or
                    ((data[offset + 2].toInt() and 0xFF) shl 8) or
                    (data[offset + 3].toInt() and 0xFF)

        if (magic != PACKET_MAGIC && magic != UDP_PACKET_MAGIC) {
            Logger.w("UdpConnectionHandler", "UDP 包魔数不匹配: 0x${magic.toString(16).uppercase()}")
            return
        }

        // 解析长度（4 字节大端）
        val payloadLength = ((data[offset + 4].toInt() and 0xFF) shl 24) or
                     ((data[offset + 5].toInt() and 0xFF) shl 16) or
                     ((data[offset + 6].toInt() and 0xFF) shl 8) or
                     (data[offset + 7].toInt() and 0xFF)

        if (payloadLength <= 0 || payloadLength > length - 8) {
            Logger.w("UdpConnectionHandler", "UDP 包长度无效: $payloadLength")
            return
        }

        val payloadStart = offset + 8

        try {
            val wrapper: MessageWrapper = proto.decodeFromByteArray(MessageWrapper.serializer(), data.copyOfRange(payloadStart, payloadStart + payloadLength))

            // UDP 通道仅处理音频包
            val audioPacket = wrapper.audioPacket?.audioPacket
            if (audioPacket != null) {
                // 序列号跟踪
                val seqNum = wrapper.audioPacket.sequenceNumber
                if (packetsReceived == 0) {
                    expectedSequenceNumber = seqNum
                } else {
                    val expected = (expectedSequenceNumber + 1) and 0xFFFFFFFF.toInt()
                    if (seqNum != expected) {
                        if (seqNum > expected) {
                            // 正常丢包：收到的序列号大于期望值
                            val lost = ((seqNum - expected) and 0xFFFFFFFF.toInt())
                            packetsLost += lost
                            Logger.d("UdpConnectionHandler", "UDP 丢包检测: 期望 $expected, 收到 $seqNum, 丢失 $lost 包")
                        } else {
                            // 乱序包：收到的序列号小于期望值，不计算丢包
                            Logger.d("UdpConnectionHandler", "UDP 乱序包: 期望 $expected, 收到 $seqNum (旧包)")
                        }
                    }
                    expectedSequenceNumber = seqNum
                }
                packetsReceived++

                onAudioPacketReceived(audioPacket)
            }
        } catch (e: Exception) {
            Logger.e("UdpConnectionHandler", "UDP 包解码失败", e)
        }
    }

    private fun cleanup() {
        try {
            udpSocket?.close()
        } catch (e: Exception) {
            Logger.w("UdpConnectionHandler", "关闭 UDP socket 时出错: ${e.message}")
        }
        udpSocket = null
        clientAddress = null
    }

    data class UdpStats(
        val packetsReceived: Int,
        val packetsLost: Int,
        val clientAddress: InetSocketAddress?
    ) {
        val lossRate: Double
            get() = if (packetsReceived + packetsLost > 0) {
                packetsLost.toDouble() / (packetsReceived + packetsLost) * 100.0
            } else 0.0
    }
}
