package com.lanrhyme.micyou.audio

import kotlinx.coroutines.flow.Flow

/**
 * Interface for capturing desktop system audio (loopback).
 */
interface LoopbackCapture {
    /**
     * Start capturing audio.
     * @param sampleRate The desired sample rate.
     * @param channelCount The number of channels (1 for Mono, 2 for Stereo).
     */
    fun start(sampleRate: Int, channelCount: Int)

    /**
     * Stop capturing audio.
     */
    fun stop()

    /**
     * A flow of captured audio data packets.
     */
    val capturedData: Flow<ByteArray>

    /**
     * Whether the capture is currently active.
     */
    val isActive: Boolean

    /**
     * Get the current capture format.
     */
    val format: LoopbackFormat

    data class LoopbackFormat(
        val sampleRate: Int,
        val channelCount: Int,
        val bitsPerSample: Int
    )
}
