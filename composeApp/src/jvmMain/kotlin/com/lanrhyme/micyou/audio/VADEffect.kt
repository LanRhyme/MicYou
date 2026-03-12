package com.lanrhyme.micyou.audio

import kotlin.math.sqrt

// 语音活动检测 (VAD) 效果器，当未检测到语音时将音频静音
class VADEffect : AudioEffect {
    // 是否启用 VAD
    var enableVAD: Boolean = false
    // 灵敏度阈值 (0-100)
    var vadThreshold: Int = 10
    // 当前帧包含语音的概率 (由降噪模块提供)
    var speechProbability: Float? = null

    override fun process(input: ShortArray, channelCount: Int): ShortArray {
        if (!enableVAD) return input

        // 计算所需置信度 (阈值越高，需要的置信度越低？反之亦然？)
        // 阈值 0 -> 灵敏度 0 -> 需要 1.0 置信度 (几乎不可能触发)
        // 阈值 100 -> 灵敏度 1.0 -> 需要 0.0 置信度 (总是触发)
        val sensitivity = vadThreshold.coerceIn(0, 100) / 100f
        val requiredConfidence = 1f - sensitivity
        
        // 检查是否有语音
        val speech = speechProbability?.let { it >= requiredConfidence } ?: run {
            // 如果没有外部提供的概率，使用 RMS 估算
            var sum = 0.0
            for (s in input) {
                val n = s.toDouble() / 32768.0
                sum += n * n
            }
            val rms = if (input.isNotEmpty()) sqrt(sum / input.size.toDouble()).toFloat() else 0f
            rms >= (requiredConfidence * 0.12f) // 0.12 是一个经验值
        }
        
        // 如果是静音，将缓冲区置零
        if (!speech) {
            for (i in input.indices) {
                input[i] = 0
            }
        }
        return input
    }

    override fun reset() {
        speechProbability = null
    }

    override fun release() {
        reset()
    }
}
