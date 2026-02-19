package com.lanrhyme.micyou.network

import com.lanrhyme.micyou.AudioPacketMessage
import com.lanrhyme.micyou.AudioPacketMessageOrdered
import com.lanrhyme.micyou.ConnectMessage
import com.lanrhyme.micyou.MessageWrapper
import com.lanrhyme.micyou.PACKET_MAGIC
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.readFully
import io.ktor.utils.io.writeFully
import io.ktor.utils.io.writeInt
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.encodeToByteArray
import kotlinx.serialization.protobuf.ProtoBuf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ConnectionHandlerIntegrationTest {
    @OptIn(ExperimentalSerializationApi::class)
    private val proto = ProtoBuf { }

    @Test
    fun acceptsAudioAfterValidToken() = runBlocking {
        val input = ByteChannel(autoFlush = true)
        val output = ByteChannel(autoFlush = true)
        val received = mutableListOf<AudioPacketMessage>()
        val errors = mutableListOf<String>()

        val handler = ConnectionHandler(
            input = input,
            output = output,
            onAudioPacketReceived = { received.add(it) },
            onMuteStateChanged = {},
            onError = { errors.add(it) },
            requiredTokenProvider = { "secret" }
        )

        val job = launch { handler.run() }

        input.writeFully("MicYouCheck1".encodeToByteArray())
        val handshake = ByteArray("MicYouCheck2".length)
        withTimeout(2000) { output.readFully(handshake, 0, handshake.size) }
        assertEquals("MicYouCheck2", handshake.decodeToString())

        writeWrapper(input, MessageWrapper(connect = ConnectMessage(protocolVersion = 2, token = "secret")))
        val audio = AudioPacketMessage(byteArrayOf(1, 2, 3, 4), 48000, 1, 2)
        writeWrapper(input, MessageWrapper(audioPacket = AudioPacketMessageOrdered(1, audio)))

        input.close()
        withTimeout(2000) { job.join() }

        assertEquals(1, received.size)
        assertTrue(errors.none { it.contains("Authentication failed", ignoreCase = true) })
    }

    @Test
    fun rejectsAudioWhenTokenIsInvalid() = runBlocking {
        val input = ByteChannel(autoFlush = true)
        val output = ByteChannel(autoFlush = true)
        val received = mutableListOf<AudioPacketMessage>()
        val errors = mutableListOf<String>()

        val handler = ConnectionHandler(
            input = input,
            output = output,
            onAudioPacketReceived = { received.add(it) },
            onMuteStateChanged = {},
            onError = { errors.add(it) },
            requiredTokenProvider = { "secret" }
        )

        val job = launch { handler.run() }

        input.writeFully("MicYouCheck1".encodeToByteArray())
        val handshake = ByteArray("MicYouCheck2".length)
        withTimeout(2000) { output.readFully(handshake, 0, handshake.size) }

        writeWrapper(input, MessageWrapper(connect = ConnectMessage(protocolVersion = 2, token = "wrong")))
        val audio = AudioPacketMessage(byteArrayOf(1, 2, 3, 4), 48000, 1, 2)
        writeWrapper(input, MessageWrapper(audioPacket = AudioPacketMessageOrdered(1, audio)))

        input.close()
        withTimeout(2000) { job.join() }

        assertEquals(0, received.size)
        assertTrue(errors.any { it.contains("Authentication failed", ignoreCase = true) })
    }

    @Test
    fun skipsOversizedPacketAndContinuesReading() = runBlocking {
        val input = ByteChannel(autoFlush = true)
        val output = ByteChannel(autoFlush = true)
        val received = mutableListOf<AudioPacketMessage>()

        val handler = ConnectionHandler(
            input = input,
            output = output,
            onAudioPacketReceived = { received.add(it) },
            onMuteStateChanged = {},
            onError = {}
        )

        val job = launch { handler.run() }

        input.writeFully("MicYouCheck1".encodeToByteArray())
        val handshake = ByteArray("MicYouCheck2".length)
        withTimeout(2000) { output.readFully(handshake, 0, handshake.size) }

        input.writeInt(PACKET_MAGIC)
        input.writeInt(3 * 1024 * 1024) // oversized frame length, should be discarded

        val audio = AudioPacketMessage(byteArrayOf(9, 8, 7, 6), 16000, 1, 2)
        writeWrapper(input, MessageWrapper(audioPacket = AudioPacketMessageOrdered(2, audio)))

        input.close()
        withTimeout(2000) { job.join() }

        assertEquals(1, received.size)
        assertTrue(received[0].buffer.contentEquals(byteArrayOf(9, 8, 7, 6)))
    }

    @OptIn(ExperimentalSerializationApi::class)
    private suspend fun writeWrapper(channel: ByteChannel, wrapper: MessageWrapper) {
        val bytes = proto.encodeToByteArray(MessageWrapper.serializer(), wrapper)
        channel.writeInt(PACKET_MAGIC)
        channel.writeInt(bytes.size)
        channel.writeFully(bytes)
    }
}
