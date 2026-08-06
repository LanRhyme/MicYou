package com.lanrhyme.micyou.network

import kotlinx.serialization.Serializable
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.protobuf.ProtoNumber
import com.lanrhyme.micyou.audio.AudioFormat
import com.lanrhyme.micyou.audio.ChannelCount
import com.lanrhyme.micyou.audio.SampleRate
import com.lanrhyme.micyou.network.AudioPacketMessage
import com.lanrhyme.micyou.network.AudioPacketMessageOrdered
import com.lanrhyme.micyou.network.calculateUdpPort
import com.lanrhyme.micyou.network.MessageWrapper
import com.lanrhyme.micyou.network.PACKET_MAGIC
import com.lanrhyme.micyou.network.UDP_PACKET_MAGIC
import com.lanrhyme.micyou.network.UDP_PORT_OFFSET

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class AudioPacketMessage(
    @ProtoNumber(1)
    val buffer: ByteArray,
    @ProtoNumber(2)
    val sampleRate: Int,
    @ProtoNumber(3)
    val channelCount: Int,
    @ProtoNumber(4)
    val audioFormat: Int,
    // 0 = PCM（旧版默认），1 = Opus。Opus 载荷为压缩音频，
    // audioFormat 仍描述采集格式仅供遥测，解码不依赖它。
    @ProtoNumber(5)
    val codec: Int = 0
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || this::class != other::class) return false

        other as AudioPacketMessage

        if (!buffer.contentEquals(other.buffer)) return false
        if (sampleRate != other.sampleRate) return false
        if (channelCount != other.channelCount) return false
        if (audioFormat != other.audioFormat) return false
        if (codec != other.codec) return false

        return true
    }

    override fun hashCode(): Int {
        var result = buffer.contentHashCode()
        result = 31 * result + sampleRate
        result = 31 * result + channelCount
        result = 31 * result + audioFormat
        result = 31 * result + codec
        return result
    }
}

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class AudioPacketMessageOrdered(
    @ProtoNumber(1)
    val sequenceNumber: Int,
    @ProtoNumber(2)
    val audioPacket: AudioPacketMessage,
    @ProtoNumber(3)
    val timestamp: Long = 0,
    @ProtoNumber(4)
    val fecBuffer: ByteArray? = null,
    @ProtoNumber(5)
    val fecSequenceNumber: Int = -1,
    @ProtoNumber(6)
    val sessionId: Long = 0,
    // 原始源包长度；空列表表示旧版发送端，接收端保持旧版恢复行为。
    @ProtoNumber(7)
    val fecPacketLengths: List<Int> = emptyList()
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class MuteMessage(
    @ProtoNumber(1)
    val isMuted: Boolean
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class ConnectMessage(
    @ProtoNumber(1)
    val sessionId: Long = 0
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class PingMessage(
    @ProtoNumber(1)
    val timestamp: Long
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class PongMessage(
    @ProtoNumber(1)
    val timestamp: Long
)

const val PACKET_MAGIC = 0x4D696359 // "MicY" in ASCII
const val UDP_PACKET_MAGIC = 0x4D696355 // "MicU" in ASCII
const val UDP_CUSTOM_HEADER_SIZE = 8
const val UDP_MAX_DATAGRAM_SIZE = 1472
// 为自定义头、嵌套 protobuf、64 位字段及 FEC 长度元数据预留最坏情况预算。
const val UDP_PCM_PAYLOAD_SIZE = 1320

/** 音频 buffer 编码：0 = PCM（旧版默认），1 = Opus */
const val CODEC_PCM = 0
const val CODEC_OPUS = 1

/** UDP 端口 = TCP 端口 + 1 */
const val UDP_PORT_OFFSET = 1

/**
 * 计算 UDP 端口，带边界校验防止端口溢出。
 * @param tcpPort TCP 端口号
 * @return UDP 端口号
 * @throws IllegalArgumentException 当计算结果超出有效端口范围 (0-65535)
 */
fun calculateUdpPort(tcpPort: Int): Int {
    val udpPort = tcpPort + UDP_PORT_OFFSET
    if (udpPort !in 0..65535) {
        throw IllegalArgumentException("UDP 端口溢出: TCP 端口 $tcpPort + 偏移量 $UDP_PORT_OFFSET = $udpPort，超出有效范围 0-65535")
    }
    return udpPort
}

/** 判断 MessageWrapper 是否包含控制消息（应通过 TCP 发送） */
fun MessageWrapper.hasControlMessage(): Boolean {
    return connect != null || mute != null || ping != null || pong != null
}

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class MessageWrapper(
    @ProtoNumber(1)
    val audioPacket: AudioPacketMessageOrdered? = null,
    @ProtoNumber(2)
    val connect: ConnectMessage? = null,
    @ProtoNumber(3)
    val mute: MuteMessage? = null,
    @ProtoNumber(5)
    val ping: PingMessage? = null,
    @ProtoNumber(6)
    val pong: PongMessage? = null
)
