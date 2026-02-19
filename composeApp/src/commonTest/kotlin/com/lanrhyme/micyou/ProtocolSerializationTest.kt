package com.lanrhyme.micyou

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray
import kotlinx.serialization.protobuf.ProtoBuf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ProtocolSerializationTest {
    @OptIn(ExperimentalSerializationApi::class)
    private val proto = ProtoBuf { }

    @Test
    fun serializesConnectMessageWithToken() {
        val wrapper = MessageWrapper(
            connect = ConnectMessage(
                protocolVersion = PROTOCOL_VERSION,
                token = "token123"
            )
        )

        val bytes = proto.encodeToByteArray(MessageWrapper.serializer(), wrapper)
        val decoded = proto.decodeFromByteArray(MessageWrapper.serializer(), bytes)

        assertEquals(PROTOCOL_VERSION, decoded.connect?.protocolVersion)
        assertEquals("token123", decoded.connect?.token)
    }

    @Test
    fun serializesAudioPacketWrapper() {
        val packet = AudioPacketMessage(
            buffer = byteArrayOf(1, 2, 3, 4),
            sampleRate = 48000,
            channelCount = 1,
            audioFormat = 2
        )
        val wrapper = MessageWrapper(
            audioPacket = AudioPacketMessageOrdered(sequenceNumber = 7, audioPacket = packet)
        )

        val bytes = proto.encodeToByteArray(MessageWrapper.serializer(), wrapper)
        val decoded = proto.decodeFromByteArray(MessageWrapper.serializer(), bytes)

        val decodedPacket = decoded.audioPacket?.audioPacket
        assertNotNull(decodedPacket)
        assertEquals(48000, decodedPacket.sampleRate)
        assertEquals(1, decodedPacket.channelCount)
        assertEquals(2, decodedPacket.audioFormat)
        assertTrue(decodedPacket.buffer.contentEquals(byteArrayOf(1, 2, 3, 4)))
    }

    @Test
    fun serializesVideoMessages() {
        val wrapper = MessageWrapper(
            videoConfig = VideoConfigMessage(
                width = 1920,
                height = 1080,
                fps = 30,
                jpegQuality = 85,
                rotation = 0,
                cameraFacing = 0
            ),
            videoFrame = VideoFrameMessage(
                sequenceNumber = 12,
                timestampMs = 1000L,
                width = 1920,
                height = 1080,
                jpegBytes = byteArrayOf(9, 8, 7)
            ),
            videoControl = VideoControlMessage(switchCamera = true)
        )

        val bytes = proto.encodeToByteArray(MessageWrapper.serializer(), wrapper)
        val decoded = proto.decodeFromByteArray(MessageWrapper.serializer(), bytes)

        assertEquals(1920, decoded.videoConfig?.width)
        assertEquals(30, decoded.videoConfig?.fps)
        assertEquals(12, decoded.videoFrame?.sequenceNumber)
        assertTrue(decoded.videoFrame?.jpegBytes?.contentEquals(byteArrayOf(9, 8, 7)) == true)
        assertEquals(true, decoded.videoControl?.switchCamera)
    }
}
