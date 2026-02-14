package com.lanrhyme.micyou

import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * ADB 工具管理器
 * 提供 ADB 命令执行和端口转发功能
 * 
 * 主要功能：
 * - 查找 ADB 可执行文件
 * - 执行 ADB reverse 命令进行端口转发
 */
object AdbManager {
    
    private const val TAG = "AdbManager"
    
    /**
     * 查找 ADB 可执行文件的候选路径
     * @return ADB 可执行文件的路径列表（按优先级排序）
     */
    fun getAdbExecutableCandidates(): List<String> {
        val isWindows = System.getProperty("os.name")?.lowercase()?.contains("win") == true
        val exe = if (isWindows) "adb.exe" else "adb"

        val candidates = LinkedHashSet<String>()
        candidates.add("adb")

        val sdkRoot = System.getenv("ANDROID_SDK_ROOT") ?: System.getenv("ANDROID_HOME")
        if (!sdkRoot.isNullOrBlank()) {
            candidates.add(File(sdkRoot, "platform-tools/$exe").absolutePath)
        }

        val localAppData = System.getenv("LOCALAPPDATA")
        if (!localAppData.isNullOrBlank()) {
            candidates.add(File(localAppData, "Android/Sdk/platform-tools/$exe").absolutePath)
        }

        val userHome = System.getProperty("user.home")
        if (!userHome.isNullOrBlank() && isWindows) {
            candidates.add(File(userHome, "AppData/Local/Android/Sdk/platform-tools/$exe").absolutePath)
        }

        return candidates.toList()
    }
    
    /**
     * 执行 ADB reverse 命令进行端口转发
     * @param port 本地端口号
     * @param timeoutSeconds 超时时间（秒），默认6秒
     * @throws IOException 如果找不到 ADB 或执行失败
     */
    @Throws(IOException::class)
    fun runAdbReverse(port: Int, timeoutSeconds: Long = 6) {
        val candidates = getAdbExecutableCandidates()
        var lastError: Exception? = null

        for (adb in candidates) {
            val adbFile = File(adb)
            if (adb != "adb" && !adbFile.exists()) continue

            try {
                val process = ProcessBuilder(
                    adb,
                    "reverse",
                    "tcp:$port",
                    "tcp:$port"
                ).redirectErrorStream(true).start()

                val finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
                if (!finished) {
                    process.destroy()
                    throw IOException("ADB 命令执行超时")
                }

                val output = process.inputStream.bufferedReader().readText().trim()
                val code = process.exitValue()
                if (code != 0) {
                    val msg = if (output.isNotBlank()) output else "exitCode=$code"
                    throw IOException(msg)
                }

                Logger.d(TAG, "ADB reverse 成功: tcp:$port -> tcp:$port")
                return
            } catch (e: Exception) {
                lastError = e
                Logger.w(TAG, "ADB 命令执行失败: $adb, 错误: ${e.message}")
            }
        }

        val error = lastError ?: IOException("未找到 adb")
        Logger.e(TAG, "ADB reverse 失败", error)
        throw error
    }
    
    /**
     * 检查 ADB 是否可用
     * @return true 如果找到可用的 ADB
     */
    fun isAdbAvailable(): Boolean {
        val candidates = getAdbExecutableCandidates()
        
        for (adb in candidates) {
            val adbFile = File(adb)
            if (adb != "adb" && !adbFile.exists()) continue
            
            try {
                val process = ProcessBuilder(
                    adb,
                    "version"
                ).redirectErrorStream(true).start()
                
                val finished = process.waitFor(3, TimeUnit.SECONDS)
                if (finished && process.exitValue() == 0) {
                    return true
                }
            } catch (e: Exception) {
                // 继续尝试下一个候选
            }
        }
        
        return false
    }
    
    /**
     * 获取 ADB 版本信息
     * @return ADB 版本字符串，如果不可用返回 null
     */
    fun getAdbVersion(): String? {
        val candidates = getAdbExecutableCandidates()
        
        for (adb in candidates) {
            val adbFile = File(adb)
            if (adb != "adb" && !adbFile.exists()) continue
            
            try {
                val process = ProcessBuilder(
                    adb,
                    "version"
                ).redirectErrorStream(true).start()
                
                val finished = process.waitFor(3, TimeUnit.SECONDS)
                if (finished && process.exitValue() == 0) {
                    val output = process.inputStream.bufferedReader().readText()
                    // 提取版本号
                    val versionMatch = Regex("Android Debug Bridge version (\\S+)").find(output)
                    return versionMatch?.groupValues?.get(1)
                }
            } catch (e: Exception) {
                // 继续尝试下一个候选
            }
        }
        
        return null
    }
}
