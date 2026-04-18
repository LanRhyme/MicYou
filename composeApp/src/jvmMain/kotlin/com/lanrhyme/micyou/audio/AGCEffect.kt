package com.lanrhyme.micyou.audio

import kotlin.math.sqrt

/**
 * 自动增益控制 (AGC) 效果器
 * 自动调整音频音量以保持一致的输出水平
 *
 * AGC 平滑系数说明：
 * - DECAY_RATE (0.005): 快速降低增益，防止声音突然增大造成刺耳
 * - ATTACK_RATE (0.01): 缓慢增加增益，平滑提升避免突变
 * - SILENT_RECOVERY_RATE (0.001): 静音时缓慢恢复到默认增益
 */
class AGCEffect : AudioEffect {

    companion object {
        // AGC 平滑系数常量
        /** 快速衰减率：当需要降低增益时使用，防止声音突增 */
        private const val AGC_DECAY_RATE = 0.005f
        /** 慢速增长率：当需要提升增益时使用，平滑提升 */
        private const val AGC_ATTACK_RATE = 0.01f
        /** 静音恢复率：静音时缓慢恢复到默认增益 1.0 */
        private const val AGC_SILENT_RECOVERY_RATE = 0.001f

        // 增益范围限制
        /** 最小增益倍数，防止过度衰减 */
        private const val MIN_GAIN_LIMIT = 0.5f
        /** 最大增益倍数，防止过度放大产生削波失真 */
        private const val MAX_GAIN_LIMIT = 3.0f
        /** 最终输出的最小增益 */
        private const val MIN_OUTPUT_GAIN = 0.8f
        /** 最终输出的最大增益，限制在安全范围内 */
        private const val MAX_OUTPUT_GAIN = 3.0f

        // RMS 相关常量
        /** RMS 最小阈值，低于此值视为静音 */
        private const val RMS_MIN_THRESHOLD = 0.001f
        /** 目标 RMS 最小值（标准化后） */
        private const val TARGET_RMS_MIN = 0.01f
        /** 目标 RMS 最大值（标准化后） */
        private const val TARGET_RMS_MAX = 0.9f
        /** 增益计算时的微小偏移，防止除零 */
        private const val EPSILON = 1e-6f
        /** 初始增益包络值 */
        private const val INITIAL_ENVELOPE = 1.0f

        // 数值常量
        /** Short 值范围的最大值 */
        private const val SHORT_MAX_VALUE = 32768.0f
        /** 静音恢复时的目标增益 */
        private const val SILENT_TARGET_GAIN = 1.0f
    }

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
            val sample = s.toDouble() / SHORT_MAX_VALUE
            sumSquares += sample * sample
        }
        val rms = sqrt(sumSquares / input.size.toDouble())

        // 目标 RMS (标准化到 0.0-1.0)
        val targetRms = (agcTargetLevel.toDouble() / SHORT_MAX_VALUE.toDouble()).coerceIn(TARGET_RMS_MIN.toDouble(), TARGET_RMS_MAX.toDouble())

        if (rms > RMS_MIN_THRESHOLD) {
            val error = targetRms / (rms + EPSILON)
            val desiredGain = error.toFloat().coerceIn(MIN_GAIN_LIMIT, MAX_GAIN_LIMIT)

            if (agcEnvelope == 0f) {
                agcEnvelope = INITIAL_ENVELOPE
            }

            // 平滑增益变化：降低快、增加慢
            val smoothing = if (desiredGain < agcEnvelope) {
                AGC_DECAY_RATE  // 快速降低，防止声音突增
            } else {
                AGC_ATTACK_RATE  // 缓慢增加，平滑提升
            }
            agcEnvelope = agcEnvelope * (1f - smoothing) + desiredGain * smoothing
        } else {
            // 静音时缓慢恢复到默认增益
            agcEnvelope = agcEnvelope * (1f - AGC_SILENT_RECOVERY_RATE) + SILENT_TARGET_GAIN * AGC_SILENT_RECOVERY_RATE
        }

        val finalGain = agcEnvelope.coerceIn(MIN_OUTPUT_GAIN, MAX_OUTPUT_GAIN)

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
