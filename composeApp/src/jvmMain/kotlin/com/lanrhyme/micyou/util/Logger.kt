package com.lanrhyme.micyou.util

import com.lanrhyme.micyou.LogLevel
import com.lanrhyme.micyou.LoggerImpl
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.FileWriter
import java.io.PrintWriter
import java.text.SimpleDateFormat
import java.util.*
import java.util.zip.GZIPOutputStream

object JvmLogger : LoggerImpl {
    private var logFile: File? = null
    private var logWriter: PrintWriter? = null
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())
    
    private const val MAX_FILE_SIZE = 8 * 1024 * 1024L
    private const val MAX_ARCHIVED_FILES = 2
    
    private val logDir: File by lazy {
        val userHome = System.getProperty("user.home")
        File(userHome, ".micyou").apply {
            if (!exists()) mkdirs()
        }
    }
    
    fun init() {
        try {
            logFile = File(logDir, "micyou.log")
            logWriter = PrintWriter(FileWriter(logFile, true), true)
        } catch (e: Exception) {
            System.err.println("Failed to initialize logger: ${e.message}")
        }
    }
    
    private fun checkAndRotate() {
        val file = logFile ?: return
        if (file.length() >= MAX_FILE_SIZE) {
            rotateLog(file)
        }
    }
    
    private fun rotateLog(currentFile: File) {
        try {
            logWriter?.flush()
            logWriter?.close()
            logWriter = null
            
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
    val archivedFile = File(logDir, "micyou_$timestamp.log")
            
            currentFile.renameTo(archivedFile)
            
            compressFile(archivedFile)
            
            cleanupOldArchives()
            
            logFile = File(logDir, "micyou.log")
            logWriter = PrintWriter(FileWriter(logFile, true), true)
        } catch (e: Exception) {
            System.err.println("Failed to rotate log: ${e.message}")
            try {
                logFile = File(logDir, "micyou.log")
                logWriter = PrintWriter(FileWriter(logFile, true), true)
            } catch (e2: Exception) {
                // 二次尝试也失败，记录错误但不中断程序运行
                System.err.println("Failed to reinitialize log file after rotation: ${e2.message}")
            }
        }
    }
    
    private fun compressFile(file: File) {
        if (!file.exists()) return
        try {
            val gzipFile = File(file.parentFile, "${file.name}.gz")
            FileInputStream(file).use { input ->
                GZIPOutputStream(FileOutputStream(gzipFile)).use { output ->
                    input.copyTo(output)
                }
            }
            file.delete()
        } catch (e: Exception) {
            System.err.println("Failed to compress log: ${e.message}")
        }
    }
    
    private fun cleanupOldArchives() {
        val gzipFiles = logDir.listFiles { f -> f.name.startsWith("micyou_") && f.name.endsWith(".log.gz") }
            ?.sortedByDescending { it.lastModified() }
            ?: return
        
        if (gzipFiles.size > MAX_ARCHIVED_FILES) {
            gzipFiles.drop(MAX_ARCHIVED_FILES).forEach { it.delete() }
        }
    }
    
    private fun formatMessage(level: LogLevel, tag: String, message: String): String {
        val timestamp = dateFormat.format(Date())
        return "[$timestamp] ${level.name}/$tag: $message"
    }
    
    override fun log(level: LogLevel, tag: String, message: String, throwable: Throwable?) {
        checkAndRotate()
    val formattedMessage = formatMessage(level, tag, message)
        
        println(formattedMessage)
        
        try {
            logWriter?.println(formattedMessage)
            throwable?.let {
                it.printStackTrace(System.out)
                it.printStackTrace(logWriter)
            }
            logWriter?.flush()
        } catch (e: Exception) {
            // 写入日志文件失败时，仅输出到控制台，不影响程序运行
            System.err.println("Failed to write to log file: ${e.message}")
        }
    }
    
    override fun getLogFilePath(): String? {
        return logFile?.absolutePath
    }
    
    fun release() {
        try {
            logWriter?.flush()
            logWriter?.close()
            logWriter = null
        } catch (e: Exception) {
            // 释放日志资源失败，记录但不影响程序退出
            System.err.println("Error releasing log writer: ${e.message}")
        }
    }
}
