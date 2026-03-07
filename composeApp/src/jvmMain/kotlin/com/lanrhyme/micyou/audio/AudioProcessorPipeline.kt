package com.lanrhyme.micyou.audio

import com.lanrhyme.micyou.NoiseReductionType
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 编排音频处理管道。
 * 管理音频效果器链并处理格式转换。
 */
class AudioProcessorPipeline {
    private val noiseReducer = NoiseReducer()
    private val dereverbEffect = DereverbEffect()
    private val agcEffect = AGCEffect()
    private val amplifierEffect = AmplifierEffect()
    private val vadEffect = VADEffect()
    private val resamplerEffect = ResamplerEffect()

    // 临时缓冲区
    private var scratchShorts: ShortArray = ShortArray(0)
    private var scratchResultBuffer: ByteArray = ByteArray(0)

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
        
        amplifierEffect.amplification = amplification
    }

    /**
     * 通过管道处理音频缓冲区。
     * 
     * @param inputBuffer 来自网络的原始音频字节。
     * @param audioFormat 音频格式 ID (例如 PCM_16BIT, PCM_FLOAT)。
     * @param channelCount 声道数。
     * @param queuedDurationMs 输出缓冲区中当前排队的音频时长（用于重采样控制）。
     * @return 准备输出的处理后音频字节，如果输入无效则返回 null。
     */
    fun process(
        inputBuffer: ByteArray,
        audioFormat: Int,
        channelCount: Int,
        queuedDurationMs: Long
    ): ByteArray? {
        // 1. 将字节转换为 Short
        val shorts = convertToShorts(inputBuffer, audioFormat)
        if (shorts == null || shorts.isEmpty()) return null

        var processed = shorts

        // 2. 按顺序应用效果

        // 放大器 (前置增益)
        processed = amplifierEffect.process(processed, channelCount)

        // 降噪
        processed = noiseReducer.process(processed, channelCount)

        // 去混响
        processed = dereverbEffect.process(processed, channelCount)

        // 自动增益控制 (AGC)
        processed = agcEffect.process(processed, channelCount)

        // 语音活动检测 (VAD) (使用降噪模块的语音概率)
        vadEffect.speechProbability = noiseReducer.speechProbability
        processed = vadEffect.process(processed, channelCount)
        
        // 重采样器 (播放速度控制)
        resamplerEffect.updatePlaybackRatio(queuedDurationMs)
        processed = resamplerEffect.process(processed, channelCount)

        // 3. 将 Short 转换回字节
        return convertToBytes(processed)
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

    private fun convertToBytes(shorts: ShortArray): ByteArray {
        val neededBytes = shorts.size * 2
        if (scratchResultBuffer.size != neededBytes) {
            scratchResultBuffer = ByteArray(neededBytes)
        }
        val resultBuffer = scratchResultBuffer
        ByteBuffer.wrap(resultBuffer).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(shorts)
        return resultBuffer
    }

    fun release() {
        noiseReducer.release()
        dereverbEffect.release()
        agcEffect.release()
        vadEffect.release()
        amplifierEffect.release()
        resamplerEffect.release()
    }

    /**
     * 重置管道状态以适应新连接。
     */
    fun reset() {
        noiseReducer.reset()
        dereverbEffect.reset()
        agcEffect.reset()
        vadEffect.reset()
        amplifierEffect.reset()
        resamplerEffect.reset()
    }
}
