package com.lanrhyme.micyou.audio

import ai.onnxruntime.*
import com.lanrhyme.micyou.Logger
import org.jtransforms.fft.FloatFFT_1D
import java.io.File
import java.nio.FloatBuffer
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sqrt

class UlunasProcessor(
    modelPath: String,
    private val frameSize: Int = 960,
    private val hopLength: Int = 480
) {
    private val window: FloatArray = hanningWindow(frameSize)
    private val olaGainCompensation: Float = calculateOlaGainCompensation()
    private val previous: FloatArray = FloatArray(hopLength)
    
    private val fft: FloatFFT_1D = FloatFFT_1D(frameSize.toLong())
    private val fftBuffer: FloatArray = FloatArray(frameSize)
    private val modelInput: FloatArray = FloatArray((frameSize / 2 + 1) * 2)
    
    private val olaAccumulator: FloatArray = FloatArray(frameSize)
    private val outputFrame: FloatArray = FloatArray(hopLength)
    
    private var outputBuffer: FloatArray = FloatArray((frameSize / 2 + 1) * 2)
    private val stateBuffer: FloatArray = FloatArray(242)
    
    private var env: OrtEnvironment? = null
    private var session: OrtSession? = null
    private var inputNames: List<String> = emptyList()
    private var outputNames: List<String> = emptyList()
    
    private val states: Array<FloatArray> = Array(18) { i -> createStateArray(i) }
    
    private var destroyed = false
    
    init {
        initModel(modelPath)
    }
    
    private fun hanningWindow(size: Int): FloatArray {
        return FloatArray(size) { i ->
            sqrt(0.5 - 0.5 * cos(2.0 * PI * i / (size - 1))).toFloat()
        }
    }
    
    /**
     * 计算 OLA (Overlap-Add) 增益补偿因子。
     * 
     * 当使用 sqrt-Hanning 窗进行分析和合成时（双重加窗），
     * 在 50% 重叠的情况下，窗函数平方的和不等于 1.0，
     * 这会导致音量衰减。此函数计算补偿因子以恢复原始音量。
     */
    private fun calculateOlaGainCompensation(): Float {
        // 计算窗函数平方在重叠区域的和
        var sumSquared = 0.0f
        for (i in 0 until hopLength) {
            // 在 50% 重叠时，每个样本会被两个窗口覆盖
            // 位置 i 被当前帧的 window[i] 和前一帧的 window[i + hopLength] 覆盖
            val w1 = window[i]
            val w2 = window[i + hopLength]
            sumSquared += w1 * w1 + w2 * w2
        }
        val avgGain = sumSquared / hopLength
        // 返回补偿因子：1 / sqrt(avgGain) 用于补偿双重加窗的衰减
        return if (avgGain > 0.001f) 1.0f / sqrt(avgGain) else 1.0f
    }
    
    private fun initModel(modelPath: String) {
        try {
            env = OrtEnvironment.getEnvironment()
            val options = OrtSession.SessionOptions()
            options.setIntraOpNumThreads(1)
            options.setInterOpNumThreads(1)
            options.setExecutionMode(OrtSession.SessionOptions.ExecutionMode.SEQUENTIAL)
            options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.BASIC_OPT)
            
            val modelFile = File(modelPath)
            if (!modelFile.exists()) {
                Logger.e("UlunasProcessor", "Model file not found: $modelPath")
                return
            }
            
            session = env?.createSession(modelPath, options)
            inputNames = session?.inputNames?.toList() ?: emptyList()
            outputNames = session?.outputNames?.toList() ?: emptyList()
            
            Logger.i("UlunasProcessor", "Model loaded: $modelPath")
        } catch (e: Exception) {
            Logger.e("UlunasProcessor", "Failed to load model: ${e.message}", e)
        }
    }
    
    private fun createStateArray(index: Int): FloatArray {
        return when (index) {
            0 -> FloatArray(1 * 1 * 2 * 121)
            1 -> FloatArray(1 * 24 * 1 * 61)
            2 -> FloatArray(1 * 24 * 1 * 31)
            3 -> FloatArray(1 * 1 * 24)
            4 -> FloatArray(1 * 1 * 48)
            5 -> FloatArray(1 * 1 * 48)
            6 -> FloatArray(1 * 1 * 64)
            7 -> FloatArray(1 * 1 * 32)
            8 -> FloatArray(1 * 31 * 16)
            9 -> FloatArray(1 * 31 * 16)
            10 -> FloatArray(1 * 24 * 1 * 31)
            11 -> FloatArray(1 * 12 * 1 * 31)
            12 -> FloatArray(1 * 12 * 2 * 61)
            13 -> FloatArray(1 * 1 * 64)
            14 -> FloatArray(1 * 1 * 48)
            15 -> FloatArray(1 * 1 * 48)
            16 -> FloatArray(1 * 1 * 24)
            17 -> FloatArray(1 * 1 * 2)
            else -> FloatArray(0)
        }
    }
    
    fun process(input: FloatArray): FloatArray {
        if (destroyed || session == null || input.size != hopLength) {
            return input
        }
        return noiseReduction(input)
    }
    
    private fun noiseReduction(audioChunk: FloatArray): FloatArray {
        if (session == null) return audioChunk
        
        // 构建帧：前一帧的后半部分 + 当前帧
        System.arraycopy(previous, 0, fftBuffer, 0, hopLength)
        System.arraycopy(audioChunk, 0, fftBuffer, hopLength, hopLength)
        System.arraycopy(audioChunk, 0, previous, 0, hopLength)
        
        // 加窗
        for (i in 0 until frameSize) {
            fftBuffer[i] *= window[i]
        }
        
        // FFT 正变换
        fft.realForward(fftBuffer)
        
        // 将 JTransforms FFT 输出转换为模型输入格式
        // JTransforms realForward 输出格式：
        // fftBuffer[0] = DC (实数)
        // fftBuffer[1] = Nyquist (实数，当 n 为偶数时)
        // fftBuffer[2*i] = 实部, fftBuffer[2*i+1] = 虚部 (i=1..n/2-1)
        
        val specSize = frameSize / 2 + 1
        
        // 转换为 [batch, freq_bins, 1, 2] 格式 (实部, 虚部)
        // modelInput 布局: [freq_bins][real, imag]
        
        // DC 分量
        modelInput[0] = fftBuffer[0]  // DC 实部
        modelInput[1] = 0f            // DC 虚部
        
        // 中间频率
        for (i in 1 until specSize - 1) {
            val srcIdx = 2 * i
            val dstIdx = 2 * i
            modelInput[dstIdx] = fftBuffer[srcIdx]      // 实部
            modelInput[dstIdx + 1] = fftBuffer[srcIdx + 1]  // 虚部
        }
        
        // Nyquist 频率
        if (frameSize % 2 == 0) {
            modelInput[2 * (specSize - 1)] = fftBuffer[1]  // Nyquist 实部
            modelInput[2 * (specSize - 1) + 1] = 0f        // Nyquist 虚部
        }
        
        try {
            val inputTensors = mutableListOf<OnnxTensor>()
            
            try {
                // 频谱输入 [1, freq_bins, 1, 2]
                val specShape = longArrayOf(1, specSize.toLong(), 1, 2)
                inputTensors.add(OnnxTensor.createTensor(env, FloatBuffer.wrap(modelInput), specShape))
                
                // 状态输入
                val stateShapes = listOf(
                    longArrayOf(1, 1, 2, 121), longArrayOf(1, 24, 1, 61), longArrayOf(1, 24, 1, 31),
                    longArrayOf(1, 1, 24), longArrayOf(1, 1, 48), longArrayOf(1, 1, 48),
                    longArrayOf(1, 1, 64), longArrayOf(1, 1, 32), longArrayOf(1, 31, 16),
                    longArrayOf(1, 31, 16), longArrayOf(1, 24, 1, 31), longArrayOf(1, 12, 1, 31),
                    longArrayOf(1, 12, 2, 61), longArrayOf(1, 1, 64), longArrayOf(1, 1, 48),
                    longArrayOf(1, 1, 48), longArrayOf(1, 1, 24), longArrayOf(1, 1, 2)
                )
                
                for (i in 0 until 18) {
                    inputTensors.add(OnnxTensor.createTensor(env, FloatBuffer.wrap(states[i]), stateShapes[i]))
                }
                
                // 构建输入映射
                val inputMap = mutableMapOf<String, OnnxTensor>()
                inputNames.forEachIndexed { index, name ->
                    if (index < inputTensors.size) {
                        inputMap[name] = inputTensors[index]
                    }
                }
                
                // 运行推理
                val results = session?.run(inputMap)
                
                if (results != null) {
                    try {
                        // 获取输出频谱 [1, freq_bins, 1, 2]
                        val outputTensor = results.get(0) as? OnnxTensor
                        val outputData = outputTensor?.floatBuffer
                        
                        if (outputData != null) {
                            val outputSize = outputData.remaining()
                            if (outputBuffer.size < outputSize) {
                                outputBuffer = FloatArray(outputSize)
                            }
                            outputData.get(outputBuffer, 0, outputSize)
                            
                            // 将模型输出转换回 JTransforms FFT 格式
                            val outputSpecSize = outputSize / 2
                            
                            // DC 分量
                            fftBuffer[0] = outputBuffer[0]
                            
                            // Nyquist 频率 (当 frameSize 为偶数时)
                            if (frameSize % 2 == 0 && outputSpecSize > 1) {
                                fftBuffer[1] = outputBuffer[2 * (outputSpecSize - 1)]
                            }
                            
                            // 中间频率
                            for (i in 1 until minOf(outputSpecSize - 1, specSize - 1)) {
                                val srcIdx = 2 * i
                                val dstIdx = 2 * i
                                fftBuffer[dstIdx] = outputBuffer[srcIdx]
                                fftBuffer[dstIdx + 1] = outputBuffer[srcIdx + 1]
                            }
                        }
                        
                        // 更新状态 - 输出 1-18 是更新后的状态
                        for (i in 1 until minOf(outputNames.size, 19)) {
                            val stateTensor = results.get(i) as? OnnxTensor
                            if (stateTensor != null) {
                                val stateData = stateTensor.floatBuffer
                                if (stateData != null) {
                                    val stateSize = stateData.remaining()
                                    stateData.get(stateBuffer, 0, minOf(stateSize, stateBuffer.size))
                                    if (i - 1 < states.size && stateSize == states[i - 1].size) {
                                        System.arraycopy(stateBuffer, 0, states[i - 1], 0, stateSize)
                                    }
                                }
                            }
                        }
                    } finally {
                        results.close()
                    }
                }
            } finally {
                // 确保所有输入张量都被关闭
                inputTensors.forEach { it.close() }
            }
            
        } catch (e: Exception) {
            Logger.e("UlunasProcessor", "ONNX inference failed: ${e.message}", e)
            return audioChunk
        }
        
        // FFT 逆变换
        fft.realInverse(fftBuffer, true)
        
        // 加窗
        for (i in 0 until frameSize) {
            fftBuffer[i] *= window[i]
        }
        
        // OLA: 累加到输出缓冲区
        for (i in 0 until frameSize) {
            olaAccumulator[i] += fftBuffer[i]
        }
        
        // 提取输出帧 (前半部分)，并应用 OLA 增益补偿
        for (i in 0 until hopLength) {
            outputFrame[i] = olaAccumulator[i] * olaGainCompensation
        }
        
        // 移位：后半部分移到前面
        for (i in 0 until frameSize - hopLength) {
            olaAccumulator[i] = olaAccumulator[i + hopLength]
        }
        
        // 清零新位置
        for (i in frameSize - hopLength until frameSize) {
            olaAccumulator[i] = 0f
        }
        
        // 直接返回 outputFrame（调用者不应修改返回的数组）
        // 这样避免了每次调用都创建数组副本
        return outputFrame
    }
    
    fun destroy() {
        if (destroyed) return
        destroyed = true
        try {
            session?.close()
            session = null
        } catch (e: Exception) {
            Logger.e("UlunasProcessor", "Error closing session: ${e.message}")
        }
    }
    
    protected fun finalize() {
        destroy()
    }
}
