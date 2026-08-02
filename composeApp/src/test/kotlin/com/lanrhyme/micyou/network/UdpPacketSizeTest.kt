package com.lanrhyme.micyou.network

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.protobuf.ProtoBuf
import kotlin.test.Test
import kotlin.test.assertTrue

class UdpPacketSizeTest {
    @OptIn(ExperimentalSerializationApi::class)
    @Test
    fun worstCaseFecPacketFitsDatagramBudget() {
        val sourceLengths = List(12) { UDP_PCM_PAYLOAD_SIZE }
        val wrapper = MessageWrapper(
            audioPacket = AudioPacketMessageOrdered(
                sequenceNumber = Int.MAX_VALUE,
                audioPacket = AudioPacketMessage(
                    buffer = ByteArray(UDP_PCM_PAYLOAD_SIZE) { 0x7f },
                    sampleRate = Int.MAX_VALUE,
                    channelCount = Int.MAX_VALUE,
                    audioFormat = Int.MAX_VALUE
                ),
                timestamp = Long.MAX_VALUE,
                fecBuffer = byteArrayOf(1),
                fecSequenceNumber = Int.MAX_VALUE,
                sessionId = Long.MAX_VALUE,
                fecPacketLengths = sourceLengths
            )
        )

        val encoded = ProtoBuf.encodeToByteArray(MessageWrapper.serializer(), wrapper)
        assertTrue(
            UDP_CUSTOM_HEADER_SIZE + encoded.size <= UDP_MAX_DATAGRAM_SIZE,
            "serialized UDP datagram was ${UDP_CUSTOM_HEADER_SIZE + encoded.size} bytes"
        )
    }
}
