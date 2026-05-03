package com.lanrhyme.micyou.audio

import kotlin.math.min
import kotlin.math.sqrt

/**
 * 声学回声消除 (AEC) 效果器
 * 使用 NLMS (Normalized Least Mean Squares) 自适应滤波器
 */
class AecEffect : AudioEffect {

    /** 是否启用 AEC */
    var enabled: Boolean = false

    /** 自适应滤波器抽头数 */
    var filterLength: Int = DEFAULT_FILTER_LENGTH

    /** NLMS 步长因子 (0 < mu <= 1) */
    var mu: Float = 0.4f

    /** 泄漏因子 */
    var leakage: Float = 0.9999f

    /** 归一化常数 */
    private val delta: Float = 1e-5f

    // 滤波器权重
    private var weights: FloatArray = FloatArray(filterLength)
    
    // 自适应滤波器的输入向量 (最新采样在最后)
    private var x: FloatArray = FloatArray(filterLength)

    // 参考信号环形缓冲区 (存放从 PC 收到的音频，始终为 Mono)
    private val ringBufferSize = 96000 // 约 2 秒 (48kHz)
    private var ringBuffer = FloatArray(ringBufferSize)
    private var writePos = 0
    private var readPos = 0
    private var availableSamples = 0

    // 延迟对齐相关
    private var delayMatched = false
    private var bestDelaySamples = 0
    private val maxDelaySamples = 19200 // 最大搜寻 400ms (48kHz)
    private var searchCounter = 0

    // 统计数据
    private var processedFrames = 0
    private var lastErleLogTime = 0L
    private var currentErle = 0f

    companion object {
        const val DEFAULT_FILTER_LENGTH = 1024
    }

    /**
     * 添加参考信号到缓冲区
     */
    fun setReferenceSignal(loopbackSamples: ShortArray, timestamp: Long = 0, channelCount: Int = 1) {
        if (!enabled) return
        
        synchronized(this) {
            val frames = loopbackSamples.size / channelCount
            for (i in 0 until frames) {
                var sum = 0f
                for (c in 0 until channelCount) {
                    sum += loopbackSamples[i * channelCount + c].toFloat() / 32768f
                }
                val monoSample = sum / channelCount
                
                ringBuffer[writePos] = monoSample
                writePos = (writePos + 1) % ringBufferSize
                if (availableSamples < ringBufferSize) {
                    availableSamples++
                } else {
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
            // 自动延迟匹配：如果当前消除效果已经很好 (ERLE > 8dB)，则降低搜索频率以避免抖动
            val searchInterval = if (currentErle > 8f) 2000 else 500
            if (!delayMatched || searchCounter++ > searchInterval) {
                findBestDelay(input, channelCount)
                searchCounter = 0
            }

            // 如果参考信号不足，透传
            if (availableSamples < framesToProcess + filterLength) {
                return input
            }

            for (frame in 0 until framesToProcess) {
                val sampleIndex = frame * channelCount
                val micSample = input[sampleIndex].toFloat() / 32768f
                micEnergySum += micSample * micSample

                // 取一个参考采样
                val refSample = ringBuffer[readPos]
                readPos = (readPos + 1) % ringBufferSize
                availableSamples--

                System.arraycopy(x, 1, x, 0, filterLength - 1)
                x[filterLength - 1] = refSample

                var echoEst = 0f
                for (i in 0 until filterLength) {
                    echoEst += weights[i] * x[i]
                }

                val error = micSample - echoEst
                errorEnergySum += error * error

                var xEnergy = 0f
                for (i in 0 until filterLength) {
                    xEnergy += x[i] * x[i]
                }

                val normFactor = mu / (xEnergy + delta)
                for (i in 0 until filterLength) {
                    weights[i] = (weights[i] * leakage) + (normFactor * error * x[i])
                    if (weights[i] > 1.0f) weights[i] = 1.0f
                    else if (weights[i] < -1.0f) weights[i] = -1.0f
                }

                val errorShort = (error * 32767f).toInt().coerceIn(-32768, 32767).toShort()
                for (ch in 0 until channelCount) {
                    output[sampleIndex + ch] = errorShort
                }
            }
        }

        // 定期监控
        processedFrames++
        val now = currentTimeMillis()
        
        // 更新当前 ERLE 估算值
        if (errorEnergySum > 0 && micEnergySum > 0) {
            val instantErle = 10 * kotlin.math.log10(micEnergySum / (errorEnergySum + 1e-10f))
            // 简单的一阶低通滤波平滑 ERLE
            currentErle = currentErle * 0.9f + instantErle * 0.1f
        }

        if (now - lastErleLogTime > 2000) {
            if (micEnergySum > 0.001f) {
                com.lanrhyme.micyou.Logger.d("AecEffect", "AEC Stats: ERLE=${currentErle.toInt()}dB, DelayMatch=$bestDelaySamples samples, RefBuf=$availableSamples")
            }
            lastErleLogTime = now
        }

        return output
    }

    /**
     * 自动寻找最佳延迟偏移
     * 通过简单的互相关搜索对齐参考信号 and 麦克风信号
     */
    private fun findBestDelay(micInput: ShortArray, channelCount: Int) {
        if (availableSamples < maxDelaySamples) return
        
        // 抽取部分样本进行匹配
        val testLen = 256
        val micNorm = FloatArray(testLen)
        for (i in 0 until testLen) {
            micNorm[i] = micInput[min(i * channelCount, micInput.size - 1)].toFloat() / 32768f
        }

        var maxCorr = -1f
        var bestOffset = 0

        // 在缓冲区中滑动搜索匹配度最高的点 (每 32 个采样步进一次以节省 CPU)
        for (offset in 0 until (availableSamples - testLen) step 32) {
            if (offset > maxDelaySamples) break
            
            var corr = 0f
            for (i in 0 until testLen) {
                val refIdx = (readPos + offset + i) % ringBufferSize
                corr += micNorm[i] * ringBuffer[refIdx]
            }
            
            if (corr > maxCorr) {
                maxCorr = corr
                bestOffset = offset
            }
        }

        // 如果找到了显著的匹配点
        if (maxCorr > 0.1f) {
            readPos = (readPos + bestOffset) % ringBufferSize
            availableSamples -= bestOffset
            bestDelaySamples = bestOffset
            delayMatched = true
        }
    }

    private fun currentTimeMillis(): Long = System.currentTimeMillis()

    override fun reset() {
        synchronized(this) {
            weights = FloatArray(filterLength)
            x = FloatArray(filterLength)
            writePos = 0
            readPos = 0
            availableSamples = 0
            delayMatched = false
            currentErle = 0f
        }
    }

    override fun release() {
        reset()
    }
}
