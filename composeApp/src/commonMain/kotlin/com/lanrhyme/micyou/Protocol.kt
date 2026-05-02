package com.lanrhyme.micyou

import kotlinx.serialization.Serializable
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.protobuf.ProtoNumber

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
    val audioFormat: Int
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || this::class != other::class) return false

        other as AudioPacketMessage

        if (!buffer.contentEquals(other.buffer)) return false
        if (sampleRate != other.sampleRate) return false
        if (channelCount != other.channelCount) return false
        if (audioFormat != other.audioFormat) return false

        return true
    }

    override fun hashCode(): Int {
        var result = buffer.contentHashCode()
        result = 31 * result + sampleRate
        result = 31 * result + channelCount
        result = 31 * result + audioFormat
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
    val timestamp: Long = 0
)

@Serializable
data class MuteMessage(
    @ProtoNumber(1)
    val isMuted: Boolean
)

@Serializable
class ConnectMessage

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class PluginInfoMessage(
    @ProtoNumber(1)
    val id: String,
    @ProtoNumber(2)
    val name: String,
    @ProtoNumber(3)
    val version: String
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class PluginSyncMessage(
    @ProtoNumber(1)
    val plugins: List<PluginInfoMessage> = emptyList(),
    @ProtoNumber(2)
    val platform: String = ""
)

@Serializable
data class PingMessage(
    @ProtoNumber(1)
    val timestamp: Long
)

@Serializable
data class PongMessage(
    @ProtoNumber(1)
    val timestamp: Long
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class LoopbackAudioMessage(
    @ProtoNumber(1)
    val buffer: ByteArray,
    @ProtoNumber(2)
    val sampleRate: Int,
    @ProtoNumber(3)
    val channelCount: Int,
    @ProtoNumber(4)
    val audioFormat: Int,
    @ProtoNumber(5)
    val timestamp: Long = 0
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || this::class != other::class) return false
        other as LoopbackAudioMessage
        if (!buffer.contentEquals(other.buffer)) return false
        if (sampleRate != other.sampleRate) return false
        if (channelCount != other.channelCount) return false
        if (audioFormat != other.audioFormat) return false
        if (timestamp != other.timestamp) return false
        return true
    }

    override fun hashCode(): Int {
        var result = buffer.contentHashCode()
        result = 31 * result + sampleRate
        result = 31 * result + channelCount
        result = 31 * result + audioFormat
        result = 31 * result + timestamp.hashCode()
        return result
    }
}

/** AEC 状态消息（客户端告知服务器是否需要回环参考音频） */
@Serializable
data class AecStateMessage(
    @ProtoNumber(1)
    val enabled: Boolean
)

const val PACKET_MAGIC = 0x4D696359 // "MicY" in ASCII
const val UDP_PACKET_MAGIC = 0x4D696355 // "MicU" in ASCII
/** UDP 回环音频魔数（PC -> Android） */
const val UDP_PACKET_MAGIC_LOOPBACK = 0x4D69634C // "MicL" in ASCII

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
    return connect != null || mute != null || pluginSync != null || ping != null || pong != null || loopbackAudio != null || aecState != null
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
    @ProtoNumber(4)
    val pluginSync: PluginSyncMessage? = null,
    @ProtoNumber(5)
    val ping: PingMessage? = null,
    @ProtoNumber(6)
    val pong: PongMessage? = null,
    @ProtoNumber(7)
    val loopbackAudio: LoopbackAudioMessage? = null,
    @ProtoNumber(8)
    val aecState: AecStateMessage? = null
)
