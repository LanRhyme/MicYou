package com.lanrhyme.micyou.network

import com.lanrhyme.micyou.*
import kotlinx.coroutines.*
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.protobuf.ProtoBuf
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.text.SimpleDateFormat
import java.util.*

/**
 * Handles UDP audio data connection (Desktop server).
 * Responsibilities include:
 * 1. Receiving UDP audio packets
 * 2. Parsing and validating packet format
 * 3. Detecting packet loss/reordering using sequence numbers
 * 4. Feeding packets into a jitter buffer for reordering, FEC recovery, and PLC
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

    // Jitter calculation
    @Volatile
    private var lastTransmitTime = 0L
    @Volatile
    private var lastReceiveTime = 0L
    @Volatile
    private var jitter = 0.0

    // Jitter buffer for reordering, FEC recovery, and PLC
    private val jitterBuffer = JitterBuffer(
        onAudioPacketReady = onAudioPacketReceived,
        targetDelayMs = 40,
        maxDelayMs = 200
    )

    // Periodic loss logging (once per minute)
    @Volatile
    private var lastLossLogTime = 0L
    @Volatile
    private var lostSinceLastLog = 0L

    /**
     * Starts the UDP receiving loop.
     * This function is non-blocking and runs in a background coroutine.
     */
    fun start() {
        handlerJob?.takeIf { it.isActive }?.let {
            Logger.w("UdpConnectionHandler", "UDP handler is already running")
            return
        }

        jitterBuffer.start()

        handlerJob = scope.launch {
            runUdpReceiver()
        }
    }

    /**
     * Stops the UDP receiver.
     */
    suspend fun stop() {
        jitterBuffer.stop()
        handlerJob?.cancel()
        withTimeoutOrNull(2000) {
            handlerJob?.join()
        }
        cleanup()
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

    private suspend fun runUdpReceiver() {
        try {
            udpSocket = DatagramSocket(port).also { socket ->
                // Set receive buffer size for audio stream
                socket.receiveBufferSize = 2 * 1024 * 1024 // 2MB
                val actualBufferSize = socket.receiveBufferSize
                Logger.i("UdpConnectionHandler", "UDP receiver started on port $port, receive buffer: ${actualBufferSize / 1024}KB (requested: 2048KB)")
                if (actualBufferSize < 512 * 1024) {
                    Logger.w("UdpConnectionHandler", "UDP receive buffer is smaller than expected (${actualBufferSize / 1024}KB). Consider adjusting OS UDP buffer limits.")
                }
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

                // Record the first client address
                if (clientAddress == null) {
                    clientAddress = senderAddress
                    Logger.i("UdpConnectionHandler", "UDP client connected: ${senderAddress.address.hostAddress}:${senderAddress.port}")
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
            return
        }

        // Parse length (4-byte big-endian)
        val payloadLength = ((data[offset + 4].toInt() and 0xFF) shl 24) or
                     ((data[offset + 5].toInt() and 0xFF) shl 16) or
                     ((data[offset + 6].toInt() and 0xFF) shl 8) or
                     (data[offset + 7].toInt() and 0xFF)

        if (payloadLength <= 0 || payloadLength > length - 8) {
            return
        }
    val payloadStart = offset + 8

        try {
            val wrapper: MessageWrapper = proto.decodeFromByteArray(MessageWrapper.serializer(), data.copyOfRange(payloadStart, payloadStart + payloadLength))

            // UDP channel only processes audio packets
            val orderedPacket = wrapper.audioPacket
            if (orderedPacket != null) {
                // Sequence number tracking
                val seqNum = orderedPacket.sequenceNumber
                if (packetsReceived == 0L) {
                    expectedSequenceNumber = seqNum
                    packetsReceived++
                } else {
                    val expected = expectedSequenceNumber + 1
                    if (seqNum != expected) {
                        // Use unsigned comparison for sequence number (handles Int wrap correctly)
                        val diffUnsigned = (seqNum.toUInt() - expected.toUInt()).toLong() and 0xFFFFFFFFL
                        val isGapForward = diffUnsigned < 0x80000000L
                        if (isGapForward) {
                            val lost = diffUnsigned
                            val cappedLost = minOf(lost, 1000L)
                            packetsLost += cappedLost
                            expectedSequenceNumber = seqNum
                            packetsReceived++
                            // Accumulate loss for periodic logging
                            lostSinceLastLog += cappedLost
                            logLossPeriodically(expected, seqNum, cappedLost)
                        } else {
                            // Out-of-order or duplicate packet: do NOT advance expectedSequenceNumber
                            packetsReceived++
                        }
                    } else {
                        expectedSequenceNumber = seqNum
                        packetsReceived++
                    }
                }

                // Jitter calculation (RFC 3550 variant)
                val transmitTime = orderedPacket.timestamp
                val receiveTime = System.currentTimeMillis()
                if (packetsReceived > 1 && transmitTime > 0 && lastTransmitTime > 0) {
                    val d = (receiveTime - lastReceiveTime) - (transmitTime - lastTransmitTime)
                    jitter += (kotlin.math.abs(d) - jitter) / 16.0
                }
                lastTransmitTime = transmitTime
                lastReceiveTime = receiveTime

                // Feed into jitter buffer (handles reordering, FEC recovery, PLC, and delivery)
                jitterBuffer.insert(orderedPacket)
            }
        } catch (e: Exception) {
            Logger.e("UdpConnectionHandler", "UDP packet decoding failed", e)
        }
    }

    /**
     * Log loss events once per minute in the specified format.
     */
    private fun logLossPeriodically(expected: Int, received: Int, lostCount: Long) {
        val now = System.currentTimeMillis()
        if (now - lastLossLogTime >= 60_000) {
            lastLossLogTime = now
            if (lostSinceLastLog > 0) {
                val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
                val timestamp = sdf.format(Date(now))
                Logger.d("UdpConnectionHandler", "[$timestamp] DEBUG/UdpConnectionHandler: UDP loss detected: expected $expected, received $received, lost $lostCount packets (total in period: $lostSinceLastLog)")
            }
            lostSinceLastLog = 0
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
        jitterBuffer.reset()
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
