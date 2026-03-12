package com.lanrhyme.micyou.audio

import kotlin.math.sqrt

// 自动增益控制 (AGC) 效果器，调整音频音量以保持一致的输出水平
class AGCEffect : AudioEffect {
    // 是否启用 AGC
    var enableAGC: Boolean = false
    // 目标音量水平 (RMS)
    var agcTargetLevel: Int = 32000

    private var agcEnvelope: Float = 0f

    override fun process(input: ShortArray, channelCount: Int): ShortArray {
        if (!enableAGC || agcTargetLevel <= 0) return input

        // 计算当前帧的 RMS
        var sumSquares = 0.0
        for (s in input) {
            val sample = s.toDouble() / 32768.0
            sumSquares += sample * sample
        }
        val rms = sqrt(sumSquares / input.size.toDouble())
        
        // 目标 RMS (标准化到 0.0-1.0)
        val targetRms = (agcTargetLevel.toDouble() / 32768.0).coerceIn(0.01, 0.9)
        
        if (rms > 0.001) {
            val error = targetRms / (rms + 1e-6)
            val desiredGain = error.toFloat().coerceIn(0.5f, 5.0f)
            
            if (agcEnvelope == 0f) {
                agcEnvelope = 1.0f
            }
            
            // 平滑增益变化
            val smoothing = if (desiredGain < agcEnvelope) {
                0.005f // 快速降低
            } else {
                0.01f  // 缓慢增加
            }
            agcEnvelope = agcEnvelope * (1f - smoothing) + desiredGain * smoothing
        } else {
            // 静音时缓慢恢复到 1.0
            agcEnvelope = agcEnvelope * 0.999f + 1.0f * 0.001f
        }
        
        val finalGain = agcEnvelope.coerceIn(0.8f, 5.0f)
        
        // 应用增益
        for (i in input.indices) {
            val v = (input[i].toInt() * finalGain).toInt().coerceIn(-32768, 32767)
            input[i] = v.toShort()
        }
        return input
    }

    override fun reset() {
        agcEnvelope = 0f
    }

    override fun release() {
        reset()
    }
}
