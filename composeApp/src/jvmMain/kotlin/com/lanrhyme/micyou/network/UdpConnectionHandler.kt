package com.lanrhyme.micyou.network

import com.lanrhyme.micyou.*
import kotlinx.coroutines.*
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.protobuf.ProtoBuf
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress

/**
 * Handles UDP audio data connection (Desktop server).
 * Responsibilities include:
 * 1. Receiving UDP audio packets
 * 2. Parsing and validating packet format
 * 3. Detecting packet loss/reordering using sequence numbers
 * 4. Dispatching audio packets to listeners
 * 
 * Unlike ConnectionHandler, the UDP processor:
 * - Does not require handshake (UDP is connectionless)
 * - Does not send control messages (control messages go through TCP channel)
 * - Tolerates packet loss (audio can handle small amounts of loss)
 */
class UdpConnectionHandler(
    private val port: Int,
    private val onAudioPacketReceived: suspend (AudioPacketMessage) -> Unit,
    private val onError: (String) -> Unit
) {
    @OptIn(ExperimentalSerializationApi::class)
    private val proto = ProtoBuf { }

    private var udpSocket: DatagramSocket? = null
    private var handlerJob: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Client address mapping (currently supports single client only)
    @Volatile
    private var clientAddress: InetSocketAddress? = null

    // Sequence number tracking (for packet loss statistics)
    @Volatile
    private var expectedSequenceNumber = 0
    @Volatile
    private var packetsReceived = 0L
    @Volatile
    private var packetsLost = 0L

    // Log rate limiting
    @Volatile
    private var lastLossLogTime = 0L
    @Volatile
    private var lastOooLogTime = 0L

    // Jitter calculation
    @Volatile
    private var lastTransmitTime = 0L
    @Volatile
    private var lastReceiveTime = 0L
    @Volatile
    private var jitter = 0.0

    /**
     * Starts the UDP receiving loop.
     * This function is non-blocking and runs in a background coroutine.
     */
    fun start() {
        handlerJob?.takeIf { it.isActive }?.let {
            Logger.w("UdpConnectionHandler", "UDP handler is already running")
            return
        }

        handlerJob = scope.launch {
            runUdpReceiver()
        }
    }

    /**
     * Stops the UDP receiver.
     */
    suspend fun stop() {
        handlerJob?.cancel()
        withTimeoutOrNull(2000) {
            handlerJob?.join()
        }
        cleanup()
    }

    /**
     * Resets the client address. Called when a new TCP connection is established,
     * so that stale UDP addresses don't cause playback data to be sent to the
     * wrong port. The address will be updated when the first UDP packet from
     * the new client arrives.
     */
    fun resetClientAddress() {
        clientAddress = null
        expectedSequenceNumber = 0
        packetsReceived = 0L
        packetsLost = 0L
        jitter = 0.0
        lastTransmitTime = 0L
        lastReceiveTime = 0L
    }

    /**
     * Gets statistics (for debugging/monitoring)
     */
    fun getStats(): UdpStats = UdpStats(
        packetsReceived = packetsReceived,
        packetsLost = packetsLost,
        jitter = jitter,
        clientAddress = clientAddress
    )

    /**
     * Sends audio playback data to the client via UDP.
     */
    @OptIn(ExperimentalSerializationApi::class)
    suspend fun sendAudioPlayback(playback: AudioPlaybackMessage) {
        val address = clientAddress ?: return
        val socket = udpSocket ?: return

        try {
            val wrapper = MessageWrapper(audioPlayback = playback)
            val packetBytes = proto.encodeToByteArray(MessageWrapper.serializer(), wrapper)
            val length = packetBytes.size
            
            val header = ByteArray(8).apply {
                this[0] = (UDP_PACKET_MAGIC shr 24).toByte()
                this[1] = (UDP_PACKET_MAGIC shr 16).toByte()
                this[2] = (UDP_PACKET_MAGIC shr 8).toByte()
                this[3] = UDP_PACKET_MAGIC.toByte()
                this[4] = (length shr 24).toByte()
                this[5] = (length shr 16).toByte()
                this[6] = (length shr 8).toByte()
                this[7] = length.toByte()
            }

            val udpPacket = DatagramPacket(header + packetBytes, 8 + length, address)
            withContext(Dispatchers.IO) {
                socket.send(udpPacket)
            }
        } catch (e: Exception) {
            Logger.w("UdpConnectionHandler", "Failed to send audio playback via UDP: ${e.message}")
        }
    }

    private suspend fun runUdpReceiver() {
        try {
            // Use SO_REUSEADDR to allow rebinding if the port is still in TIME_WAIT
            // from a previous server instance. Without this, restarting the server
            // quickly causes BindException: Address already in use.
            val socket = DatagramSocket(null)
            socket.reuseAddress = true
            socket.bind(java.net.InetSocketAddress(port))
            udpSocket = socket

            socket.sendBufferSize = 1 * 1024 * 1024 // 1MB send buffer
            socket.receiveBufferSize = 2 * 1024 * 1024 // 2MB receive buffer
            val actualSendBuffer = socket.sendBufferSize
            val actualBufferSize = socket.receiveBufferSize
            Logger.i("UdpConnectionHandler", "UDP receiver started on port $port (SO_REUSEADDR=true), send buffer: ${actualSendBuffer / 1024}KB, receive buffer: ${actualBufferSize / 1024}KB")
            if (actualBufferSize < 512 * 1024) {
                Logger.w("UdpConnectionHandler", "UDP receive buffer is smaller than expected (${actualBufferSize / 1024}KB). Consider adjusting OS UDP buffer limits.")
            }
    val buffer = ByteArray(Constants.MAX_PACKET_SIZE)

            while (currentCoroutineContext().isActive) {
                val packet = DatagramPacket(buffer, buffer.size)
                try {
                    udpSocket?.receive(packet)
                } catch (e: java.net.SocketException) {
                    // This exception is thrown when the socket is closed, exit normally
                    if (currentCoroutineContext().isActive) {
                        Logger.e("UdpConnectionHandler", "UDP receive error", e)
                    }
                    break
                }
    val senderAddress = InetSocketAddress(packet.address, packet.port)

                // Update client address. This is essential for reconnection: the Android
                // client creates a new DatagramSocket (random port) on each connection,
                // so the address changes. Without updating, playback data would be sent
                // to the old (dead) address and never reach the client.
                val prevAddress = clientAddress
                if (clientAddress == null || clientAddress != senderAddress) {
                    clientAddress = senderAddress
                    if (prevAddress == null) {
                        Logger.i("UdpConnectionHandler", "UDP client connected: ${senderAddress.address.hostAddress}:${senderAddress.port}")
                    } else {
                        Logger.i("UdpConnectionHandler", "UDP client address changed: ${prevAddress.address.hostAddress}:${prevAddress.port} -> ${senderAddress.address.hostAddress}:${senderAddress.port}")
                        // Reset sequence tracking for the new client session
                        expectedSequenceNumber = 0
                        packetsReceived = 0L
                        packetsLost = 0L
                        jitter = 0.0
                        lastTransmitTime = 0L
                        lastReceiveTime = 0L
                    }
                }

                processUdpPacket(packet.data, packet.offset, packet.length)
            }
        } catch (e: Exception) {
            if (currentCoroutineContext().isActive) {
                Logger.e("UdpConnectionHandler", "UDP receiver fatal error", e)
                onError("UDP receiver error: ${e.message}")
            }
        } finally {
            cleanup()
        }
    }

    @OptIn(ExperimentalSerializationApi::class)
    private suspend fun processUdpPacket(data: ByteArray, offset: Int, length: Int) {
        if (length < 8) {
            // Packet too small, skip
            return
        }

        // Parse magic number (4-byte big-endian)
        val magic = ((data[offset].toInt() and 0xFF) shl 24) or
                    ((data[offset + 1].toInt() and 0xFF) shl 16) or
                    ((data[offset + 2].toInt() and 0xFF) shl 8) or
                    (data[offset + 3].toInt() and 0xFF)

        if (magic != UDP_PACKET_MAGIC) {
            Logger.w("UdpConnectionHandler", "UDP packet magic mismatch: 0x${magic.toString(16).uppercase()}")
            return
        }

        // Parse length (4-byte big-endian)
        val payloadLength = ((data[offset + 4].toInt() and 0xFF) shl 24) or
                     ((data[offset + 5].toInt() and 0xFF) shl 16) or
                     ((data[offset + 6].toInt() and 0xFF) shl 8) or
                     (data[offset + 7].toInt() and 0xFF)

        if (payloadLength <= 0 || payloadLength > length - 8) {
            Logger.w("UdpConnectionHandler", "UDP packet length invalid: $payloadLength")
            return
        }
    val payloadStart = offset + 8

        try {
            val wrapper: MessageWrapper = proto.decodeFromByteArray(MessageWrapper.serializer(), data.copyOfRange(payloadStart, payloadStart + payloadLength))

            // UDP channel only processes audio packets
            val audioPacket = wrapper.audioPacket?.packet
            if (audioPacket != null) {
                // Sequence number tracking
                val seqNum = wrapper.audioPacket.sequenceNumber
                if (packetsReceived == 0L) {
                    expectedSequenceNumber = seqNum
                    packetsReceived++
                } else {
                    val expected = expectedSequenceNumber + 1
                    if (seqNum != expected) {
                        // Use unsigned comparison for sequence number (handles Int wrap correctly)
                        val diffUnsigned = (seqNum.toUInt() - expected.toUInt()).toLong() and 0xFFFFFFFFL
                        // If diff < 0x80000000u, seqNum is "after" expected (normal loss)
                        // If diff >= 0x80000000u, seqNum is "before" expected (out-of-order/repeat)
                        val isGapForward = diffUnsigned < 0x80000000L
                        if (isGapForward) {
                            // Normal packet loss: received sequence number > expected
                            val lost = diffUnsigned
                            // Sanity check: limit max loss per gap to prevent absurd counts
                            val cappedLost = minOf(lost, 1000L)
                            packetsLost += cappedLost
                            expectedSequenceNumber = seqNum
                            packetsReceived++
                            val now = System.currentTimeMillis()
                            if (now - lastLossLogTime > 60000) {
                                Logger.d("UdpConnectionHandler", "UDP loss detected: expected $expected, received $seqNum, lost $cappedLost packets")
                                lastLossLogTime = now
                            }
                        } else {
                            // Out-of-order or duplicate packet: do NOT advance expectedSequenceNumber
                            // This prevents cascading false loss detection when UDP delivers packets out of order
                            packetsReceived++
                            val now = System.currentTimeMillis()
                            if (now - lastOooLogTime > 60000) {
                                Logger.d("UdpConnectionHandler", "UDP out-of-order packet: expected $expected, received $seqNum (ignored for loss tracking)")
                                lastOooLogTime = now
                            }
                        }
                    } else {
                        expectedSequenceNumber = seqNum
                        packetsReceived++
                    }
                }

                // Jitter calculation (RFC 3550 variant)
                val transmitTime = wrapper.audioPacket.timestamp
                val receiveTime = System.currentTimeMillis()
                if (packetsReceived > 1 && transmitTime > 0 && lastTransmitTime > 0) {
                    val d = (receiveTime - lastReceiveTime) - (transmitTime - lastTransmitTime)
                    jitter += (kotlin.math.abs(d) - jitter) / 16.0
                }
                lastTransmitTime = transmitTime
                lastReceiveTime = receiveTime

                onAudioPacketReceived(audioPacket)
            }
        } catch (e: Exception) {
            Logger.e("UdpConnectionHandler", "UDP packet decoding failed", e)
        }
    }

    private fun cleanup() {
        try {
            udpSocket?.close()
        } catch (e: Exception) {
            Logger.w("UdpConnectionHandler", "Error closing UDP socket: ${e.message}")
        }
        udpSocket = null
        clientAddress = null
    }

    data class UdpStats(
        val packetsReceived: Long,
        val packetsLost: Long,
        val jitter: Double,
        val clientAddress: InetSocketAddress?
    ) {
        val lossRate: Double
            get() = if (packetsReceived + packetsLost > 0) {
                packetsLost.toDouble() / (packetsReceived + packetsLost) * 100.0
            } else 0.0
    }
}
