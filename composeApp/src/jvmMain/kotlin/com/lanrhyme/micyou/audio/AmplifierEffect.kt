package com.lanrhyme.micyou.audio

import kotlin.math.pow

/**
 * 放大器效果器。
 * 简单的线性音量放大，支持 dB 输入。
 */
class AmplifierEffect : AudioEffect {
    /** 增益值 (dB)，0 = 原始音量 */
    var gainDb: Float = 0.0f

    override fun process(input: ShortArray, channelCount: Int): ShortArray {
        if (gainDb == 0.0f) return input

        // Convert dB to linear multiplier
        val multiplier = dbToLinear(gainDb)
        if (multiplier == 1.0f) return input

        for (i in input.indices) {
            val sample = input[i].toInt()
            val amplified = (sample * multiplier).toInt()
            input[i] = amplified.coerceIn(-32768, 32767).toShort()
        }
        return input
    }

    private fun dbToLinear(db: Float): Float {
        return 10.0f.pow(db / 20.0f)
    }

    override fun reset() {
    }

    override fun release() {
    }
}
