package com.lanrhyme.androidmic_md

import kotlinx.serialization.Serializable
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.protobuf.ProtoNumber

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class AudioPacketMessage(
    @ProtoNumber(1) val buffer: ByteArray,
    @ProtoNumber(2) val sampleRate: Int,
    @ProtoNumber(3) val channelCount: Int,
    @ProtoNumber(4) val audioFormat: Int
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
    @ProtoNumber(1) val sequenceNumber: Int,
    @ProtoNumber(2) val audioPacket: AudioPacketMessage
)

@Serializable
class ConnectMessage

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class MessageWrapper(
    @ProtoNumber(1) val audioPacket: AudioPacketMessageOrdered? = null,
    @ProtoNumber(2) val connect: ConnectMessage? = null
)
