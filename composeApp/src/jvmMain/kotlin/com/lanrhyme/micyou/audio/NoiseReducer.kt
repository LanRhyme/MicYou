package com.lanrhyme.micyou.audio

import com.lanrhyme.micyou.Logger
import com.lanrhyme.micyou.NoiseReductionType
import de.maxhenkel.rnnoise4j.Denoiser
import java.io.IOException
import java.nio.file.Files

/**
 * 单例模型加载器，确保 Ulunas 模型只加载一次并缓存路径
 * 使用双重检查锁定模式实现线程安全的延迟初始化
 */
object UlunasModelLoader {
    @Volatile
    private var modelPath: String? = null
    private val lock = Any()

    /**
     * 获取 Ulunas 模型路径，延迟初始化且线程安全
     * 模型只会被复制一次到用户目录，后续直接使用缓存的路径
     */
    fun getModelPath(): String {
        // 第一次检查（无锁）
        modelPath?.let { path ->
            if (java.io.File(path).exists()) return path
        }

        // 双重检查锁定
        return synchronized(lock) {
            modelPath?.let { path ->
                if (java.io.File(path).exists()) return path
            }

            loadModelInternal()
        }
    }

    private fun loadModelInternal(): String {
        // 检查系统属性
        System.getProperty("micyou.ulunas.model.path")?.let { path ->
            Logger.i("UlunasModelLoader", "Using system property model path: $path")
            modelPath = path
            return path
        }

        // 使用用户目录下的固定位置
        val userDir = java.io.File(System.getProperty("user.home"), ".micyou")
        if (!userDir.exists()) {
            userDir.mkdirs()
        }
        val modelFile = java.io.File(userDir, "ulunas.onnx")

        // 如果已存在且有效，直接返回
        if (modelFile.exists() && modelFile.length() > 0) {
            Logger.i("UlunasModelLoader", "Using cached model: ${modelFile.absolutePath}")
            modelPath = modelFile.absolutePath
            return modelFile.absolutePath
        }

        // 从资源复制模型
        Logger.i("UlunasModelLoader", "Copying model from resources to: ${modelFile.absolutePath}")
        val classLoader = this.javaClass.classLoader
        val resourceStream = classLoader.getResourceAsStream("ulunas.onnx")
            ?: throw IOException("Unable to find Ulunas model file: ulunas.onnx")

        resourceStream.use { input ->
            modelFile.outputStream().use { output ->
                input.copyTo(output)
            }
        }

        Logger.i("UlunasModelLoader", "Model cached successfully, size: ${modelFile.length()}")
        modelPath = modelFile.absolutePath
        return modelFile.absolutePath
    }

    /**
     * 清除缓存的模型路径（用于测试或重新加载）
     */
    fun clearCache() {
        synchronized(lock) {
            modelPath = null
        }
    }
}

