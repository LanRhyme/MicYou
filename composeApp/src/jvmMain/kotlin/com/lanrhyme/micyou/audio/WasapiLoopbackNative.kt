package com.lanrhyme.micyou.audio

import com.lanrhyme.micyou.Logger
import java.io.File
import java.nio.file.Files

/**
 * JNI 接口：原生 WASAPI Loopback 采集
 * 通过 C++ DLL 实现，避免 JNA COM 调用的兼容性问题
 */
class WasapiLoopbackNative {
    private var onAudioDataCallback: ((ByteArray, Int, Int, Long) -> Unit)? = null
    private var started = false

    fun onAudioData(callback: (ByteArray, Int, Int, Long) -> Unit) {
        onAudioDataCallback = callback
    }

    /**
     * 从 native 层回调的音频数据
     */
    @Suppress("unused")
    fun onAudioData(data: ByteArray, actualSampleRate: Int, actualChannels: Int) {
        onAudioDataCallback?.invoke(data, actualSampleRate, actualChannels, System.currentTimeMillis())
    }

    fun start(sampleRate: Int, channels: Int): Boolean {
        if (started) return true
        val result = nativeStart(sampleRate, channels)
        started = result == 0
        if (!started) {
            Logger.e("WasapiLoopbackNative", "nativeStart failed with code: $result")
        }
        return started
    }

    fun stop() {
        if (!started) return
        nativeStop()
        started = false
    }

    private external fun nativeStart(sampleRate: Int, channels: Int): Int
    private external fun nativeStop()

    companion object {
        private var loaded = false
        private var loadError: String? = null

        fun ensureLoaded() {
            if (loaded || loadError != null) return
            try {
                val libName = "wasapi_loopback"
                val ext = if (System.getProperty("os.name").lowercase().contains("win")) ".dll" else ".so"

                // 尝试从 resources 中提取 DLL 到临时目录
                val resourcePath = "/$libName$ext"
                val inputStream = WasapiLoopbackNative::class.java.getResourceAsStream(resourcePath)
                if (inputStream != null) {
                    val tempDir = Files.createTempDirectory("micyou_native").toFile()
                    tempDir.deleteOnExit()
                    val tempFile = File(tempDir, "$libName$ext")
                    tempFile.deleteOnExit()
                    tempFile.outputStream().use { output ->
                        inputStream.copyTo(output)
                    }
                    System.load(tempFile.absolutePath)
                    loaded = true
                    Logger.i("WasapiLoopbackNative", "Loaded native library from resources: ${tempFile.absolutePath}")
                } else {
                    // 回退：尝试直接加载（开发环境，DLL 在工作目录）
                    System.loadLibrary(libName)
                    loaded = true
                    Logger.i("WasapiLoopbackNative", "Loaded native library via System.loadLibrary")
                }
            } catch (e: UnsatisfiedLinkError) {
                loadError = e.message
                Logger.e("WasapiLoopbackNative", "Failed to load native library: ${e.message}")
            } catch (e: Exception) {
                loadError = e.message
                Logger.e("WasapiLoopbackNative", "Failed to load native library: ${e.message}")
            }
        }

        fun isAvailable(): Boolean {
            ensureLoaded()
            return loaded
        }
    }
}
