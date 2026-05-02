package com.lanrhyme.micyou.audio

import kotlin.math.abs
import kotlin.math.min

/**
 * 声学回声消除 (AEC) 效果器
 * 使用 NLMS (Normalized Least Mean Squares) 自适应滤波器
 * 将 PC 端回环音频作为参考信号，从麦克风信号中消除回声
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

    // 输入缓冲区（麦克风信号）
    private var inputBuffer: FloatArray = FloatArray(filterLength)

    // 回声估计缓冲区
    private var echoEstimate: ShortArray = ShortArray(0)

    // 采样率（用于计算缓冲区大小）
    private var sampleRate: Int = 44100

    // 时间戳对齐缓冲区
    private var refTimestamp: Long = 0
    private var micTimestamp: Long = 0

    companion object {
        const val DEFAULT_FILTER_LENGTH = 256
    }

    /**
     * 设置回环音频参考信号
     * @param loopbackSamples PC 端回环音频采样
     * @param timestamp 回环音频时间戳（毫秒）
     */
    fun setReferenceSignal(loopbackSamples: ShortArray, timestamp: Long = 0) {
        refTimestamp = timestamp
        // 将参考信号写入循环缓冲区
        val newRefBuffer = FloatArray(filterLength)
        val copyLen = min(loopbackSamples.size, filterLength)
        for (i in 0 until copyLen) {
            newRefBuffer[filterLength - copyLen + i] = loopbackSamples[i].toFloat() / 32768f
        }
        refBuffer = newRefBuffer
    }

    override fun process(input: ShortArray, channelCount: Int): ShortArray {
        if (!enabled || input.isEmpty()) return input

        // 如果没有参考信号（无回环音频），直接返回
        if (refBuffer.all { it == 0f }) return input

        val output = ShortArray(input.size)

        // 处理每个采样（单声道处理，多声道按帧处理）
        val framesToProcess = input.size / channelCount

        for (frame in 0 until framesToProcess) {
            val sampleIndex = frame * channelCount

            // 读取当前麦克风采样（取第一个声道）
            val micSample = input[sampleIndex].toFloat() / 32768f

            // 计算回声估计
            var echoEst = 0f
            for (i in 0 until filterLength) {
                echoEst += weights[i] * refBuffer[i]
            }

            // 误差信号 = 麦克风信号 - 回声估计
            val error = micSample - echoEst

            // NLMS 更新滤波器权重
            // 计算参考信号能量
            var refEnergy = 0f
            for (i in 0 until filterLength) {
                refEnergy += refBuffer[i] * refBuffer[i]
            }

            val normFactor = mu / (refEnergy + delta)

            for (i in 0 until filterLength) {
                weights[i] = weights[i] * leakage + normFactor * error * refBuffer[i]
                // 限制权重范围，防止发散
                weights[i] = weights[i].coerceIn(-1f, 1f)
            }

            // 移动参考信号缓冲区
            for (i in 0 until filterLength - 1) {
                refBuffer[i] = refBuffer[i + 1]
            }

            // 将误差信号写入输出
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
        inputBuffer = FloatArray(filterLength)
        refTimestamp = 0
        micTimestamp = 0
    }

    override fun release() {
        reset()
    }
}
