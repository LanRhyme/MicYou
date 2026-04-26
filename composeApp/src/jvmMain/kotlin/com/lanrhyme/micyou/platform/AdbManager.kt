package com.lanrhyme.micyou.platform

import com.lanrhyme.micyou.Logger
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

object AdbManager {
    private var adbPath: String? = null
    
    fun findAdb(): String? {
        adbPath?.let { path ->
            if (File(path).exists()) {
                return path
            }
            adbPath = null
        }
    val pathEnv = System.getenv("PATH") ?: return null
        val pathSeparator = if (PlatformInfo.isWindows) ";" else ":"
        val adbExecutable = if (PlatformInfo.isWindows) "adb.exe" else "adb"
        
        for (pathDir in pathEnv.split(pathSeparator)) {
            val adbFile = File(pathDir, adbExecutable)
            if (adbFile.exists() && adbFile.canExecute()) {
                adbPath = adbFile.absolutePath
                Logger.i("AdbManager", "找到 ADB: $adbPath")
                return adbPath
            }
        }
    val commonPaths = if (PlatformInfo.isWindows) {
            listOf(
                "${System.getenv("LOCALAPPDATA")}\\Android\\Sdk\\platform-tools\\adb.exe",
                "${System.getenv("USERPROFILE")}\\AppData\\Local\\Android\\Sdk\\platform-tools\\adb.exe",
                "C:\\Android\\sdk\\platform-tools\\adb.exe"
            )
        } else {
            listOf(
                "${System.getenv("HOME")}/Android/Sdk/platform-tools/adb",
                "/usr/bin/adb",
                "/usr/local/bin/adb",
                "/opt/android-sdk/platform-tools/adb"
            )
        }
        
        for (path in commonPaths) {
            val file = File(path)
            if (file.exists()) {
                adbPath = file.absolutePath
                Logger.i("AdbManager", "找到 ADB: $adbPath")
                return adbPath
            }
        }
        
        Logger.w("AdbManager", "未找到 ADB")
        return null
    }
    
    fun runAdbReverse(port: Int): Boolean {
        val adb = findAdb() ?: run {
            Logger.e("AdbManager", "ADB 未找到，无法执行 reverse")
            return false
        }
        
        return try {
            val process = ProcessBuilder(adb, "reverse", "tcp:$port", "tcp:$port")
                .redirectErrorStream(true)
                .start()
    val output = process.inputStream.bufferedReader().readText()
    val exitCode = process.waitFor()
            
            if (exitCode == 0 && output.contains("error", ignoreCase = true).not()) {
                Logger.i("AdbManager", "ADB reverse 成功: $port")
                true
            } else {
                Logger.e("AdbManager", "ADB reverse 失败: $output")
                false
            }
        } catch (e: Exception) {
            Logger.e("AdbManager", "执行 ADB reverse 时出错", e)
            false
        }
    }
    
    fun checkAdbAvailable(): Boolean {
        return findAdb() != null
    }
    
    fun getAdbVersion(): String? {
        val adb = findAdb() ?: return null
        
        return try {
            val process = ProcessBuilder(adb, "version")
                .redirectErrorStream(true)
                .start()
    val reader = BufferedReader(InputStreamReader(process.inputStream))
    val firstLine = reader.readLine()
            process.waitFor()
            
            firstLine
        } catch (e: Exception) {
            null
        }
    }
}
