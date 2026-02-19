package com.lanrhyme.micyou.network

import com.lanrhyme.micyou.ConnectMessage
import com.lanrhyme.micyou.MessageWrapper
import com.lanrhyme.micyou.PACKET_MAGIC
import com.lanrhyme.micyou.VideoFrameMessage
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

class VideoConnectionHandlerIntegrationTest {
    @OptIn(ExperimentalSerializationApi::class)
    private val proto = ProtoBuf { }

    @Test
    fun acceptsVideoAfterValidToken() = runBlocking {
        val input = ByteChannel(autoFlush = true)
        val output = ByteChannel(autoFlush = true)
        val received = mutableListOf<VideoFrameMessage>()
        val errors = mutableListOf<String>()

        val handler = VideoConnectionHandler(
            input = input,
            output = output,
            onVideoConfig = {},
            onVideoFrame = { received.add(it) },
            onRttMeasured = {},
            onError = { errors.add(it) },
            requiredTokenProvider = { "secret" }
        )

        val job = launch { handler.run() }

        input.writeFully("MicYouCheck1".encodeToByteArray())
        val handshake = ByteArray("MicYouCheck2".length)
        withTimeout(2000) { output.readFully(handshake, 0, handshake.size) }
        assertEquals("MicYouCheck2", handshake.decodeToString())

        writeWrapper(input, MessageWrapper(connect = ConnectMessage(protocolVersion = 2, token = "secret")))
        writeWrapper(
            input,
            MessageWrapper(
                videoFrame = VideoFrameMessage(
                    sequenceNumber = 1,
                    timestampMs = 100L,
                    width = 640,
                    height = 360,
                    jpegBytes = byteArrayOf(1, 2, 3)
                )
            )
        )

        input.close()
        withTimeout(2000) { job.join() }

        assertEquals(1, received.size)
        assertTrue(errors.none { it.contains("Authentication failed", ignoreCase = true) })
    }

    @OptIn(ExperimentalSerializationApi::class)
    private suspend fun writeWrapper(channel: ByteChannel, wrapper: MessageWrapper) {
        val bytes = proto.encodeToByteArray(MessageWrapper.serializer(), wrapper)
        channel.writeInt(PACKET_MAGIC)
        channel.writeInt(bytes.size)
        channel.writeFully(bytes)
    }
}
