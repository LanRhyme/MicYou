package com.lanrhyme.micyou.audio

import kotlin.math.min

/**
 * 声学回声消除 (AEC) 效果器
 * 使用 NLMS (Normalized Least Mean Squares) 自适应滤波器
 * 将 PC 端回环音频作为参考信号，从麦克风信号中消除回声
 *
 * 可在 commonMain 中使用，同时支持 Android 和 JVM 平台
 */
class AecEffect : AudioEffect {

    /** 是否启用 AEC */
    var enabled: Boolean = false

    /** 自适应滤波器抽头数（越大能消除的回声延迟越长，但计算量越大） */
    var filterLength: Int = DEFAULT_FILTER_LENGTH

    /** NLMS 步长因子 (0 < mu <= 1)，控制收敛速度 */
    var mu: Float = 0.5f

    /** 泄漏因子，防止滤波器发散 */
    var leakage: Float = 0.9999f

    /** 归一化常数，防止除以零 */
    private val delta: Float = 1e-6f

    // 自适应滤波器权重
    private var weights: FloatArray = FloatArray(filterLength)

    // 参考信号缓冲区（PC 回环音频）
    private var refBuffer: FloatArray = FloatArray(filterLength)

    // 时间戳对齐缓冲区
    private var refTimestamp: Long = 0
    private var micTimestamp: Long = 0

    companion object {
        const val DEFAULT_FILTER_LENGTH = 1024
    }

    /**
     * 设置回环音频参考信号
     * @param loopbackSamples PC 端回环音频采样
     * @param timestamp 回环音频时间戳（毫秒）
     */
    fun setReferenceSignal(loopbackSamples: ShortArray, timestamp: Long = 0) {
        refTimestamp = timestamp
        val newRefBuffer = FloatArray(filterLength)
        val copyLen = min(loopbackSamples.size, filterLength)
        for (i in 0 until copyLen) {
            newRefBuffer[filterLength - copyLen + i] = loopbackSamples[i].toFloat() / 32768f
        }
        refBuffer = newRefBuffer
    }

    /**
     * 处理音频采样，消除回声
     * @param input 输入采样（ShortArray 格式）
     * @param channelCount 声道数
     * @return 处理后的采样
     */
    override fun process(input: ShortArray, channelCount: Int): ShortArray {
        if (!enabled || input.isEmpty()) return input
        if (refBuffer.all { it == 0f }) return input

        val output = ShortArray(input.size)
        val framesToProcess = input.size / channelCount

        for (frame in 0 until framesToProcess) {
            val sampleIndex = frame * channelCount
            val micSample = input[sampleIndex].toFloat() / 32768f

            var echoEst = 0f
            for (i in 0 until filterLength) {
                echoEst += weights[i] * refBuffer[i]
            }

            val error = micSample - echoEst

            var refEnergy = 0f
            for (i in 0 until filterLength) {
                refEnergy += refBuffer[i] * refBuffer[i]
            }

            val normFactor = mu / (refEnergy + delta)

            for (i in 0 until filterLength) {
                weights[i] = weights[i] * leakage + normFactor * error * refBuffer[i]
                weights[i] = weights[i].coerceIn(-1f, 1f)
            }

            for (i in 0 until filterLength - 1) {
                refBuffer[i] = refBuffer[i + 1]
            }

            val errorShort = (error * 32768f).toInt().coerceIn(-32768, 32767).toShort()
            for (ch in 0 until channelCount) {
                output[sampleIndex + ch] = errorShort
            }
        }

        return output
    }

    override fun reset() {
        weights = FloatArray(filterLength)
        refBuffer = FloatArray(filterLength)
        refTimestamp = 0
        micTimestamp = 0
    }

    override fun release() {
        reset()
    }
}
