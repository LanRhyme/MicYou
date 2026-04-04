package com.lanrhyme.micyou.audio

import java.nio.ByteBuffer
import kotlin.math.abs

class ResamplerEffect : AudioEffect {
    var playbackRatio: Double = 1.0
    
    private var resamplePosFrames: Double = 0.0
    private var resamplePrevFrame: ShortArray = ShortArray(0)
    private var scratchResampledShorts: ShortArray = ShortArray(0)
    private val lock = Any()
    
    var playbackRatioIntegral: Double = 0.0
        private set

    data class ProcessResult(val buffer: ShortArray, val validLength: Int)
    
    fun processWithLength(input: ShortArray, channelCount: Int): ProcessResult {
        synchronized(lock) {
            if (abs(playbackRatio - 1.0) < 0.00005) {
                return ProcessResult(input, input.size)
            }
            
            val processedShortCount = resampleInterleavedShorts(input, channelCount, playbackRatio)
            return ProcessResult(scratchResampledShorts.copyOf(processedShortCount), processedShortCount)
        }
    }
    
    override fun process(input: ShortArray, channelCount: Int): ShortArray {
        synchronized(lock) {
            if (abs(playbackRatio - 1.0) < 0.00005) {
                lastProcessLength = input.size
                return input
            }
            
            val processedShortCount = resampleInterleavedShorts(input, channelCount, playbackRatio)
            lastProcessLength = processedShortCount
            
            return scratchResampledShorts.copyOf(processedShortCount)
        }
    }
    
    fun processToByteBuffer(input: ShortArray, channelCount: Int, output: ByteBuffer): Int {
        synchronized(lock) {
            if (abs(playbackRatio - 1.0) < 0.00005) {
                output.asShortBuffer().put(input)
                return input.size
            }
            
            val processedShortCount = resampleInterleavedShorts(input, channelCount, playbackRatio)
            output.asShortBuffer().put(scratchResampledShorts, 0, processedShortCount)
            return processedShortCount
        }
    }
    
    var lastProcessLength: Int = 0
        private set
    
    fun updatePlaybackRatio(queuedMs: Long): Double {
        val targetMs = 80.0
        val errorMs = queuedMs.toDouble() - targetMs
        
        val kP = 0.0008
        val kI = 0.000008
        val maxAdjust = 0.03
        
        var integral = (playbackRatioIntegral + errorMs * 0.01).coerceIn(-5000.0, 5000.0)
        playbackRatioIntegral = integral
        val adjust = (errorMs * kP + integral * kI).coerceIn(-maxAdjust, maxAdjust)
        playbackRatio = (1.0 + adjust).coerceIn(1.0 - maxAdjust, 1.0 + maxAdjust)
        return playbackRatio
    }

    private fun resampleInterleavedShorts(processedShorts: ShortArray, channelCount: Int, ratio: Double): Int {
        if (channelCount <= 0) return 0
        val inputFrames = processedShorts.size / channelCount
        if (inputFrames <= 1) {
            // 没有足够的帧进行插值，只需复制到 scratch
            if (scratchResampledShorts.size < processedShorts.size) {
                scratchResampledShorts = ShortArray(processedShorts.size)
            }
            System.arraycopy(processedShorts, 0, scratchResampledShorts, 0, processedShorts.size)
            return processedShorts.size
        }

        if (resamplePrevFrame.size != channelCount) {
            resamplePrevFrame = ShortArray(channelCount)
            for (c in 0 until channelCount) {
                resamplePrevFrame[c] = processedShorts[c]
            }
            resamplePosFrames = 1.0
        }

        val effectiveFrames = inputFrames + 1
        var outFrames = 0
        var pos = resamplePosFrames
        
        val estimatedOutFrames = ((inputFrames.toDouble() / ratio) + 4.0).toInt().coerceAtLeast(8)
        val neededShorts = estimatedOutFrames * channelCount
        if (scratchResampledShorts.size < neededShorts) {
            scratchResampledShorts = ShortArray(neededShorts)
        }

        fun sample(frameIndex: Int, channel: Int): Int {
            return if (frameIndex == 0) {
                resamplePrevFrame[channel].toInt()
            } else {
                processedShorts[(frameIndex - 1) * channelCount + channel].toInt()
            }
        }

        while (true) {
            val base = pos.toInt()
            if (base + 1 >= effectiveFrames) break
            val frac = pos - base.toDouble()

            val outBase = outFrames * channelCount
            
            // 检查是否需要扩容，预先分配足够空间避免频繁扩容
            val required = (outFrames + 1) * channelCount
            if (required > scratchResampledShorts.size) {
                // 预分配更大的空间（按预估大小的1.5倍），避免循环内频繁扩容
                val newSize = ((estimatedOutFrames * channelCount * 1.5).toInt()).coerceAtLeast(required)
                scratchResampledShorts = ShortArray(newSize)
            }
            
            for (c in 0 until channelCount) {
                val s0 = sample(base, c)
                val s1 = sample(base + 1, c)
                val v = (s0 + (s1 - s0) * frac).toInt().coerceIn(-32768, 32767)
                scratchResampledShorts[outBase + c] = v.toShort()
            }

            outFrames++
            pos += ratio
        }

        val lastFrameOffset = (inputFrames - 1) * channelCount
        for (c in 0 until channelCount) {
            resamplePrevFrame[c] = processedShorts[lastFrameOffset + c]
        }

        resamplePosFrames = pos - inputFrames.toDouble()

        return outFrames * channelCount
    }

    override fun reset() {
        resamplePosFrames = 0.0
        resamplePrevFrame = ShortArray(0)
        playbackRatioIntegral = 0.0
        playbackRatio = 1.0
    }

    override fun release() {
        reset()
    }
}
