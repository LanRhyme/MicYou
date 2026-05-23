package com.lanrhyme.micyou.network

object IosProtocolConstants {
    const val MAGIC_HEADER = 0x694F5354  // "iOST" in ASCII (big-endian)

    const val TYPE_HELLO = 1
    const val TYPE_ACK = 2
    const val TYPE_KEEPALIVE = 3
    const val TYPE_DISCONNECT = 4
    const val TYPE_AUDIO_FRAME = 16

    const val HEADER_SIZE = 16
    const val ACK_PAYLOAD_SIZE = 9 // success(1) + udpPort(4) + msgLen(4)
}

data class IosPacketHeader(
    val magic: Int,
    val type: Int,
    val payloadLength: Int,
    val sequence: Int
)

data class IosHelloPayload(
    val deviceName: String,
    val deviceId: String,
    val sampleRate: Int,
    val channelCount: Int
)

class IosAudioFramePayload(
    val sequence: Int,
    val timestamp: Long,
    val sampleRate: Int,
    val channelCount: Int,
    val pcmData: ByteArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || other !is IosAudioFramePayload) return false
        return sequence == other.sequence &&
                timestamp == other.timestamp &&
                sampleRate == other.sampleRate &&
                channelCount == other.channelCount &&
                pcmData.contentEquals(other.pcmData)
    }

    override fun hashCode(): Int {
        var result = sequence
        result = 31 * result + timestamp.hashCode()
        result = 31 * result + sampleRate
        result = 31 * result + channelCount
        result = 31 * result + pcmData.contentHashCode()
        return result
    }
}