class NoiseReducer(
    private val frameSize: Int = 480
) : AudioEffect {

    var enableNS: Boolean = false
    var nsType: NoiseReductionType = NoiseReductionType.Ulunas

    // RNNoise - 延迟初始化
    private var denoiserLeft: Denoiser? = null
    private var denoiserRight: Denoiser? = null
    private var rnnoiseFrameLeft: ShortArray = ShortArray(0)
    private var rnnoiseFrameRight: ShortArray = ShortArray(0)

    // Ulunas - 延迟初始化
    private var ulunasProcessorLeft: UlunasProcessor? = null
    private var ulunasProcessorRight: UlunasProcessor? = null
    private var ulunasFrameLeft: FloatArray = FloatArray(0)
    private var ulunasFrameRight: FloatArray = FloatArray(0)

    var speechProbability: Float? = null
        private set

    override fun process(input: ShortArray, channelCount: Int): ShortArray {
        if (!enableNS || channelCount <= 0) return input
        
        when (nsType) {
            NoiseReductionType.RNNoise -> processRNNoise(input, channelCount)
            NoiseReductionType.Ulunas -> processUlunas(input, channelCount)
            else -> {}
        }
        return input
    }

    private fun processRNNoise(input: ShortArray, channelCount: Int) {
        if (denoiserLeft == null) denoiserLeft = Denoiser()
        if (channelCount >= 2 && denoiserRight == null) denoiserRight = Denoiser()

        val framesPerChannel = input.size / channelCount
        val frameCount = framesPerChannel / frameSize

        if (frameCount > 0 && (channelCount == 1 || channelCount == 2)) {
            if (rnnoiseFrameLeft.size != frameSize) rnnoiseFrameLeft = ShortArray(frameSize)
            if (channelCount == 2 && rnnoiseFrameRight.size != frameSize) rnnoiseFrameRight = ShortArray(frameSize)
            
            val left = rnnoiseFrameLeft
            val right = if (channelCount == 2) rnnoiseFrameRight else null
            
            val denoiserL = denoiserLeft ?: return
            val denoiserR = denoiserRight

            var probSum = 0f
            var probN = 0

            for (f in 0 until frameCount) {
                val base = f * frameSize * channelCount
                if (channelCount == 1) {
                    for (i in 0 until frameSize) {
                        left[i] = input[base + i]
                    }
                    val p = denoiserL.denoiseInPlace(left)
                    for (i in 0 until frameSize) {
                        input[base + i] = left[i]
                    }
                    probSum += p
                    probN++
                } else {
                    val rightArr = right ?: continue
                    val denoiserRightInst = denoiserR ?: continue
                    for (i in 0 until frameSize) {
                        val idx = base + i * 2
                        left[i] = input[idx]
                        rightArr[i] = input[idx + 1]
                    }
                    val pL = denoiserL.denoiseInPlace(left)
                    val pR = denoiserRightInst.denoiseInPlace(rightArr)
                    for (i in 0 until frameSize) {
                        val idx = base + i * 2
                        input[idx] = left[i]
                        input[idx + 1] = rightArr[i]
                    }
                    probSum += ((pL + pR) * 0.5f)
                    probN++
                }
            }

            if (probN > 0) {
                speechProbability = probSum / probN.toFloat()
            }
        }
    }

    private fun processUlunas(input: ShortArray, channelCount: Int) {
        try {
            // 使用单例模型加载器获取路径，避免重复检查
            val modelPath = UlunasModelLoader.getModelPath()

            val hopLength = frameSize

            if (ulunasProcessorLeft == null) {
                Logger.i("NoiseReducer", "Initializing Ulunas processor with cached model")
                ulunasProcessorLeft = UlunasProcessor(modelPath, 960, hopLength)
            }
            if (channelCount >= 2 && ulunasProcessorRight == null) {
                ulunasProcessorRight = UlunasProcessor(modelPath, 960, hopLength)
            }

            val framesPerChannel = input.size / channelCount
            val frameCount = framesPerChannel / hopLength

            if (frameCount > 0 && (channelCount == 1 || channelCount == 2)) {
                if (ulunasFrameLeft.size != hopLength) ulunasFrameLeft = FloatArray(hopLength)
                if (channelCount == 2 && ulunasFrameRight.size != hopLength) ulunasFrameRight = FloatArray(hopLength)
                
                val left = ulunasFrameLeft
                val right = if (channelCount == 2) ulunasFrameRight else null
                
                val processorL = ulunasProcessorLeft ?: return
                val processorR = ulunasProcessorRight

                for (f in 0 until frameCount) {
                    val base = f * hopLength * channelCount
                    if (channelCount == 1) {
                        for (i in 0 until hopLength) {
                            left[i] = input[base + i] / 32768.0f
                        }
                        val processedLeft = processorL.process(left)
                        for (i in 0 until hopLength) {
                            // 使用 32768.0f 保持对称范围，避免量化误差
                            input[base + i] = (processedLeft[i] * 32768.0f).toInt().coerceIn(-32768, 32767).toShort()
                        }
                    } else {
                        val rightArr = right ?: continue
                        val processorRightInst = processorR ?: continue
                        for (i in 0 until hopLength) {
                            val idx = base + i * 2
                            left[i] = input[idx] / 32768.0f
                            rightArr[i] = input[idx + 1] / 32768.0f
                        }
                        val processedLeft = processorL.process(left)
                        val processedRight = processorRightInst.process(rightArr)
                        for (i in 0 until hopLength) {
                            val idx = base + i * 2
                            // 使用 32768.0f 保持对称范围，避免量化误差
                            input[idx] = (processedLeft[i] * 32768.0f).toInt().coerceIn(-32768, 32767).toShort()
                            input[idx + 1] = (processedRight[i] * 32768.0f).toInt().coerceIn(-32768, 32767).toShort()
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Logger.e("NoiseReducer", "Ulunas processing failed: ${e.message}", e)
            enableNS = false
        }
    }

    override fun reset() {
    }

    override fun release() {
        try {
            denoiserLeft?.close()
            denoiserLeft = null
            denoiserRight?.close()
            denoiserRight = null
            ulunasProcessorLeft?.destroy()
            ulunasProcessorLeft = null
            ulunasProcessorRight?.destroy()
            ulunasProcessorRight = null
        } catch (e: Exception) {
            Logger.d("NoiseReducer", "Error during release: ${e.message}")
        }
    }
}
