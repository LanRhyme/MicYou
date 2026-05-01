package com.lanrhyme.micyou.network

import com.lanrhyme.micyou.*
import io.ktor.utils.io.*
import micyou.composeapp.generated.resources.Res
import micyou.composeapp.generated.resources.connectionChannelClosed
import micyou.composeapp.generated.resources.connectionDisconnected
import micyou.composeapp.generated.resources.connectionError
import micyou.composeapp.generated.resources.connectionPipeBroken
import micyou.composeapp.generated.resources.connectionReset
import micyou.composeapp.generated.resources.connectionSocketClosed
import micyou.composeapp.generated.resources.errorHandshakeFailedDetailed
import org.jetbrains.compose.resources.getString
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray
import kotlinx.serialization.protobuf.ProtoBuf
import java.io.EOFException
import java.io.IOException

/**
 * Handles a single active network connection (TCP or Bluetooth).
 * Responsibilities include:
 * 1. Handshake (Check1/Check2)
 * 2. Receiving and parsing packets (protocol loop)
 * 3. Sending control messages
 * 4. Dispatching received audio packets to listeners
 */
class ConnectionHandler(
    private val input: ByteReadChannel,
    private val output: ByteWriteChannel,
    private val onAudioPacketReceived: suspend (AudioPacketMessage) -> Unit,
    private val onMuteStateChanged: (Boolean) -> Unit,
    private val onPluginSyncReceived: ((PluginSyncMessage) -> Unit)? = null,
    private val onError: (String) -> Unit
) {
    private val CHECK_1 = "MicYouCheck1"
    private val CHECK_2 = "MicYouCheck2"
    
    @OptIn(ExperimentalSerializationApi::class)
    private val proto = ProtoBuf { }
    
    private var sendChannel: Channel<MessageWrapper>? = null
    private var writerJob: Job? = null
    private var pingJob: Job? = null
    
    // Latency measurement
    @Volatile
    private var rtt = 0L

    /**
     * Starts the connection handling loop.
     * This function suspends until the connection is closed or an error occurs.
     */
    suspend fun run() {
        try {
            // 1. Handshake
            if (!performHandshake()) {
                onError(getString(Res.string.errorHandshakeFailedDetailed))
                return
            }

            // 2. Set up send channel (limit capacity to prevent memory growth)
            sendChannel = Channel(Constants.MESSAGE_CHANNEL_CAPACITY)
            
            coroutineScope {
                // Start writer task
                writerJob = launch(Dispatchers.IO) {
                    processSendQueue()
                }

                // Start Ping task (once per second)
                pingJob = launch {
                    while (isActive) {
                        sendPing()
                        delay(1000)
                    }
                }

                // 3. Start receive loop
                try {
                    processReceiveLoop()
                } finally {
                    writerJob?.cancel()
                    pingJob?.cancel()
                }
            }
        } catch (e: Exception) {
            if (!isNormalDisconnect(e)) {
                val errorMsg = when {
                    e is EOFException -> getString(Res.string.connectionDisconnected)
                    e is ClosedReceiveChannelException -> getString(Res.string.connectionChannelClosed)
                    e is IOException -> when {
                        e.message?.contains("Connection reset", ignoreCase = true) == true ->
                            getString(Res.string.connectionReset)
                        e.message?.contains("Broken pipe", ignoreCase = true) == true ->
                            getString(Res.string.connectionPipeBroken)
                        e.message?.contains("Socket closed", ignoreCase = true) == true ->
                            getString(Res.string.connectionSocketClosed)
                        else -> String.format(getString(Res.string.connectionError), e.message ?: "")
                    }
                    else -> String.format(getString(Res.string.connectionError), e.message ?: "")
                }
                Logger.e("ConnectionHandler", errorMsg, e)
                onError(errorMsg)
            }
        } finally {
            cleanup()
        }
    }

    private suspend fun performHandshake(): Boolean {
        try {
            val check1Packet = input.readPacket(CHECK_1.length)
    val check1String = check1Packet.readText()
            
            if (check1String != CHECK_1) {
                Logger.e("ConnectionHandler", "Handshake failed: received $check1String")
                return false
            }

            output.writeFully(CHECK_2.encodeToByteArray())
            output.flush()
            return true
        } catch (e: EOFException) {
            Logger.e("ConnectionHandler", "Handshake failed: connection closed early", e)
            return false
        } catch (e: IOException) {
            Logger.e("ConnectionHandler", "Handshake IO error: ${e.message}", e)
            return false
        } catch (e: Exception) {
            Logger.e("ConnectionHandler", "Handshake unknown error", e)
            return false
        }
    }

    private suspend fun processSendQueue() {
        val channel = sendChannel ?: return
        for (msg in channel) {
            try {
                @OptIn(ExperimentalSerializationApi::class)
    val packetBytes = proto.encodeToByteArray(MessageWrapper.serializer(), msg)
    val length = packetBytes.size
                output.writeInt(PACKET_MAGIC)
                output.writeInt(length)
                output.writeFully(packetBytes)
                output.flush()
            } catch (e: Exception) {
                break
            }
        }
    }

    @OptIn(ExperimentalSerializationApi::class)
    private suspend fun processReceiveLoop() {
        while (currentCoroutineContext().isActive) {
            val magic = input.readInt()
            if (magic != PACKET_MAGIC) {
                // Resync
                var resyncMagic = magic
                while (currentCoroutineContext().isActive) {
                    val byte = input.readByte().toInt() and 0xFF
                    resyncMagic = (resyncMagic shl 8) or byte
                    if (resyncMagic == PACKET_MAGIC) {
                        break
                    }
                }
            }
    val length = input.readInt()

            // Packet size validation
            if (length > Constants.MAX_PACKET_SIZE) {
                Logger.w("ConnectionHandler", "Packet size too large: $length bytes (max: ${Constants.MAX_PACKET_SIZE}), skipping packet")
                continue
            }

            if (length < 0) {
                Logger.w("ConnectionHandler", "Invalid negative packet length: $length, skipping packet")
                continue
            }

            if (length == 0) {
                Logger.d("ConnectionHandler", "Received empty packet, skipping")
                continue
            }
    val packetBytes = ByteArray(length)
            input.readFully(packetBytes)

            try {
                val wrapper: MessageWrapper = proto.decodeFromByteArray(MessageWrapper.serializer(), packetBytes)

                if (wrapper.mute != null) {
                    onMuteStateChanged(wrapper.mute.isMuted)
                }
    val audioPacket = wrapper.audioPacket?.audioPacket
                if (audioPacket != null) {
                    onAudioPacketReceived(audioPacket)
                }
                
                if (wrapper.pluginSync != null && onPluginSyncReceived != null) {
                    onPluginSyncReceived(wrapper.pluginSync)
                }

                if (wrapper.pong != null) {
                    val now = System.currentTimeMillis()
                    rtt = now - wrapper.pong.timestamp
                    Logger.d("ConnectionHandler", "RTT updated: $rtt ms")
                }
            } catch (e: Exception) {
                Logger.e("ConnectionHandler", "Failed to decode packet", e)
            }
        }
    }

    private suspend fun sendPing() {
        try {
            sendChannel?.send(MessageWrapper(ping = PingMessage(System.currentTimeMillis())))
        } catch (e: Exception) {
            // Ignore
        }
    }

    fun getRtt(): Long = rtt

    suspend fun sendMuteState(muted: Boolean) {
        try {
            sendChannel?.send(MessageWrapper(mute = MuteMessage(muted)))
        } catch (e: Exception) {
            Logger.e("ConnectionHandler", "Failed to send mute message", e)
        }
    }

    suspend fun sendPluginSync(plugins: List<PluginInfoMessage>, platform: String) {
        try {
            sendChannel?.send(MessageWrapper(pluginSync = PluginSyncMessage(plugins, platform)))
        } catch (e: Exception) {
            Logger.e("ConnectionHandler", "Failed to send plugin sync message", e)
        }
    }

    private fun cleanup() {
        writerJob?.cancel()
        sendChannel?.close()
        sendChannel = null
    }

    private fun isNormalDisconnect(e: Throwable): Boolean {
        if (e is kotlinx.coroutines.CancellationException) return true
        if (e is EOFException) return true
        if (e is ClosedReceiveChannelException) return true
        if (e is IOException) {
            val msg = e.message ?: ""
            if (msg.contains("Socket closed", ignoreCase = true)) return true
            if (msg.contains("Connection reset", ignoreCase = true)) return true
            if (msg.contains("Broken pipe", ignoreCase = true)) return true
        }
        return false
    }
}
