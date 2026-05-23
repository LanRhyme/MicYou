package com.lanrhyme.micyou.network

import com.lanrhyme.micyou.AudioFormat
import com.lanrhyme.micyou.AudioPacketMessage
import com.lanrhyme.micyou.Logger
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.readFully
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.EOFException
import java.io.IOException

class IosConnectionHandler(
    private val input: ByteReadChannel,
    private val output: ByteWriteChannel,
    private val headerBytes: ByteArray,
    private val onAudioPacketReceived: suspend (AudioPacketMessage) -> Unit,
    private val onError: (String) -> Unit
) {
    private var keepAliveJob: Job? = null
    private var sequenceCounter = 0

    suspend fun run() {
        try {
            if (headerBytes.size < IosProtocolConstants.HEADER_SIZE) {
                onError("iOS protocol header too short: ${headerBytes.size} bytes")
                return
            }

            val header = parseHeader(headerBytes)
            if (header.type != IosProtocolConstants.TYPE_HELLO) {
                onError("iOS protocol: expected Hello (type=1), got type=${header.type}")
                return
            }

            if (header.payloadLength <= 0) {
                onError("iOS protocol: Hello payload is empty")
                return
            }

            val payloadBytes = ByteArray(header.payloadLength)
            input.readFully(payloadBytes)

            val hello = parseHelloPayload(payloadBytes)
            Logger.i("IosConnectionHandler", "iOS client connected: ${hello.deviceName} (${hello.deviceId}), sampleRate=${hello.sampleRate}, channels=${hello.channelCount}")

            sendAck()

            coroutineScope {
                keepAliveJob = launch(Dispatchers.IO) {
                    while (isActive) {
                        sendKeepAlive()
                        delay(5000)
                    }
                }

                try {
                    processReceiveLoop(hello.sampleRate, hello.channelCount)
                } finally {
                    keepAliveJob?.cancel()
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: EOFException) {
            Logger.i("IosConnectionHandler", "iOS client disconnected")
        } catch (e: IOException) {
            val msg = e.message ?: ""
            if (msg.contains("Socket closed", ignoreCase = true) ||
                msg.contains("Connection reset", ignoreCase = true) ||
                msg.contains("Broken pipe", ignoreCase = true)) {
                Logger.i("IosConnectionHandler", "iOS connection closed: $msg")
            } else {
                Logger.e("IosConnectionHandler", "iOS connection error", e)
                onError("iOS connection error: $msg")
            }
        } catch (e: Exception) {
            Logger.e("IosConnectionHandler", "iOS handler error", e)
            onError("iOS handler error: ${e.message}")
        } finally {
            keepAliveJob?.cancel()
        }
    }

    private fun parseHeader(bytes: ByteArray): IosPacketHeader {
        val magic = ((bytes[0].toInt() and 0xFF) shl 24) or
                    ((bytes[1].toInt() and 0xFF) shl 16) or
                    ((bytes[2].toInt() and 0xFF) shl 8) or
                    (bytes[3].toInt() and 0xFF)

        val type = ((bytes[4].toInt() and 0xFF) shl 24) or
                   ((bytes[5].toInt() and 0xFF) shl 16) or
                   ((bytes[6].toInt() and 0xFF) shl 8) or
                   (bytes[7].toInt() and 0xFF)

        val payloadLength = ((bytes[8].toInt() and 0xFF) shl 24) or
                            ((bytes[9].toInt() and 0xFF) shl 16) or
                            ((bytes[10].toInt() and 0xFF) shl 8) or
                            (bytes[11].toInt() and 0xFF)

        val sequence = ((bytes[12].toInt() and 0xFF) shl 24) or
                       ((bytes[13].toInt() and 0xFF) shl 16) or
                       ((bytes[14].toInt() and 0xFF) shl 8) or
                       (bytes[15].toInt() and 0xFF)

        return IosPacketHeader(
            magic = magic,
            type = type,
            payloadLength = payloadLength,
            sequence = sequence
        )
    }

    private fun parseHelloPayload(bytes: ByteArray): IosHelloPayload {
        var offset = 0

        val nameLen = readBigEndianInt(bytes, offset); offset += 4
        val deviceName = bytes.copyOfRange(offset, offset + nameLen).decodeToString(); offset += nameLen

        val idLen = readBigEndianInt(bytes, offset); offset += 4
        val deviceId = bytes.copyOfRange(offset, offset + idLen).decodeToString(); offset += idLen

        val sampleRate = readBigEndianInt(bytes, offset); offset += 4
        val channelCount = readBigEndianInt(bytes, offset); offset += 4

        return IosHelloPayload(
            deviceName = deviceName,
            deviceId = deviceId,
            sampleRate = sampleRate,
            channelCount = channelCount
        )
    }

    private fun readBigEndianInt(bytes: ByteArray, offset: Int): Int {
        return ((bytes[offset].toInt() and 0xFF) shl 24) or
               ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
               ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
               (bytes[offset + 3].toInt() and 0xFF)
    }

    private suspend fun sendAck() {
        val ackPayload = ByteArray(IosProtocolConstants.ACK_PAYLOAD_SIZE)
        ackPayload[0] = 1 // success = true
        // udpPort = 0 (bytes 1-4), indicating no UDP audio channel, use TCP
        // msgLen = 0 (bytes 5-8)
        val ackPacket = encodePacket(IosProtocolConstants.TYPE_ACK, 0, ackPayload)
        output.writeFully(ackPacket)
        output.flush()
        Logger.i("IosConnectionHandler", "Sent ACK (UDP port = 0, TCP-only mode)")
    }

    private suspend fun sendKeepAlive() {
        try {
            val packet = encodePacket(IosProtocolConstants.TYPE_KEEPALIVE, ++sequenceCounter, ByteArray(0))
            output.writeFully(packet)
            output.flush()
        } catch (e: Exception) {
            Logger.d("IosConnectionHandler", "Failed to send keepalive: ${e.message}")
        }
    }

    private fun encodePacket(type: Int, sequence: Int, payload: ByteArray): ByteArray {
        val packet = ByteArray(IosProtocolConstants.HEADER_SIZE + payload.size)
        val magic = IosProtocolConstants.MAGIC_HEADER
        packet[0] = (magic shr 24).toByte()
        packet[1] = (magic shr 16).toByte()
        packet[2] = (magic shr 8).toByte()
        packet[3] = magic.toByte()
        packet[4] = (type shr 24).toByte()
        packet[5] = (type shr 16).toByte()
        packet[6] = (type shr 8).toByte()
        packet[7] = type.toByte()
        packet[8] = (payload.size shr 24).toByte()
        packet[9] = (payload.size shr 16).toByte()
        packet[10] = (payload.size shr 8).toByte()
        packet[11] = payload.size.toByte()
        packet[12] = (sequence shr 24).toByte()
        packet[13] = (sequence shr 16).toByte()
        packet[14] = (sequence shr 8).toByte()
        packet[15] = sequence.toByte()
        payload.copyInto(packet, IosProtocolConstants.HEADER_SIZE)
        return packet
    }

    private suspend fun processReceiveLoop(sampleRate: Int, channelCount: Int) {
        while (currentCoroutineContext().isActive) {
            val headerBytes = ByteArray(IosProtocolConstants.HEADER_SIZE)
            input.readFully(headerBytes)

            val header = parseHeader(headerBytes)

            if (header.magic != IosProtocolConstants.MAGIC_HEADER) {
                Logger.w("IosConnectionHandler", "Invalid magic: 0x${header.magic.toString(16).uppercase()}, resyncing")
                continue
            }

            val payload = if (header.payloadLength > 0) {
                val payloadBytes = ByteArray(header.payloadLength)
                input.readFully(payloadBytes)
                payloadBytes
            } else {
                ByteArray(0)
            }

            when (header.type) {
                IosProtocolConstants.TYPE_HELLO -> {
                    Logger.w("IosConnectionHandler", "Received unexpected Hello after handshake, ignoring")
                }
                IosProtocolConstants.TYPE_KEEPALIVE -> {
                    // Client heartbeat, no action needed
                }
                IosProtocolConstants.TYPE_DISCONNECT -> {
                    val reason = if (payload.size > 4) {
                        val reasonLen = readBigEndianInt(payload, 0)
                        if (reasonLen > 0 && reasonLen <= payload.size - 4) {
                            payload.copyOfRange(4, 4 + reasonLen).decodeToString()
                        } else {
                            "unknown"
                        }
                    } else {
                        "unknown"
                    }
                    Logger.i("IosConnectionHandler", "iOS client disconnected: $reason")
                    return
                }
                IosProtocolConstants.TYPE_AUDIO_FRAME -> {
                    processAudioFrame(payload, sampleRate, channelCount)
                }
                else -> {
                    Logger.d("IosConnectionHandler", "Unknown message type: ${header.type}")
                }
            }
        }
    }

    private suspend fun processAudioFrame(payload: ByteArray, defaultSampleRate: Int, defaultChannelCount: Int) {
        if (payload.size < 24) {
            Logger.w("IosConnectionHandler", "Audio frame payload too short: ${payload.size} bytes")
            return
        }

        var offset = 0
        offset += 4 // seq (already handled by sequence in header)

        val timestamp = ((payload[offset].toLong() and 0xFF) shl 56) or
                        ((payload[offset + 1].toLong() and 0xFF) shl 48) or
                        ((payload[offset + 2].toLong() and 0xFF) shl 40) or
                        ((payload[offset + 3].toLong() and 0xFF) shl 32) or
                        ((payload[offset + 4].toLong() and 0xFF) shl 24) or
                        ((payload[offset + 5].toLong() and 0xFF) shl 16) or
                        ((payload[offset + 6].toLong() and 0xFF) shl 8) or
                        (payload[offset + 7].toLong() and 0xFF)
        offset += 8

        val sampleRate = readBigEndianInt(payload, offset); offset += 4
        val channelCount = readBigEndianInt(payload, offset); offset += 4
        val dataLen = readBigEndianInt(payload, offset); offset += 4

        if (dataLen <= 0 || offset + dataLen > payload.size) {
            Logger.w("IosConnectionHandler", "Audio frame data length invalid: $dataLen")
            return
        }

        val pcmData = payload.copyOfRange(offset, offset + dataLen)

        val effectiveSampleRate = if (sampleRate > 0) sampleRate else defaultSampleRate
        val effectiveChannelCount = if (channelCount > 0) channelCount else defaultChannelCount

        onAudioPacketReceived(
            AudioPacketMessage(
                buffer = pcmData,
                sampleRate = effectiveSampleRate,
                channelCount = effectiveChannelCount,
                audioFormat = AudioFormat.PCM_16BIT.value
            )
        )
    }
}
