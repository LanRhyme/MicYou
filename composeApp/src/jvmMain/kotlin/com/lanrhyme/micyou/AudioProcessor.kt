package com.lanrhyme.micyou

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

class AudioProcessor(
    preGainDb: Float,
    postGainDb: Float,
    modelPath: String,
    frameSize: Long,
    hopLength: Long
) {
    private var nativeHandle: Long = 0

    init {
        nativeHandle = nativeCreate(preGainDb, postGainDb, modelPath, frameSize, hopLength)
    }

    fun process(input: FloatArray): FloatArray {
        if (nativeHandle == 0L) {
            return input
        }
        return nativeProcess(nativeHandle, input)
    }

    fun setPreGain(gainDb: Float) {
        if (nativeHandle != 0L) {
            nativeSetPreGain(nativeHandle, gainDb)
        }
    }

    fun setPostGain(gainDb: Float) {
        if (nativeHandle != 0L) {
            nativeSetPostGain(nativeHandle, gainDb)
        }
    }

    fun setEqGains(gains: FloatArray) {
        if (nativeHandle != 0L) {
            nativeSetEqGains(nativeHandle, gains)
        }
    }

    fun destroy() {
        if (nativeHandle != 0L) {
            nativeDestroy(nativeHandle)
            nativeHandle = 0
        }
    }

    protected fun finalize() {
        destroy()
    }

    private external fun nativeCreate(
        preGainDb: Float,
        postGainDb: Float,
        modelPath: String,
        frameSize: Long,
        hopLength: Long
    ): Long

    private external fun nativeDestroy(handle: Long)
    private external fun nativeProcess(handle: Long, input: FloatArray): FloatArray
    private external fun nativeSetPreGain(handle: Long, gainDb: Float)
    private external fun nativeSetPostGain(handle: Long, gainDb: Float)
    private external fun nativeSetEqGains(handle: Long, gains: FloatArray)

    companion object {
        @Volatile
        private var libraryLoaded = false

        init {
            loadNativeLibrary()
        }

        private fun loadNativeLibrary() {
            if (libraryLoaded) return

            synchronized(this) {
                if (libraryLoaded) return

                val dllName = "audio_processor.dll"
                val tempDir = Files.createTempDirectory("micyou_native")
                val tempDll = tempDir.resolve(dllName).toFile()

                // 尝试从 classpath 加载 DLL 到临时目录
                val classLoader = AudioProcessor::class.java.classLoader
                val resourceStream = classLoader.getResourceAsStream(dllName)
                    ?: classLoader.getResourceAsStream("$dllName")

                if (resourceStream != null) {
                    resourceStream.use { input ->
                        tempDll.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                    tempDll.deleteOnExit()
                    System.load(tempDll.absolutePath)
                    libraryLoaded = true
                    return
                }

                // 如果 classpath 中没有，尝试从文件系统加载
                val searchPaths = listOf(
                    dllName,
                    "composeApp/src/jvmMain/resources/$dllName",
                    "src/jvmMain/resources/$dllName",
                    "../src/jvmMain/resources/$dllName",
                    "../../src/jvmMain/resources/$dllName"
                )

                for (path in searchPaths) {
                    val file = File(path)
                    if (file.exists() && file.isFile) {
                        System.load(file.absolutePath)
                        libraryLoaded = true
                        return
                    }
                }

                // 最后尝试从 java.library.path 加载
                try {
                    System.loadLibrary("audio_processor")
                    libraryLoaded = true
                } catch (e: UnsatisfiedLinkError) {
                    throw UnsatisfiedLinkError(
                        "无法加载 audio_processor.dll。请确保 DLL 文件在以下位置之一：\n" +
                        searchPaths.joinToString("\n") { "  - $it" } + "\n" +
                        "或将其放在 classpath 根目录"
                    )
                }
            }
        }
    }
}
