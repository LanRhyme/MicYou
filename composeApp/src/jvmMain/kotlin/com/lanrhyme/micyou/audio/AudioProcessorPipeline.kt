package com.lanrhyme.micyou.audio

import com.lanrhyme.micyou.NoiseReductionType
import java.nio.ByteBuffer
import java.nio.ByteOrder

class AudioProcessorPipeline {
    private val noiseReducer = NoiseReducer()
    private val dereverbEffect = DereverbEffect()
    private val agcEffect = AGCEffect()
    private val amplifierEffect = AmplifierEffect()
    private val vadEffect = VADEffect()
    private val resamplerEffect = ResamplerEffect()

    private var scratchShorts: ShortArray = ShortArray(0)
    private var scratchResultBuffer: ByteArray = ByteArray(0)
    private var scratchResultByteBuffer: ByteBuffer? = null

    fun updateConfig(
        enableNS: Boolean,
        nsType: NoiseReductionType,
        enableAGC: Boolean,
        agcTargetLevel: Int,
        enableVAD: Boolean,
        vadThreshold: Int,
        enableDereverb: Boolean,
        dereverbLevel: Float,
        amplification: Float
    ) {
        noiseReducer.enableNS = enableNS
        noiseReducer.nsType = nsType
        
        agcEffect.enableAGC = enableAGC
        agcEffect.agcTargetLevel = agcTargetLevel
        
        vadEffect.enableVAD = enableVAD
        vadEffect.vadThreshold = vadThreshold
        
        dereverbEffect.enableDereverb = enableDereverb
        dereverbEffect.dereverbLevel = dereverbLevel

        amplifierEffect.gainDb = amplification
    }

    fun process(
        inputBuffer: ByteArray,
        audioFormat: Int,
        channelCount: Int,
        queuedDurationMs: Long
    ): ByteArray? {
        val shorts = convertToShorts(inputBuffer, audioFormat)
        if (shorts == null || shorts.isEmpty()) return null

        var processed = shorts

        processed = amplifierEffect.process(processed, channelCount)
        processed = noiseReducer.process(processed, channelCount)
        processed = dereverbEffect.process(processed, channelCount)
        processed = agcEffect.process(processed, channelCount)
        
        vadEffect.speechProbability = noiseReducer.speechProbability
        processed = vadEffect.process(processed, channelCount)
        
        resamplerEffect.updatePlaybackRatio(queuedDurationMs)
        
        val maxOutputShorts = ((processed.size / playbackRatioLowerBound) + 16).toInt()
        val neededBytes = maxOutputShorts * 2
        ensureOutputBufferCapacity(neededBytes)
        
        val outputBuffer = scratchResultByteBuffer!!
        outputBuffer.clear()
        
        val processedShortCount = resamplerEffect.processToByteBuffer(processed, channelCount, outputBuffer)
        
        return scratchResultBuffer.copyOf(processedShortCount * 2)
    }
    
    private val playbackRatioLowerBound: Double get() = 0.97

    private fun ensureOutputBufferCapacity(neededBytes: Int) {
        if (scratchResultBuffer.size < neededBytes) {
            scratchResultBuffer = ByteArray(neededBytes)
            scratchResultByteBuffer = ByteBuffer.wrap(scratchResultBuffer).order(ByteOrder.LITTLE_ENDIAN)
        }
    }

    private fun convertToShorts(buffer: ByteArray, format: Int): ShortArray? {
        val shortsSize = when (format) {
            4, 32 -> buffer.size / 4
            3, 8 -> buffer.size
            else -> buffer.size / 2
        }
        if (shortsSize <= 0) return null
        
        if (scratchShorts.size != shortsSize) {
            scratchShorts = ShortArray(shortsSize)
        }
        val shorts = scratchShorts
        
        when (format) {
            4, 32 -> { // PCM_FLOAT (32-bit float)
                for (i in 0 until shortsSize) {
                    val byteIndex = i * 4
                    // Little Endian
                    val bits = (buffer[byteIndex].toInt() and 0xFF) or
                               ((buffer[byteIndex + 1].toInt() and 0xFF) shl 8) or
                               ((buffer[byteIndex + 2].toInt() and 0xFF) shl 16) or
                               ((buffer[byteIndex + 3].toInt() and 0xFF) shl 24)
                    val sample = Float.fromBits(bits)
                    // Clamp and convert to 16-bit PCM
                    shorts[i] = (sample * 32767.0f).toInt().coerceIn(-32768, 32767).toShort()
                }
            }
            3, 8 -> { // PCM_8BIT (Unsigned 8-bit)
                for (i in 0 until shortsSize) {
                    // 8-bit PCM is usually unsigned 0-255, center at 128
                    val sample = (buffer[i].toInt() and 0xFF) - 128
                    shorts[i] = (sample * 256).toShort()
                }
            }
            else -> { // PCM_16BIT (Default, Signed 16-bit Little Endian)
                for (i in 0 until shortsSize) {
                     val byteIndex = i * 2
                     val sample = (buffer[byteIndex].toInt() and 0xFF) or
                                  ((buffer[byteIndex + 1].toInt()) shl 8)
                     shorts[i] = sample.toShort()
                }
            }
        }
        return shorts
    }

    fun release() {
        noiseReducer.release()
        dereverbEffect.release()
        agcEffect.release()
        vadEffect.release()
        amplifierEffect.release()
        resamplerEffect.release()
    }

    fun reset() {
        noiseReducer.reset()
        dereverbEffect.reset()
        agcEffect.reset()
        vadEffect.reset()
        amplifierEffect.reset()
        resamplerEffect.reset()
    }
}
