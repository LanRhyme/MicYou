package com.lanrhyme.micyou

import java.util.PriorityQueue
import kotlinx.coroutines.delay

class PlaybackJitterBuffer(
    private val capacity: Int = 512,
    private val minPrebuffer: Int = 15 // ~40-60ms prebuffer at 1024-byte chunks
) {
    private val queue = PriorityQueue<AudioPlaybackMessage>(capacity) { a, b ->
        a.sequenceNumber.compareTo(b.sequenceNumber)
    }
    private var lastSeq: Int = -1
    private var buffering = true
    private val lock = Any()

    fun push(packet: AudioPlaybackMessage) {
        synchronized(lock) {
            if (!buffering && lastSeq != -1 && packet.sequenceNumber <= lastSeq) {
                // Drop late packets
                return
            }
            
            // Deduplicate
            if (queue.any { it.sequenceNumber == packet.sequenceNumber }) {
                return
            }

            queue.offer(packet)
            
            // Drop oldest if we exceed capacity
            if (queue.size > capacity) {
                queue.poll()
            }
        }
    }

    suspend fun popSuspend(): AudioPlaybackMessage {
        while (true) {
            var result: AudioPlaybackMessage? = null
            var injectSilence = false
            var silencePacket: AudioPlaybackMessage? = null

            synchronized(lock) {
                if (buffering) {
                    if (queue.size >= minPrebuffer) {
                        buffering = false
                    }
                }

                if (!buffering) {
                    val nextPacket = queue.peek()
                    if (nextPacket != null) {
                        // Check for gaps in sequence numbers
                        if (lastSeq != -1 && nextPacket.sequenceNumber > lastSeq + 1) {
                            // Missing packet! Inject a silent frame to maintain audio clock sync.
                            // This is CRITICAL for AEC reference signal stability.
                            val missingSeq = lastSeq + 1
                            lastSeq = missingSeq
                            injectSilence = true
                            silencePacket = AudioPlaybackMessage(
                                buffer = ByteArray(nextPacket.buffer.size), // Silence
                                sampleRate = nextPacket.sampleRate,
                                channelCount = nextPacket.channelCount,
                                audioFormat = nextPacket.audioFormat,
                                sequenceNumber = missingSeq
                            )
                        } else {
                            result = queue.poll()
                            lastSeq = result!!.sequenceNumber
                        }
                    } else {
                        // Buffer underrun. Re-buffer to absorb more jitter.
                        buffering = true
                    }
                }
            }
            
            if (injectSilence && silencePacket != null) {
                return silencePacket!!
            }
            if (result != null) {
                return result!!
            }
            
            // Wait for network
            delay(2)
        }
    }

    fun clear() {
        synchronized(lock) {
            queue.clear()
            lastSeq = -1
            buffering = true
        }
    }
}
