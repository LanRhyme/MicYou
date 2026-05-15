package com.lanrhyme.micyou.audio

import org.jtransforms.fft.FloatFFT_1D
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * 音频频谱分析器
 * 使用 FFT 将时域信号转换为频域信号
 */
class AudioSpectrumAnalyzer(val fftSize: Int = 1024) {
    private val fft = FloatFFT_1D(fftSize.toLong())
    private val fftBuffer = FloatArray(fftSize * 2) // 复数数组: [real, imag, real, imag, ...]
    private val magnitudeBuffer = FloatArray(fftSize / 2)
    private val window = FloatArray(fftSize) { i ->
        // 汉宁窗 (Hanning Window) 以减少频谱泄漏
        (0.5 * (1.0 - kotlin.math.cos(2.0 * kotlin.math.PI * i / (fftSize - 1)))).toFloat()
    }

    /**
     * 计算音频数据的频谱
     * @param input 16-bit PCM 音频采样
     * @return 频率幅值数组 (线性或对数比例)
     */
    fun calculateSpectrum(input: ShortArray): FloatArray {
        if (input.isEmpty()) return FloatArray(fftSize / 2)

        // 1. 准备数据：加窗并转换为浮点
        val size = minOf(input.size, fftSize)
        for (i in 0 until fftSize) {
            if (i < size) {
                fftBuffer[i] = (input[i].toFloat() / 32768f) * window[i]
            } else {
                fftBuffer[i] = 0f
            }
        }

        // 2. 执行实数 FFT
        // 对于 realForward，输入数组的前 fftSize 个元素会被替换为 FFT 结果
        // 结果格式为：[Re[0], Re[n/2], Re[1], Im[1], Re[2], Im[2], ..., Re[n/2-1], Im[n/2-1]]
        fft.realForward(fftBuffer)

        // 3. 计算幅值 (Magnitude)
        // DC 分量
        magnitudeBuffer[0] = Math.abs(fftBuffer[0])
        
        for (i in 1 until fftSize / 2) {
            val re = fftBuffer[2 * i]
            val im = fftBuffer[2 * i + 1]
            magnitudeBuffer[i] = sqrt(re * re + im * im)
        }

        // 归一化并转换为对数刻度 (可选，这里先返回线性幅值，UI层可以再处理)
        return magnitudeBuffer.copyOf()
    }

    /**
     * 计算音频数据的频谱 (直接从 16-bit PCM 字节数组计算，避免不必要的类型转换)
     * @param input 16-bit PCM 字节数组 (小端序)
     * @return 频率幅值数组
     */
    fun calculateSpectrumFromBytes(input: ByteArray): FloatArray {
        if (input.isEmpty()) return FloatArray(fftSize / 2)

        val size = minOf(input.size / 2, fftSize)
        for (i in 0 until fftSize) {
            if (i < size) {
                val byteIndex = i * 2
                val sample = (input[byteIndex].toInt() and 0xFF) or ((input[byteIndex + 1].toInt()) shl 8)
                fftBuffer[i] = (sample.toShort().toFloat() / 32768f) * window[i]
            } else {
                fftBuffer[i] = 0f
            }
        }

        fft.realForward(fftBuffer)
        magnitudeBuffer[0] = Math.abs(fftBuffer[0])
        
        for (i in 1 until fftSize / 2) {
            val re = fftBuffer[2 * i]
            val im = fftBuffer[2 * i + 1]
            magnitudeBuffer[i] = sqrt(re * re + im * im)
        }

        return magnitudeBuffer.copyOf()
    }
}
