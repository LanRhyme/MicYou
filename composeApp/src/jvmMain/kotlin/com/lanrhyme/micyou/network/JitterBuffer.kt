package com.lanrhyme.micyou.network

import com.lanrhyme.micyou.AudioPacketMessage
import com.lanrhyme.micyou.AudioPacketMessageOrdered
import com.lanrhyme.micyou.Logger
import kotlinx.coroutines.*
import java.text.SimpleDateFormat
import java.util.*

/**
 * Jitter buffer with FEC recovery and PLC (Packet Loss Concealment).
 *
 * Thread safety: [insert] is called from the UDP receiver, [deliverAvailable]
 * from the delivery coroutine. All shared mutable state is guarded by [lock].
 */
class JitterBuffer(
    private val onAudioPacketReady: suspend (AudioPacketMessage) -> Unit,
    targetDelayMs: Int = 40,
    maxDelayMs: Int = 200
) {
    companion object {
        private const val TAG = "JitterBuffer"
        private const val PLC_MAX_BURST = 4
        private const val PLC_FADE_PACKETS = 3
    }

    private val targetDelayPackets: Int
    private val maxDelayPackets: Int

    private val lock = Any()

    // Shared mutable state guarded by [lock]
    private val buffer = mutableMapOf<Int, AudioPacketMessageOrdered>()
    private val fecStore = mutableMapOf<Int, AudioPacketMessageOrdered>()
    private var nextPlaySeq = -1
    private var initialized = false

    // PLC state - only touched from delivery coroutine, no lock needed
    private var lastPlayedSamples: ShortArray? = null
    private var consecutivePlcCount = 0

    // Audio format tracking - written under lock, read from delivery coroutine
    @Volatile private var currentSampleRate: Int = 48000
    @Volatile private var currentChannelCount: Int = 1
    @Volatile private var currentAudioFormat: Int = 2

    // Stats
    @Volatile private var packetsRecoveredFec = 0L
    @Volatile private var packetsRecoveredPlc = 0L
    @Volatile private var packetsOutput = 0L
    @Volatile private var packetsLostUnrecovered = 0L

    // Periodic loss logging
    @Volatile private var lastLossLogTime = 0L
    @Volatile private var lostSinceLastLog = 0L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var deliveryJob: Job? = null

    init {
        targetDelayPackets = (targetDelayMs / 7.3).toInt().coerceIn(2, 30)
        maxDelayPackets = (maxDelayMs / 7.3).toInt().coerceIn(5, 60)
    }

    fun start() {
        if (deliveryJob?.isActive == true) return
        deliveryJob = scope.launch {
            while (isActive) {
                try {
                    deliverAvailable()
                    delay(3)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Logger.e(TAG, "Delivery loop error", e)
                }
            }
        }
    }

    fun stop() {
        deliveryJob?.cancel()
        deliveryJob = null
        reset()
    }

    fun reset() = synchronized(lock) {
        buffer.clear()
        fecStore.clear()
        initialized = false
        nextPlaySeq = -1
        lastPlayedSamples = null
        consecutivePlcCount = 0
    }

    /**
     * Insert a received packet. Called from the UDP receiver thread.
     */
    fun insert(packet: AudioPacketMessageOrdered) = synchronized(lock) {
        val seq = packet.sequenceNumber

        if (packet.fecBuffer != null && packet.fecSequenceNumber >= 0) {
            fecStore[packet.fecSequenceNumber] = packet
            if (fecStore.size > maxDelayPackets * 2) {
                val cutoff = seq - maxDelayPackets * 2
                fecStore.keys.retainAll { it >= cutoff }
            }
        }

        currentSampleRate = packet.audioPacket.sampleRate
        currentChannelCount = packet.audioPacket.channelCount
        currentAudioFormat = packet.audioPacket.audioFormat

        buffer[seq] = packet

        if (!initialized) {
            nextPlaySeq = seq
            initialized = true
        }
    }

    /**
     * Drain ready packets from the buffer and deliver them.
     * Called only from the delivery coroutine (single-threaded).
     */
    private suspend fun deliverAvailable() {
        val (playSeq, bufCount) = synchronized(lock) {
            if (!initialized) return
            nextPlaySeq to buffer.size
        }

        if (packetsOutput == 0L && bufCount < targetDelayPackets && bufCount < maxDelayPackets) {
            return
        }

        var seq = playSeq
        var delivered = 0
        val maxDeliverPerRound = 20

        while (delivered < maxDeliverPerRound) {
            val packet = synchronized(lock) { buffer.remove(seq) }
            if (packet != null) {
                consecutivePlcCount = 0
                lastPlayedSamples = extractSamples(packet.audioPacket)
                onAudioPacketReady(packet.audioPacket)
                packetsOutput++
                seq++
                delivered++
            } else {
                // Gap - try FEC then PLC
                val result = handleGap(seq)
                when (result) {
                    GapResult.FILLED -> { seq++; delivered++ }
                    GapResult.WAIT -> break
                    GapResult.SKIP -> {
                        // nextPlaySeq was jumped ahead
                        synchronized(lock) { seq = nextPlaySeq }
                        break
                    }
                }
            }
        }

        synchronized(lock) { nextPlaySeq = seq }
    }

    private enum class GapResult { FILLED, WAIT, SKIP }

    private suspend fun handleGap(missingSeq: Int): GapResult {
        val (furthestSeq, nextAvail) = synchronized(lock) {
            val keys = buffer.keys
            keys.maxOrNull() to keys.filter { it > missingSeq }.minOrNull()
        }

        if (furthestSeq == null) return GapResult.WAIT

        val gapSize = furthestSeq - missingSeq

        if (gapSize > maxDelayPackets) {
            logLoss(missingSeq, furthestSeq, gapSize.toLong())
            synchronized(lock) { nextPlaySeq = furthestSeq }
            return GapResult.SKIP
        }

        // Try FEC recovery
        val fecPacket = synchronized(lock) { fecStore.remove(missingSeq) }
        if (fecPacket != null && fecPacket.fecBuffer != null) {
            val recoveredPacket = AudioPacketMessage(
                buffer = fecPacket.fecBuffer,
                sampleRate = fecPacket.audioPacket.sampleRate,
                channelCount = fecPacket.audioPacket.channelCount,
                audioFormat = fecPacket.audioPacket.audioFormat
            )
            packetsRecoveredFec++
            consecutivePlcCount = 0
            lastPlayedSamples = extractSamples(recoveredPacket)
            onAudioPacketReady(recoveredPacket)
            packetsOutput++
            return GapResult.FILLED
        }

        // Try PLC if gap is small
        if (nextAvail != null && (nextAvail - missingSeq) <= PLC_MAX_BURST) {
            val concealed = applyPlc()
            if (concealed != null) {
                packetsRecoveredPlc++
                onAudioPacketReady(concealed)
                packetsOutput++
                return GapResult.FILLED
            }
        }

        if (gapSize <= 3) return GapResult.WAIT

        // Give up
        logLoss(missingSeq, nextAvail ?: missingSeq, 1)
        packetsLostUnrecovered++
        return GapResult.FILLED
    }

    /**
     * PLC: repeat last samples with progressive fade-out.
     * Always generates 16-bit LE bytes regardless of source format,
     * since the pipeline will convert anyway.
     */
    private fun applyPlc(): AudioPacketMessage? {
        val last = lastPlayedSamples ?: return null
        if (last.isEmpty()) return null

        consecutivePlcCount++
        if (consecutivePlcCount > PLC_MAX_BURST) return null

        val fadeFactor = 1.0 - (consecutivePlcCount.toDouble() / (PLC_FADE_PACKETS + 1))
        if (fadeFactor <= 0) return null

        val concealed = ShortArray(last.size) { i ->
            (last[i] * fadeFactor).toInt().toShort()
        }

        val bytes = ByteArray(concealed.size * 2)
        for (i in concealed.indices) {
            bytes[i * 2] = (concealed[i].toInt() and 0xFF).toByte()
            bytes[i * 2 + 1] = (concealed[i].toInt() shr 8).toByte()
        }

        // PLC output is always 16-bit LE; declare format accordingly
        return AudioPacketMessage(
            buffer = bytes,
            sampleRate = currentSampleRate,
            channelCount = currentChannelCount,
            audioFormat = 2  // PCM_16BIT - matches actual byte content
        )
    }

    /**
     * Extract 16-bit PCM samples from an audio packet for PLC use.
     * Handles PCM_16BIT, PCM_FLOAT, PCM_24BIT, and PCM_8BIT.
     */
    private fun extractSamples(packet: AudioPacketMessage): ShortArray? {
        try {
            val buf = packet.buffer
            if (buf.isEmpty()) return null

            return when (packet.audioFormat) {
                2 -> { // PCM_16BIT
                    if (buf.size < 2) return null
                    ShortArray(buf.size / 2) { i ->
                        ((buf[i * 2].toInt() and 0xFF) or (buf[i * 2 + 1].toInt() shl 8)).toShort()
                    }
                }
                4, 32 -> { // PCM_FLOAT
                    if (buf.size < 4) return null
                    ShortArray(buf.size / 4) { i ->
                        val bits = (buf[i * 4].toInt() and 0xFF) or
                                   ((buf[i * 4 + 1].toInt() and 0xFF) shl 8) or
                                   ((buf[i * 4 + 2].toInt() and 0xFF) shl 16) or
                                   ((buf[i * 4 + 3].toInt() and 0xFF) shl 24)
                        val sample = Float.fromBits(bits)
                        (sample * 32768.0f).toInt().coerceIn(-32768, 32767).toShort()
                    }
                }
                6, 24 -> { // PCM_24BIT
                    if (buf.size < 3) return null
                    ShortArray(buf.size / 3) { i ->
                        val idx = i * 3
                        val sample24 = (buf[idx].toInt() and 0xFF) or
                                       ((buf[idx + 1].toInt() and 0xFF) shl 8) or
                                       (buf[idx + 2].toInt() shl 16)
                        (sample24 shr 8).toShort()
                    }
                }
                3, 8 -> { // PCM_8BIT
                    ShortArray(buf.size) { i ->
                        val sample = (buf[i].toInt() and 0xFF) - 128
                        (sample * 256).toShort()
                    }
                }
                else -> { // Default: treat as 16-bit
                    if (buf.size < 2) return null
                    ShortArray(buf.size / 2) { i ->
                        ((buf[i * 2].toInt() and 0xFF) or (buf[i * 2 + 1].toInt() shl 8)).toShort()
                    }
                }
            }
        } catch (_: Exception) {
            return null
        }
    }

    private fun logLoss(expectedSeq: Int, receivedSeq: Int, lostCount: Long) {
        lostSinceLastLog += lostCount
        val now = System.currentTimeMillis()
        if (now - lastLossLogTime >= 60_000) {
            lastLossLogTime = now
            if (lostSinceLastLog > 0) {
                val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
                val timestamp = sdf.format(Date(now))
                Logger.d(TAG, "[$timestamp] DEBUG/UdpConnectionHandler: UDP loss detected: expected $expectedSeq, received $receivedSeq, lost $lostCount packets (total in period: $lostSinceLastLog)")
            }
            lostSinceLastLog = 0
        }
    }

    fun getStats() = synchronized(lock) {
        JitterBufferStats(
            packetsRecoveredFec, packetsRecoveredPlc, packetsOutput,
            packetsLostUnrecovered, buffer.size, nextPlaySeq
        )
    }

    data class JitterBufferStats(
        val packetsRecoveredFec: Long,
        val packetsRecoveredPlc: Long,
        val packetsOutput: Long,
        val packetsLostUnrecovered: Long,
        val bufferDepth: Int,
        val nextPlaySeq: Int
    )
}
