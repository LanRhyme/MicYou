package com.lanrhyme.micyou

import android.app.Application
import android.util.Log

class MicYouApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        installMultiDexViaReflection()
        setupCrashHandler()
    }

    private fun installMultiDexViaReflection() {
        try {
            val clazz = Class.forName("androidx.multidex.MultiDex")
            val method = clazz.getMethod("install", Application::class.java)
            method.invoke(null, this)
            Log.i("MicYouApplication", "MultiDex installed")
        } catch (e: ClassNotFoundException) {
            Log.i("MicYouApplication", "MultiDex not available (compat mode disabled)")
        } catch (e: Exception) {
            Log.w("MicYouApplication", "MultiDex install failed: ${e.message}")
        }
    }

    private fun setupCrashHandler() {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            Log.e("MicYouApplication", "Uncaught exception on ${thread.name}", throwable)
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }
}
