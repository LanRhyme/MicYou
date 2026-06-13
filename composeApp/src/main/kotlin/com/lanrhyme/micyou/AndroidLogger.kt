package com.lanrhyme.micyou

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class AndroidLogger(private val context: Context) : LoggerImpl {
    private val logFile: File by lazy {
        val dir = context.getExternalFilesDir(null) ?: context.filesDir
        File(dir, "micyou.log")
    }
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())

    override fun log(level: LogLevel, tag: String, message: String, throwable: Throwable?) {
        // Logcat
        when (level) {
            LogLevel.DEBUG -> Log.d(tag, message, throwable)
            LogLevel.INFO -> Log.i(tag, message, throwable)
            LogLevel.WARN -> Log.w(tag, message, throwable)
            LogLevel.ERROR -> Log.e(tag, message, throwable)
        }

        // File
        try {
            val timestamp = dateFormat.format(Date())
    val logEntry = "$timestamp [$level][$tag] $message${throwable?.let { "\n${Log.getStackTraceString(it)}" } ?: ""}\n"
            FileOutputStream(logFile, true).use {
                it.write(logEntry.toByteArray())
            }
        } catch (e: Exception) {
            Log.e("AndroidLogger", "Failed to write log to file", e)
        }
    }

    override fun getLogFilePath(): String? {
        return logFile.absolutePath
    }
}
