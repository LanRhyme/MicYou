package com.lanrhyme.micyou.network

import com.lanrhyme.micyou.AudioPacketMessage
import com.lanrhyme.micyou.AudioPacketMessageOrdered
import com.lanrhyme.micyou.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * 音频抖动缓冲区
 * 用于处理UDP乱序包，保持音频播放顺序
 * 设计目标：低延迟 + 正确的播放顺序
 *
 * 特性：
 * - 即时 gap 处理：检测到丢包立即尝试 FEC 恢复或跳过，不等待
 * - FEC 恢复：使用前向纠错包恢复丢失的音频数据
 * - 已播放包缓存：保留最近播放的包供 FEC 恢复使用
 */
class JitterBuffer(
    private val onAudioPacketReady: suspend (AudioPacketMessage) -> Unit,
    private val fecGroupSize: Int = 12
) {
    companion object {
        /**
         * 序列号倒退超过该阈值时，判定为客户端重连的新会话（而非普通乱序）。
         * 取一个足够大的值以免把正常乱序误判为重连：正常网络乱序只差几个到几十个包，
         * 而重连时序列号会从头(接近 0)开始，与当前期待值相差成千上万。
         */
        private const val SESSION_RESET_BACKWARD_GAP = 1000
    }

    private val buffer = ConcurrentHashMap<Int, AudioPacketMessageOrdered>()

    private val fecPackets = ConcurrentHashMap<Int, AudioPacketMessageOrdered>()

    private val playedPackets = ConcurrentHashMap<Int, AudioPacketMessageOrdered>()

    private val expectedSequenceNumber = AtomicInteger(0)

    private val initialized = AtomicInteger(0)

    private val packetsReceived = AtomicInteger(0)
    private val packetsLost = AtomicInteger(0)
    private val packetsOutOfOrder = AtomicInteger(0)
    private val fecRecovered = AtomicInteger(0)
    private val fecFailed = AtomicInteger(0)
    private val fecReceived = AtomicInteger(0)
    private val gapsDetected = AtomicInteger(0)

    @Volatile
    private var isRunning = false

    @Volatile
    private var lastStatsLogTime: Long = 0L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun insert(packet: AudioPacketMessageOrdered) {
        if (!isRunning) return

        if (packet.fecSequenceNumber >= 0) {
            fecReceived.incrementAndGet()
            fecPackets[packet.fecSequenceNumber] = packet
            maybeLogStats()
            return
        }

        packetsReceived.incrementAndGet()

        if (initialized.get() == 0) {
            expectedSequenceNumber.set(packet.sequenceNumber)
            initialized.set(1)
        }

        val currentExpected = expectedSequenceNumber.get()

        // 新会话检测：序列号大幅倒退说明客户端已重连、序列号从头重新开始。
        // 若不处理，新会话的小序列号会一直被下面的 "< currentExpected" 判定为
        // 过期乱序包而全部丢弃，导致重连后有连接、有 UDP 包却完全没有声音。
        // 此处重置缓冲状态，以新序列号为基准重新开始播放。
        if (currentExpected - packet.sequenceNumber > SESSION_RESET_BACKWARD_GAP) {
            Logger.i(
                "JitterBuffer",
                "检测到序列号大幅倒退 (期待 $currentExpected, 收到 ${packet.sequenceNumber})，" +
                        "判定为客户端重连，重置抖动缓冲以恢复音频"
            )
            resetForNewSession(packet.sequenceNumber)
        }

        val expectedAfterReset = expectedSequenceNumber.get()
        if (packet.sequenceNumber < expectedAfterReset) {
            packetsOutOfOrder.incrementAndGet()
            return
        }

        buffer[packet.sequenceNumber] = packet
        processBuffer()
        maybeLogStats()
    }

    /**
     * 客户端重连后，序列号从头开始，需要清空旧会话遗留的缓冲/FEC/已播放数据，
     * 并以新的序列号作为新的期待起点，否则新包会被当作过期乱序包丢弃。
     */
    private fun resetForNewSession(newSequenceNumber: Int) {
        buffer.clear()
        fecPackets.clear()
        playedPackets.clear()
        expectedSequenceNumber.set(newSequenceNumber)
    }

    private fun processBuffer() {
        while (isRunning) {
            val seqNum = expectedSequenceNumber.get()
            val packet = buffer.remove(seqNum)

            if (packet != null) {
                playedPackets[seqNum] = packet
                cleanupPlayedPackets(seqNum)

                val packetToPlay = packet
                scope.launch {
                    try {
                        onAudioPacketReady(packetToPlay.audioPacket)
                    } catch (e: Exception) {
                        Logger.e("JitterBuffer", "Error playing audio packet: ${e.message}")
                    }
                }

                expectedSequenceNumber.incrementAndGet()
                continue
            }

            val nextAvailable = buffer.keys.minOrNull()
            if (nextAvailable == null || nextAvailable <= seqNum) {
                break
            }

            gapsDetected.incrementAndGet()

            val recovered = tryFecRecovery(seqNum)
            if (recovered != null) {
                fecRecovered.incrementAndGet()
                scope.launch {
                    try {
                        onAudioPacketReady(recovered)
                    } catch (e: Exception) {
                        Logger.e("JitterBuffer", "Error playing FEC recovered packet: ${e.message}")
                    }
                }
                expectedSequenceNumber.incrementAndGet()
            } else {
                val lostCount = nextAvailable - seqNum
                packetsLost.addAndGet(lostCount)
                fecFailed.incrementAndGet()
                expectedSequenceNumber.set(nextAvailable)
            }
        }
    }

    private fun tryFecRecovery(missingSeq: Int): AudioPacketMessage? {
        val groupStart = (missingSeq / fecGroupSize) * fecGroupSize
        val fecPacket = fecPackets[groupStart] ?: return null

        val receivedInGroup = mutableListOf<ByteArray>()
        for (seq in groupStart until groupStart + fecGroupSize) {
            if (seq == missingSeq) continue
            val pkt = buffer[seq] ?: playedPackets[seq]
            if (pkt != null) {
                receivedInGroup.add(pkt.audioPacket.buffer)
            } else {
                return null
            }
        }

        if (receivedInGroup.size != fecGroupSize - 1) {
            return null
        }

        val fecBuffer = fecPacket.audioPacket.buffer
        val recoveredBuffer = xorBuffers(listOf(fecBuffer) + receivedInGroup)

        val referencePacket = (buffer[groupStart] ?: playedPackets[groupStart])?.audioPacket
            ?: (buffer[groupStart + 1] ?: playedPackets[groupStart + 1])?.audioPacket
            ?: return null

        return AudioPacketMessage(
            buffer = recoveredBuffer,
            sampleRate = referencePacket.sampleRate,
            channelCount = referencePacket.channelCount,
            audioFormat = referencePacket.audioFormat
        )
    }

    private fun xorBuffers(buffers: List<ByteArray>): ByteArray {
        val maxLen = buffers.maxOf { it.size }
        val result = ByteArray(maxLen)
        for (buf in buffers) {
            for (i in buf.indices) {
                result[i] = (result[i].toInt() xor buf[i].toInt()).toByte()
            }
        }
        return result
    }

    private fun cleanupPlayedPackets(currentSeq: Int) {
        val threshold = currentSeq - fecGroupSize * 2
        if (threshold <= 0) return
        val iter = playedPackets.keys.iterator()
        while (iter.hasNext()) {
            val seq = iter.next()
            if (seq < threshold) {
                iter.remove()
            }
        }
    }

    private fun maybeLogStats() {
        val now = System.currentTimeMillis()
        if (lastStatsLogTime == 0L) {
            lastStatsLogTime = now
            return
        }
        if (now - lastStatsLogTime < 60_000) return
        lastStatsLogTime = now

        val received = packetsReceived.get()
        val lost = packetsLost.get()
        val lossRate = if (received + lost > 0) String.format("%.2f", lost.toDouble() / (received + lost) * 100.0) else "0.00"
        Logger.i(
            "JitterBuffer",
            "Stats: received=$received, lost=$lost (${lossRate}%), ooo=${packetsOutOfOrder.get()}, " +
                    "gaps=${gapsDetected.get()}, " +
                    "fecRx=${fecReceived.get()}, fecOk=${fecRecovered.get()}, fecFail=${fecFailed.get()}, buf=${buffer.size}"
        )
    }

    fun start() {
        isRunning = true
        Logger.i("JitterBuffer", "Jitter buffer started (fecGroupSize=$fecGroupSize)")
    }

    fun stop() {
        isRunning = false
        buffer.clear()
        fecPackets.clear()
        playedPackets.clear()
        expectedSequenceNumber.set(0)
        initialized.set(0)
        packetsReceived.set(0)
        packetsLost.set(0)
        packetsOutOfOrder.set(0)
        fecRecovered.set(0)
        fecFailed.set(0)
        fecReceived.set(0)
        gapsDetected.set(0)
        lastStatsLogTime = 0L
        Logger.i("JitterBuffer", "Jitter buffer stopped")
    }

    fun getStats(): String {
        return "received=${packetsReceived.get()}, lost=${packetsLost.get()}, outOfOrder=${packetsOutOfOrder.get()}, " +
                "fecReceived=${fecReceived.get()}, fecRecovered=${fecRecovered.get()}, fecFailed=${fecFailed.get()}, buffer=${buffer.size}"
    }
}
