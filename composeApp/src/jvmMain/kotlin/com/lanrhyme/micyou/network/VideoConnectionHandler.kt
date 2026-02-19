package com.lanrhyme.micyou.network

import com.lanrhyme.micyou.ConnectMessage
import com.lanrhyme.micyou.Logger
import com.lanrhyme.micyou.MessageWrapper
import com.lanrhyme.micyou.PACKET_MAGIC
import com.lanrhyme.micyou.VideoConfigMessage
import com.lanrhyme.micyou.VideoControlMessage
import com.lanrhyme.micyou.VideoFrameMessage
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.readByte
import io.ktor.utils.io.readFully
import io.ktor.utils.io.readInt
import io.ktor.utils.io.readPacket
import io.ktor.utils.io.readText
import io.ktor.utils.io.writeFully
import io.ktor.utils.io.writeInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray
import kotlinx.serialization.protobuf.ProtoBuf
import java.io.EOFException
import java.io.IOException

class VideoConnectionHandler(
    private val input: ByteReadChannel,
    private val output: ByteWriteChannel,
    private val onVideoConfig: (VideoConfigMessage) -> Unit,
    private val onVideoFrame: (VideoFrameMessage) -> Unit,
    private val onRttMeasured: (Long) -> Unit,
    private val onError: (String) -> Unit,
    private val requiredTokenProvider: () -> String = { "" }
) {
    private val check1 = "MicYouCheck1"
    private val check2 = "MicYouCheck2"

    @OptIn(ExperimentalSerializationApi::class)
    private val proto = ProtoBuf { }

    private var authenticated: Boolean = true
    private val writeMutex = Mutex()
    private var nextPingId = 1
    private val pendingRtt = mutableMapOf<Int, Long>()

    suspend fun run() {
        try {
            if (!performHandshake()) {
                onError("Handshake failed")
                return
            }
            authenticated = requiredTokenProvider().isBlank()
            processReceiveLoop()
        } catch (e: Exception) {
            if (!isNormalDisconnect(e)) {
                Logger.e("VideoConnectionHandler", "Connection error", e)
                onError("Video connection error: ${e.message}")
            }
        }
    }

    private suspend fun performHandshake(): Boolean {
        return try {
            val packet = input.readPacket(check1.length)
            if (packet.readText() != check1) {
                false
            } else {
                output.writeFully(check2.encodeToByteArray())
                output.flush()
                true
            }
        } catch (e: Exception) {
            Logger.e("VideoConnectionHandler", "Handshake IO error", e)
            false
        }
    }

    @OptIn(ExperimentalSerializationApi::class)
    private suspend fun processReceiveLoop() {
        while (currentCoroutineContext().isActive) {
            val magic = input.readInt()
            if (magic != PACKET_MAGIC) {
                var resyncMagic = magic
                while (currentCoroutineContext().isActive) {
                    val byte = input.readByte().toInt() and 0xFF
                    resyncMagic = (resyncMagic shl 8) or byte
                    if (resyncMagic == PACKET_MAGIC) break
                }
            }

            val length = input.readInt()
            if (length <= 0 || length > 8 * 1024 * 1024) continue

            val packetBytes = ByteArray(length)
            input.readFully(packetBytes)

            try {
                val wrapper: MessageWrapper = proto.decodeFromByteArray(MessageWrapper.serializer(), packetBytes)
                wrapper.connect?.let {
                    handleConnectMessage(it)
                    return@let
                }

                if (!authenticated) {
                    onError("Authentication required: missing connect message with valid token.")
                    throw SecurityException("Authentication required")
                }

                wrapper.videoControl?.let { control ->
                    handleVideoControl(control)
                }
                wrapper.videoConfig?.let(onVideoConfig)
                wrapper.videoFrame?.let(onVideoFrame)
            } catch (e: SecurityException) {
                throw e
            } catch (e: Exception) {
                Logger.e("VideoConnectionHandler", "Failed to decode packet", e)
            }
        }
    }

    private fun handleConnectMessage(connect: ConnectMessage) {
        val requiredToken = requiredTokenProvider().trim()
        if (requiredToken.isBlank()) {
            authenticated = true
            return
        }
        val remoteToken = connect.token?.trim().orEmpty()
        if (remoteToken == requiredToken) {
            authenticated = true
            return
        }
        authenticated = false
        onError("Authentication failed: token mismatch.")
        throw SecurityException("Token mismatch")
    }

    suspend fun sendRttPing(): Boolean {
        if (!authenticated) return false
        val id = nextPingId++
        pendingRtt[id] = System.currentTimeMillis()
        val wrapper = MessageWrapper(videoControl = VideoControlMessage(pingId = id))
        return runCatching { writeWrapper(wrapper) }.isSuccess
    }

    private suspend fun handleVideoControl(control: VideoControlMessage) {
        control.pingId?.let { pingId ->
            writeWrapper(MessageWrapper(videoControl = VideoControlMessage(pongId = pingId)))
        }
        control.pongId?.let { pongId ->
            val sentAt = pendingRtt.remove(pongId)
            if (sentAt != null) {
                onRttMeasured((System.currentTimeMillis() - sentAt).coerceAtLeast(0L))
            }
        }
    }

    @OptIn(ExperimentalSerializationApi::class)
    private suspend fun writeWrapper(wrapper: MessageWrapper) {
        val packetBytes = proto.encodeToByteArray(MessageWrapper.serializer(), wrapper)
        writeMutex.withLock {
            output.writeInt(PACKET_MAGIC)
            output.writeInt(packetBytes.size)
            output.writeFully(packetBytes)
            output.flush()
        }
    }

    private fun isNormalDisconnect(e: Throwable): Boolean {
        if (e is CancellationException) return true
        if (e is EOFException) return true
        if (e is IOException) {
            val msg = e.message ?: ""
            if (msg.contains("Socket closed", ignoreCase = true)) return true
            if (msg.contains("Connection reset", ignoreCase = true)) return true
            if (msg.contains("Broken pipe", ignoreCase = true)) return true
        }
        return false
    }
}
