package com.lanrhyme.micyou.audio

import com.lanrhyme.micyou.NoiseReductionType
import com.lanrhyme.micyou.PerformanceConfig
import com.lanrhyme.micyou.Logger
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

    // 可配置的性能参数
    private var config: PerformanceConfig = PerformanceConfig.DEFAULT

    // 预分配的缓冲区 - 使用可配置的初始容量
    private var scratchShorts: ShortArray = ShortArray(config.initialShortsCapacity)
    private var scratchResultBuffer: ByteArray = ByteArray(config.initialBytesCapacity)
    private var scratchResultByteBuffer: ByteBuffer = ByteBuffer.wrap(scratchResultBuffer).order(ByteOrder.LITTLE_ENDIAN)

    /**
     * 更新音频处理配置
     */
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
        // 使用配置的增长因子预分配更多空间，避免频繁扩容
        val newSize = (neededBytes * config.bufferGrowthFactor).toInt().coerceAtLeast(neededBytes)
        scratchResultBuffer = ByteArray(newSize)
        scratchResultByteBuffer = ByteBuffer.wrap(scratchResultBuffer).order(ByteOrder.LITTLE_ENDIAN)
    }

    private fun convertToShorts(buffer: ByteArray, format: Int): ShortArray? {
        val shortsSize = when (format) {
            4, 32 -> buffer.size / 4  // PCM_FLOAT
            6, 24 -> buffer.size / 3  // PCM_24BIT (新增)
            3, 8 -> buffer.size       // PCM_8BIT
            else -> buffer.size / 2   // PCM_16BIT
        }
        if (shortsSize <= 0) return null

        // 快速检查：如果当前缓冲区容量足够，直接使用
        if (scratchShorts.size < shortsSize) {
            // 使用配置的增长因子扩容
            val newSize = (shortsSize * config.bufferGrowthFactor).toInt().coerceAtLeast(shortsSize)
            scratchShorts = ShortArray(newSize)
        }
        val shorts = scratchShorts

        when (format) {
            6, 24 -> { // PCM_24BIT (24-bit Little Endian, signed)
                for (i in 0 until shortsSize) {
                    val byteIndex = i * 3
                    // 24-bit Little Endian: LSB first
                    val sample24 = (buffer[byteIndex].toInt() and 0xFF) or
                                   ((buffer[byteIndex + 1].toInt() and 0xFF) shl 8) or
                                   ((buffer[byteIndex + 2].toInt()) shl 16)
                    // 将 24-bit 值缩放到 16-bit 范围（右移 8 位）
                    shorts[i] = (sample24 shr 8).toShort()
                }
            }
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
        // 重要：只返回实际填充的数据大小，而不是整个预分配缓冲区
        // 这样后续处理才能正确处理实际数据量，避免旧数据混入产生噪音
        return shorts.copyOf(shortsSize)
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

    /**
     * 更新性能配置
     * 在运行时调整缓冲区策略
     */
    fun updatePerformanceConfig(newConfig: PerformanceConfig) {
        config = newConfig

        // 仅在新配置需要更大容量时扩容
        if (newConfig.initialShortsCapacity > scratchShorts.size) {
            scratchShorts = ShortArray(newConfig.initialShortsCapacity)
        }
        if (newConfig.initialBytesCapacity > scratchResultBuffer.size) {
            scratchResultBuffer = ByteArray(newConfig.initialBytesCapacity)
            scratchResultByteBuffer = ByteBuffer.wrap(scratchResultBuffer).order(ByteOrder.LITTLE_ENDIAN)
        }

        Logger.d("AudioProcessorPipeline", "性能配置已更新: shorts=${scratchShorts.size}, bytes=${scratchResultBuffer.size}, growthFactor=${newConfig.bufferGrowthFactor}")
    }
}
