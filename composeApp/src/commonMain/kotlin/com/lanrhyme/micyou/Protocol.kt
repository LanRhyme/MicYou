package com.lanrhyme.micyou

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
@OptIn(ExperimentalSerializationApi::class)
data class MuteMessage(
    @ProtoNumber(1) val isMuted: Boolean
)

@Serializable
@OptIn(ExperimentalSerializationApi::class)
data class ConnectMessage(
    @ProtoNumber(1) val protocolVersion: Int? = null,
    @ProtoNumber(2) val token: String? = null
)

@Serializable
@OptIn(ExperimentalSerializationApi::class)
data class VideoConfigMessage(
    @ProtoNumber(1) val width: Int,
    @ProtoNumber(2) val height: Int,
    @ProtoNumber(3) val fps: Int,
    @ProtoNumber(4) val jpegQuality: Int,
    @ProtoNumber(5) val rotation: Int = 0,
    @ProtoNumber(6) val cameraFacing: Int = 0
)

@Serializable
@OptIn(ExperimentalSerializationApi::class)
data class VideoFrameMessage(
    @ProtoNumber(1) val sequenceNumber: Int,
    @ProtoNumber(2) val timestampMs: Long,
    @ProtoNumber(3) val width: Int,
    @ProtoNumber(4) val height: Int,
    @ProtoNumber(5) val jpegBytes: ByteArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is VideoFrameMessage) return false
        return sequenceNumber == other.sequenceNumber &&
            timestampMs == other.timestampMs &&
            width == other.width &&
            height == other.height &&
            jpegBytes.contentEquals(other.jpegBytes)
    }

    override fun hashCode(): Int {
        var result = sequenceNumber
        result = 31 * result + timestampMs.hashCode()
        result = 31 * result + width
        result = 31 * result + height
        result = 31 * result + jpegBytes.contentHashCode()
        return result
    }
}

@Serializable
@OptIn(ExperimentalSerializationApi::class)
data class VideoControlMessage(
    @ProtoNumber(1) val start: Boolean = false,
    @ProtoNumber(2) val stop: Boolean = false,
    @ProtoNumber(3) val switchCamera: Boolean = false,
    @ProtoNumber(4) val pingId: Int? = null,
    @ProtoNumber(5) val pongId: Int? = null
)

const val PACKET_MAGIC = 0x4D696359 // "MicY" in ASCII
const val PROTOCOL_VERSION = 2

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class MessageWrapper(
    @ProtoNumber(1) val audioPacket: AudioPacketMessageOrdered? = null,
    @ProtoNumber(2) val connect: ConnectMessage? = null,
    @ProtoNumber(3) val mute: MuteMessage? = null,
    @ProtoNumber(4) val videoConfig: VideoConfigMessage? = null,
    @ProtoNumber(5) val videoFrame: VideoFrameMessage? = null,
    @ProtoNumber(6) val videoControl: VideoControlMessage? = null
)
