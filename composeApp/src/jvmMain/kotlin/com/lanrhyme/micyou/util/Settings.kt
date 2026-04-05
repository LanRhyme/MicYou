package com.lanrhyme.micyou.util

import com.lanrhyme.micyou.Settings
import java.io.File

object JvmSettings : Settings {
    private val appDir: File = File(System.getProperty("user.dir"))
    private val configFile: File = File(appDir, "micyou.conf")
    private val fileSettings: FileSettings = FileSettings(configFile)
    
    override fun getString(key: String, defaultValue: String): String {
        return fileSettings.getString(key, defaultValue)
    }
    
    override fun putString(key: String, value: String) {
        fileSettings.putString(key, value)
    }
    
    override fun getLong(key: String, defaultValue: Long): Long {
        return fileSettings.getLong(key, defaultValue)
    }
    
    override fun putLong(key: String, value: Long) {
        fileSettings.putLong(key, value)
    }
    
    override fun getBoolean(key: String, defaultValue: Boolean): Boolean {
        return fileSettings.getBoolean(key, defaultValue)
    }
    
    override fun putBoolean(key: String, value: Boolean) {
        fileSettings.putBoolean(key, value)
    }
    
    override fun getInt(key: String, defaultValue: Int): Int {
        return fileSettings.getInt(key, defaultValue)
    }
    
    override fun putInt(key: String, value: Int) {
        fileSettings.putInt(key, value)
    }
    
    override fun getFloat(key: String, defaultValue: Float): Float {
        return fileSettings.getFloat(key, defaultValue)
    }
    
    override fun putFloat(key: String, value: Float) {
        fileSettings.putFloat(key, value)
    }
    
    fun remove(key: String) {
        fileSettings.remove(key)
    }
    
    fun clear() {
        fileSettings.clear()
    }
}
