package com.lanrhyme.micyou.audio

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

    // 统计数据
    private var processedFrames = 0
    private var lastErleLogTime = 0L

    companion object {
        const val DEFAULT_FILTER_LENGTH = 1024 // 增大长度以覆盖更大的延迟
    }

    /**
     * 添加参考信号到缓冲区
     * @param loopbackSamples PC 端回环音频采样 (可能是多声道)
     * @param timestamp 时间戳
     * @param channelCount 参考信号的声道数 (通常为 2)
     */
    fun setReferenceSignal(loopbackSamples: ShortArray, timestamp: Long = 0, channelCount: Int = 1) {
        if (!enabled) return
        
        synchronized(this) {
            val frames = loopbackSamples.size / channelCount
            for (i in 0 until frames) {
                // 如果是多声道，取平均值转为单声道参考信号
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
                    // 缓冲区满，覆盖最旧的数据
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
            // 如果参考信号严重不足（可能是由于网络延迟或丢包），直接透传麦克风信号
            // 至少需要 filterLength 的参考数据才能开始滤波
            if (availableSamples < framesToProcess + filterLength) {
                return input
            }

            for (frame in 0 until framesToProcess) {
                val sampleIndex = frame * channelCount
                // 麦克风输入 (通常是单声道，取第一个)
                val micSample = input[sampleIndex].toFloat() / 32768f
                micEnergySum += micSample * micSample

                // 从环形队列取一个参考采样
                val refSample = ringBuffer[readPos]
                readPos = (readPos + 1) % ringBufferSize
                availableSamples--

                // 更新滤波器输入向量 x (移位寄存器模式)
                System.arraycopy(x, 1, x, 0, filterLength - 1)
                x[filterLength - 1] = refSample

                // 计算估计回声: y = w * x
                var echoEst = 0f
                for (i in 0 until filterLength) {
                    echoEst += weights[i] * x[i]
                }

                // 误差信号 (即消除回声后的信号): e = d - y
                val error = micSample - echoEst
                errorEnergySum += error * error

                // 更新权重 (NLMS 公式: w = w + mu * e * x / (|x|^2 + delta))
                var xEnergy = 0f
                for (i in 0 until filterLength) {
                    xEnergy += x[i] * x[i]
                }

                val normFactor = mu / (xEnergy + delta)
                for (i in 0 until filterLength) {
                    weights[i] = (weights[i] * leakage) + (normFactor * error * x[i])
                    // 削波防止滤波器发散
                    if (weights[i] > 1.0f) weights[i] = 1.0f
                    else if (weights[i] < -1.0f) weights[i] = -1.0f
                }

                // 生成处理后的采样
                val errorShort = (error * 32767f).toInt().coerceIn(-32768, 32767).toShort()
                for (ch in 0 until channelCount) {
                    output[sampleIndex + ch] = errorShort
                }
            }
        }

        // 定期监控 ERLE (回声消除比)
        processedFrames++
        val now = System.currentTimeMillis()
        if (now - lastErleLogTime > 2000) {
            val erle = if (errorEnergySum > 0 && micEnergySum > 0) {
                10 * kotlin.math.log10(micEnergySum / (errorEnergySum + 1e-10f))
            } else 0f
            // 只在有信号时打印，减少日志噪音
            if (micEnergySum > 0.001f) {
                com.lanrhyme.micyou.Logger.d("AecEffect", "AEC Stats: ERLE=${erle.toInt()}dB, BufferedRef=$availableSamples, MicEnergy=${(micEnergySum * 100).toInt()}")
            }
            lastErleLogTime = now
        }

        return output
    }

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
