package com.lanrhyme.micyou.util

import com.lanrhyme.micyou.Logger
import com.lanrhyme.micyou.Settings
import java.io.File
import java.util.Properties
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

class FileSettings(private val configFile: File) : Settings {
    private val properties = Properties()
    private val lock = ReentrantReadWriteLock()

    init {
        load()
    }

    private fun load() {
        lock.write {
            try {
                if (configFile.exists()) {
                    configFile.inputStream().use { input ->
                        properties.load(input)
                    }
                    Logger.d("FileSettings", "Loaded settings from ${configFile.absolutePath}")
                } else {
                    configFile.parentFile?.mkdirs()
                    Logger.d("FileSettings", "Created new settings file at ${configFile.absolutePath}")
                }
            } catch (e: Exception) {
                Logger.e("FileSettings", "Failed to load settings from ${configFile.absolutePath}", e)
            }
        }
    }

    private fun save() {
        lock.write {
            try {
                configFile.parentFile?.mkdirs()
                configFile.outputStream().use { output ->
                    properties.store(output, null)
                }
            } catch (e: Exception) {
                Logger.e("FileSettings", "Failed to save settings to ${configFile.absolutePath}", e)
            }
        }
    }

    override fun getString(key: String, defaultValue: String): String {
        return lock.read { properties.getProperty(key, defaultValue) }
    }

    override fun putString(key: String, value: String) {
        lock.write {
            properties.setProperty(key, value)
            save()
        }
    }

    override fun getLong(key: String, defaultValue: Long): Long {
        return lock.read { 
            properties.getProperty(key)?.toLongOrNull() ?: defaultValue 
        }
    }

    override fun putLong(key: String, value: Long) {
        lock.write {
            properties.setProperty(key, value.toString())
            save()
        }
    }

    override fun getBoolean(key: String, defaultValue: Boolean): Boolean {
        return lock.read { 
            properties.getProperty(key)?.toBooleanStrictOrNull() ?: defaultValue 
        }
    }

    override fun putBoolean(key: String, value: Boolean) {
        lock.write {
            properties.setProperty(key, value.toString())
            save()
        }
    }

    override fun getInt(key: String, defaultValue: Int): Int {
        return lock.read { 
            properties.getProperty(key)?.toIntOrNull() ?: defaultValue 
        }
    }

    override fun putInt(key: String, value: Int) {
        lock.write {
            properties.setProperty(key, value.toString())
            save()
        }
    }

    override fun getFloat(key: String, defaultValue: Float): Float {
        return lock.read { 
            properties.getProperty(key)?.toFloatOrNull() ?: defaultValue 
        }
    }

    override fun putFloat(key: String, value: Float) {
        lock.write {
            properties.setProperty(key, value.toString())
            save()
        }
    }

    fun remove(key: String) {
        lock.write {
            properties.remove(key)
            save()
        }
    }

    fun clear() {
        lock.write {
            properties.clear()
            save()
        }
    }
}
