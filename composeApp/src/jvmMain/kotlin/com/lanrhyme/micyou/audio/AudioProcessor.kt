package com.lanrhyme.micyou.audio

import com.lanrhyme.micyou.Logger
import java.io.File
import java.nio.file.Files

class AudioProcessor(
    preGainDb: Float,
    postGainDb: Float,
    modelPath: String,
    frameSize: Long,
    hopLength: Long
) {
    private var nativeHandle: Long = 0

    init {
        ensureNativeLibraryLoaded()
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
        private val lock = Any()

        private val dependentLibraries: List<String>
            get() = getPlatformDependentLibraries()

        private fun getPlatformDependentLibraries(): List<String> {
            val osName = System.getProperty("os.name").lowercase()
            return when {
                osName.contains("win") -> listOf(
                    "onnxruntime.dll",
                    "onnxruntime_providers_shared.dll"
                )
                osName.contains("mac") -> listOf(
                    "libonnxruntime.dylib"
                )
                else -> listOf( // Linux and other Unix-like systems
                    "libonnxruntime.so"
                )
            }
        }

        fun ensureNativeLibraryLoaded() {
            if (libraryLoaded) return

            synchronized(lock) {
                if (libraryLoaded) return

                val osName = System.getProperty("os.name").lowercase()
                val dllName = when {
                    osName.contains("win") -> "audio_processor.dll"
                    osName.contains("mac") -> "libaudio_processor.dylib"
                    else -> "libaudio_processor.so"
                }

                val loaded = tryLoadFromDevelopmentPaths(dllName)
                    || tryLoadFromClasspath(dllName)
                    || tryLoadFromSystemProperty(dllName)
                    || tryLoadFromSystemLibraryPath()

                if (!loaded) {
                    throw UnsatisfiedLinkError(
                        "无法加载 $dllName\n" +
                        "请确保文件存在于以下位置之一：\n" +
                        "  - classpath 根目录或 natives/ 目录\n" +
                        "  - 开发环境: composeApp/src/jvmMain/resources/\n" +
                        "  - 或设置系统属性: -Dmicyou.library.path=/path/to/dll"
                    )
                }

                libraryLoaded = true
                Logger.i("AudioProcessor", "Native library loaded successfully: $dllName")
            }
        }

        private fun tryLoadFromSystemProperty(dllName: String): Boolean {
            val customPath = System.getProperty("micyou.library.path")
            if (!customPath.isNullOrBlank()) {
                val dir = File(customPath)
                if (dir.exists() && dir.isDirectory) {
                    loadDependentDllsFromDirectory(dir)
                    val file = File(dir, dllName)
                    if (file.exists() && file.isFile) {
                        System.load(file.absolutePath)
                        Logger.i("AudioProcessor", "Loaded from system property: ${file.absolutePath}")
                        return true
                    }
                }
                val directFile = File(customPath)
                if (directFile.exists() && directFile.isFile && directFile.name == dllName) {
                    System.load(directFile.absolutePath)
                    Logger.i("AudioProcessor", "Loaded from system property (direct): ${directFile.absolutePath}")
                    return true
                }
            }
            return false
        }

        private fun tryLoadFromClasspath(dllName: String): Boolean {
            val classLoader = AudioProcessor::class.java.classLoader
            
            val resourcePaths = listOf(dllName, "natives/$dllName", "native/$dllName")
            
            for (resourcePath in resourcePaths) {
                val resourceStream = classLoader.getResourceAsStream(resourcePath)
                if (resourceStream != null) {
                    try {
                        val tempDir = Files.createTempDirectory("micyou_native").toFile()
                        tempDir.deleteOnExit()
                        
                        extractAndLoadDependentDlls(classLoader, tempDir)
                        
                        val tempDll = File(tempDir, dllName)
                        resourceStream.use { input ->
                            tempDll.outputStream().use { output ->
                                input.copyTo(output)
                            }
                        }
                        tempDll.deleteOnExit()
                        
                        System.load(tempDll.absolutePath)
                        Logger.i("AudioProcessor", "Loaded from classpath: $resourcePath -> ${tempDll.absolutePath}")
                        return true
                    } catch (e: Exception) {
                        Logger.w("AudioProcessor", "Failed to load from classpath: $resourcePath - ${e.message}")
                    }
                }
            }
            return false
        }

        private fun extractAndLoadDependentDlls(classLoader: ClassLoader, targetDir: File) {
            for (depLib in dependentLibraries) {
                val depPaths = listOf(depLib, "natives/$depLib", "native/$depLib")
                for (depPath in depPaths) {
                    val depStream = classLoader.getResourceAsStream(depPath)
                    if (depStream != null) {
                        val targetFile = File(targetDir, depLib)
                        depStream.use { input ->
                            targetFile.outputStream().use { output ->
                                input.copyTo(output)
                            }
                        }
                        targetFile.deleteOnExit()
                        Logger.i("AudioProcessor", "Extracted dependent library: $depPath -> ${targetFile.absolutePath}")
                        
                        try {
                            System.load(targetFile.absolutePath)
                            Logger.i("AudioProcessor", "Pre-loaded dependent library: ${targetFile.absolutePath}")
                        } catch (e: UnsatisfiedLinkError) {
                            Logger.w("AudioProcessor", "Failed to pre-load dependent library: ${targetFile.absolutePath} - ${e.message}")
                        }
                        break
                    }
                }
            }
        }

        private fun loadDependentDllsFromDirectory(dir: File) {
            for (depLib in dependentLibraries) {
                val depFile = File(dir, depLib)
                if (depFile.exists() && depFile.isFile) {
                    try {
                        System.load(depFile.absolutePath)
                        Logger.i("AudioProcessor", "Pre-loaded dependent library from dir: ${depFile.absolutePath}")
                    } catch (e: UnsatisfiedLinkError) {
                        Logger.w("AudioProcessor", "Failed to pre-load dependent library: ${depFile.absolutePath} - ${e.message}")
                    }
                }
            }
        }

        private fun tryLoadFromDevelopmentPaths(dllName: String): Boolean {
            val userDir = System.getProperty("user.dir") ?: ""
            Logger.i("AudioProcessor", "Current working directory: $userDir")
            
            val devPaths = listOf(
                "composeApp/src/jvmMain/resources",
                "src/jvmMain/resources",
                "../src/jvmMain/resources",
                "../../src/jvmMain/resources"
            )

            for (path in devPaths) {
                val dir = if (File(path).isAbsolute) {
                    File(path)
                } else {
                    File(userDir, path)
                }
                
                Logger.i("AudioProcessor", "Checking development path: ${dir.absolutePath}")
                
                if (dir.exists() && dir.isDirectory) {
                    val dllFile = File(dir, dllName)
                    Logger.i("AudioProcessor", "Checking DLL file: ${dllFile.absolutePath}, exists: ${dllFile.exists()}")
                    
                    if (dllFile.exists() && dllFile.isFile) {
                        Logger.i("AudioProcessor", "Found DLL at: ${dllFile.absolutePath}")
                        Logger.i("AudioProcessor", "Loading dependent libraries from: ${dir.absolutePath}")
                        
                        for (depLib in dependentLibraries) {
                            val depFile = File(dir, depLib)
                            Logger.i("AudioProcessor", "Checking dependent library: ${depFile.absolutePath}, exists: ${depFile.exists()}")
                            if (depFile.exists() && depFile.isFile) {
                                try {
                                    System.load(depFile.absolutePath)
                                    Logger.i("AudioProcessor", "Pre-loaded dependent library: ${depFile.absolutePath}")
                                } catch (e: UnsatisfiedLinkError) {
                                    Logger.w("AudioProcessor", "Failed to pre-load dependent library: ${depFile.absolutePath} - ${e.message}")
                                }
                            } else {
                                Logger.w("AudioProcessor", "Dependent library not found: ${depFile.absolutePath}")
                            }
                        }
                        
                        System.load(dllFile.absolutePath)
                        Logger.i("AudioProcessor", "Loaded from development path: ${dllFile.absolutePath}")
                        return true
                    }
                }
            }
            return false
        }

        private fun tryLoadFromSystemLibraryPath(): Boolean {
            return try {
                System.loadLibrary("audio_processor")
                Logger.i("AudioProcessor", "Loaded from system library path")
                true
            } catch (e: UnsatisfiedLinkError) {
                false
            }
        }

        fun isLibraryLoaded(): Boolean = libraryLoaded
    }
}
