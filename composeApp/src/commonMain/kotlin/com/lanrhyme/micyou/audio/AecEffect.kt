package com.lanrhyme.micyou.audio

import kotlin.math.min
import kotlin.math.sqrt

/**
 * 声学回声消除 (AEC) 效果器
 * 使用 NLMS (Normalized Least Mean Squares) 自适应滤波器
 *
 * 改进版：引入环形缓冲区处理非同步的参考信号
 */
class AecEffect : AudioEffect {

    /** 是否启用 AEC */
    var enabled: Boolean = false

    /** 自适应滤波器抽头数 */
    var filterLength: Int = DEFAULT_FILTER_LENGTH

    /** NLMS 步长因子 (0 < mu <= 1) */
    var mu: Float = 0.4f

    /** 泄漏因子 */
    var leakage: Float = 0.999f

    /** 归一化常数 */
    private val delta: Float = 1e-5f

    // 滤波器权重
    private var weights: FloatArray = FloatArray(filterLength)
    
    // 自适应滤波器的输入向量 (最新采样在最后)
    private var x: FloatArray = FloatArray(filterLength)

    // 参考信号环形缓冲区 (存放从 PC 收到的音频)
    private val ringBufferSize = 48000 // 约 1 秒 (44.1kHz)
    private var ringBuffer = FloatArray(ringBufferSize)
    private var writePos = 0
    private var readPos = 0
    private var availableSamples = 0

    // 统计数据
    private var processedFrames = 0
    private var lastErleLogTime = 0L

    companion object {
        const val DEFAULT_FILTER_LENGTH = 512 // 减少长度以提高收敛速度
    }

    /**
     * 添加参考信号到缓冲区
     */
    fun setReferenceSignal(loopbackSamples: ShortArray, timestamp: Long = 0) {
        if (!enabled) return
        
        synchronized(this) {
            for (s in loopbackSamples) {
                ringBuffer[writePos] = s.toFloat() / 32768f
                writePos = (writePos + 1) % ringBufferSize
                if (availableSamples < ringBufferSize) {
                    availableSamples++
                } else {
                    // 溢出，强制移动读指针
                    readPos = (readPos + 1) % ringBufferSize
                }
            }
        }
    }

    override fun process(input: ShortArray, channelCount: Int): ShortArray {
        if (!enabled || input.isEmpty()) return input

        val output = ShortArray(input.size)
        val framesToProcess = input.size / channelCount
        
        var micEnergySum = 0f
        var errorEnergySum = 0f

        synchronized(this) {
            // 如果参考信号不足，直接透传
            if (availableSamples < filterLength) {
                return input
            }

            for (frame in 0 until framesToProcess) {
                val sampleIndex = frame * channelCount
                // 仅处理第一个声道 (Mono 滤波)
                val micSample = input[sampleIndex].toFloat() / 32768f
                micEnergySum += micSample * micSample

                // 从环形队列取一个参考采样
                val refSample = ringBuffer[readPos]
                readPos = (readPos + 1) % ringBufferSize
                availableSamples--

                // 更新滤波器输入向量 x
                // 将新采样移入，旧采样移出
                for (i in 0 until filterLength - 1) {
                    x[i] = x[i + 1]
                }
                x[filterLength - 1] = refSample

                // 计算估计回声
                var echoEst = 0f
                for (i in 0 until filterLength) {
                    echoEst += weights[i] * x[i]
                }

                // 残差信号 (即消除回声后的信号)
                val error = micSample - echoEst
                errorEnergySum += error * error

                // 更新权重 (NLMS)
                var xEnergy = 0f
                for (i in 0 until filterLength) {
                    xEnergy += x[i] * x[i]
                }

                val normFactor = mu / (xEnergy + delta)
                for (i in 0 until filterLength) {
                    weights[i] = weights[i] * leakage + normFactor * error * x[i]
                    // 限制权重范围，防止发散
                    if (weights[i] > 2f) weights[i] = 2f
                    if (weights[i] < -2f) weights[i] = -2f
                }

                // 生成输出
                val errorShort = (error * 32767f).toInt().coerceIn(-32768, 32767).toShort()
                for (ch in 0 until channelCount) {
                    output[sampleIndex + ch] = errorShort
                }
                
                if (availableSamples == 0) break // 参考信号耗尽
            }
        }

        // 定期打印 ERLE (回声消除增益) 诊断信息
        processedFrames++
        val now = currentTimeMillis()
        if (now - lastErleLogTime > 2000) {
            val erle = if (errorEnergySum > 0) 10 * kotlin.math.log10(micEnergySum / (errorEnergySum + 1e-10f)) else 0f
            Logger.d("AecEffect", "AEC Stats: ERLE=${erle.toInt()}dB, RingBuffer=$availableSamples, MicEnergy=${micEnergySum.toInt()}")
            lastErleLogTime = now
        }

        return output
    }

    private fun currentTimeMillis(): Long = System.currentTimeMillis()

    override fun reset() {
        synchronized(this) {
            weights = FloatArray(filterLength)
            x = FloatArray(filterLength)
            writePos = 0
            readPos = 0
            availableSamples = 0
        }
    }

    override fun release() {
        reset()
    }
}
