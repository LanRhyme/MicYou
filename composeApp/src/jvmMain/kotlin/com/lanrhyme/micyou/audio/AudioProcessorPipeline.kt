package com.lanrhyme.micyou.audio

import com.lanrhyme.micyou.NoiseReductionType
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 音频处理管道
 * 使用预分配的缓冲区池来避免频繁的内存分配和GC压力
 */
class AudioProcessorPipeline {
    private val noiseReducer = NoiseReducer()
    private val dereverbEffect = DereverbEffect()
    private val agcEffect = AGCEffect()
    private val amplifierEffect = AmplifierEffect()
    private val vadEffect = VADEffect()
    private val resamplerEffect = ResamplerEffect()

    // 预分配的缓冲区 - 使用更大的初始容量避免频繁扩容
    private var scratchShorts: ShortArray = ShortArray(INITIAL_SHORTS_CAPACITY)
    private var scratchResultBuffer: ByteArray = ByteArray(INITIAL_BYTES_CAPACITY)
    private var scratchResultByteBuffer: ByteBuffer = ByteBuffer.wrap(scratchResultBuffer).order(ByteOrder.LITTLE_ENDIAN)

    // 记录上次使用的缓冲区大小，避免重复检查
    private var lastShortsSize: Int = 0
    private var lastNeededBytes: Int = 0

    companion object {
        // 初始缓冲区容量 - 基于典型音频帧大小计算
        // 48000Hz, 16bit, 2ch, 100ms ≈ 19200 bytes, 9600 shorts
        private const val INITIAL_SHORTS_CAPACITY = 16384
        private const val INITIAL_BYTES_CAPACITY = 32768
        // 缓冲区增长因子
        private const val GROWTH_FACTOR = 1.5
    }

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
        
        val outputBuffer = scratchResultByteBuffer
        outputBuffer.clear()

        val processedShortCount = resamplerEffect.processToByteBuffer(processed, channelCount, outputBuffer)

        return scratchResultBuffer.copyOf(processedShortCount * 2)
    }

    private val playbackRatioLowerBound: Double get() = 0.97

    /**
     * 确保输出缓冲区有足够的容量
     * 使用增长因子来减少频繁扩容，避免内存抖动
     */
    private fun ensureOutputBufferCapacity(neededBytes: Int) {
        // 快速检查：如果当前容量足够，直接返回
        if (scratchResultBuffer.size >= neededBytes) return

        // 只有当需要更大容量时才扩容
        // 使用增长因子预分配更多空间，避免频繁扩容
        val newSize = (neededBytes * GROWTH_FACTOR).toInt().coerceAtLeast(neededBytes)
        scratchResultBuffer = ByteArray(newSize)
        scratchResultByteBuffer = ByteBuffer.wrap(scratchResultBuffer).order(ByteOrder.LITTLE_ENDIAN)
        lastNeededBytes = neededBytes
    }

    private fun convertToShorts(buffer: ByteArray, format: Int): ShortArray? {
        val shortsSize = when (format) {
            4, 32 -> buffer.size / 4
            3, 8 -> buffer.size
            else -> buffer.size / 2
        }
        if (shortsSize <= 0) return null

        // 快速检查：如果当前缓冲区容量足够，直接使用
        if (scratchShorts.size < shortsSize) {
            // 使用增长因子扩容
            val newSize = (shortsSize * GROWTH_FACTOR).toInt().coerceAtLeast(shortsSize)
            scratchShorts = ShortArray(newSize)
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
