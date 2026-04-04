package com.lanrhyme.micyou.audio

import com.lanrhyme.micyou.Logger
import com.lanrhyme.micyou.NoiseReductionType
import de.maxhenkel.rnnoise4j.Denoiser
import java.io.IOException
import java.nio.file.Files

class NoiseReducer(
    private val frameSize: Int = 480
) : AudioEffect {
    
    var enableNS: Boolean = false
    var nsType: NoiseReductionType = NoiseReductionType.Ulunas

    // RNNoise
    private var denoiserLeft: Denoiser? = null
    private var denoiserRight: Denoiser? = null
    private var rnnoiseFrameLeft: ShortArray = ShortArray(0)
    private var rnnoiseFrameRight: ShortArray = ShortArray(0)

    // Ulunas
    private var ulunasProcessorLeft: UlunasProcessor? = null
    private var ulunasProcessorRight: UlunasProcessor? = null
    private var ulunasFrameLeft: FloatArray = FloatArray(0)
    private var ulunasFrameRight: FloatArray = FloatArray(0)
    
    // 使用伴生对象缓存模型路径，避免重复创建临时目录
    companion object {
        private var cachedModelPath: String? = null
        private var tempModelDir: java.nio.file.Path? = null
        
        @Synchronized
        fun cleanupTempFiles() {
            tempModelDir?.let { dir ->
                try {
                    dir.toFile().deleteRecursively()
                    Logger.d("NoiseReducer", "Cleaned up temp model directory: $dir")
                } catch (e: Exception) {
                    Logger.w("NoiseReducer", "Failed to cleanup temp directory: ${e.message}")
                }
                tempModelDir = null
                cachedModelPath = null
            }
        }
    }

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
            val modelPath = getUlnasModelPath()
            
            val hopLength = frameSize 
            
            if (ulunasProcessorLeft == null) {
                Logger.i("NoiseReducer", "Initializing Ulunas processor (Kotlin) with model: $modelPath")
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
                            input[base + i] = (processedLeft[i] * 32767.0f).toInt().coerceIn(-32768, 32767).toShort()
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
                            input[idx] = (processedLeft[i] * 32767.0f).toInt().coerceIn(-32768, 32767).toShort()
                            input[idx + 1] = (processedRight[i] * 32767.0f).toInt().coerceIn(-32768, 32767).toShort()
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Logger.e("NoiseReducer", "Ulunas processing failed: ${e.message}", e)
            enableNS = false
        }
    }
    
    private fun getUlnasModelPath(): String {
        // 首先检查缓存的路径
        cachedModelPath?.let { 
            if (java.io.File(it).exists()) return it 
        }
        
        System.getProperty("micyou.ulunas.model.path")?.let {
            Logger.i("NoiseReducer", "Using system property Ulunas model path: $it")
            cachedModelPath = it
            return it
        }

        // 尝试使用用户主目录下的固定位置，而不是临时目录
        val userDir = java.io.File(System.getProperty("user.home"), ".micyou")
        if (!userDir.exists()) {
            userDir.mkdirs()
        }
        val modelFile = java.io.File(userDir, "ulunas.onnx")

        if (modelFile.exists() && modelFile.length() > 0) {
            Logger.i("NoiseReducer", "Found existing model in user dir: ${modelFile.absolutePath}, size: ${modelFile.length()}")
            cachedModelPath = modelFile.absolutePath
            return modelFile.absolutePath
        }

        Logger.i("NoiseReducer", "Loading Ulunas model from resources...")
        val classLoader = this.javaClass.classLoader
        val resourceStream = classLoader.getResourceAsStream("ulunas.onnx")
            ?: throw IOException("Unable to find Ulunas model file: ulunas.onnx")

        resourceStream.use { input ->
            modelFile.outputStream().use { output ->
                input.copyTo(output)
            }
        }

        Logger.i("NoiseReducer", "Copied model to: ${modelFile.absolutePath}, size: ${modelFile.length()}")
        cachedModelPath = modelFile.absolutePath
        return modelFile.absolutePath
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
        }
    }
}
