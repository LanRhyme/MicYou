package com.lanrhyme.micyou.util

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object Logger {
    private var context: Context? = null
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())
    private val logFile: File by lazy {
        val ctx = context ?: throw IllegalStateException("Logger.init(context) must be called first")
        val dir = ctx.getExternalFilesDir(null) ?: ctx.filesDir
        File(dir, "micyou.log")
    }

    fun init(context: Context) {
        this.context = context.applicationContext
    }

    fun d(tag: String, message: String) = log(Log.DEBUG, tag, message, null)
    fun i(tag: String, message: String) = log(Log.INFO, tag, message, null)
    fun w(tag: String, message: String) = log(Log.WARN, tag, message, null)
    fun e(tag: String, message: String, throwable: Throwable? = null) = log(Log.ERROR, tag, message, throwable)

    private fun log(priority: Int, tag: String, message: String, throwable: Throwable?) {
        // Logcat
        when (priority) {
            Log.DEBUG -> Log.d(tag, message, throwable)
            Log.INFO -> Log.i(tag, message, throwable)
            Log.WARN -> Log.w(tag, message, throwable)
            Log.ERROR -> Log.e(tag, message, throwable)
        }

        // File
        try {
            val timestamp = dateFormat.format(Date())
            val logEntry = "$timestamp [${priorityToTag(priority)}][$tag] $message${
                throwable?.let {
                    "\n${
                        Log.getStackTraceString(it)
                    }"
                } ?: ""
            }\n"
            FileOutputStream(logFile, true).use { it.write(logEntry.toByteArray()) }
        } catch (_: Exception) {
        }
    }

    fun getLogFilePath(): String? {
        return try {
            logFile.absolutePath
        } catch (_: Exception) {
            null
        }
    }

    private fun priorityToTag(priority: Int): String = when (priority) {
        Log.DEBUG -> "DEBUG"
        Log.INFO -> "INFO"
        Log.WARN -> "WARN"
        Log.ERROR -> "ERROR"
        else -> "UNKNOWN"
    }
}
