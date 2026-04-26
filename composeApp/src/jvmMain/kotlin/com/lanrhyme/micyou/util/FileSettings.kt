package com.lanrhyme.micyou.util

import com.lanrhyme.micyou.Logger
import com.lanrhyme.micyou.Settings
import java.io.File
import java.util.Properties
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write
import org.jetbrains.compose.resources.getString

/**
 * 文件持久化设置存储，支持延迟写入优化。
 *
 * 性能优化：
 * - 使用延迟写入机制，减少磁盘 I/O 频率
 * - 批量更新设置时只触发一次保存
 * - 支持立即写入关键设置（如用户主动保存）
 */
class FileSettings(private val configFile: File) : Settings {
    private val properties = Properties()
    private val lock = ReentrantReadWriteLock()

    // 延迟写入相关
    private var dirty = false
    private var lastSaveTime = 0L
    private val saveDelayMs = 2000L // 2秒延迟写入

    // 强制写入标记（用于关键设置）
    private var forceSave = false

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

    /**
     * 触发保存操作。
     * 默认立即写入以确保数据持久化。
     * 如果需要批量更新，请使用 batchUpdate() 方法。
     */
    private fun triggerSave(immediate: Boolean = true) {
        if (immediate || forceSave) {
            saveImmediate()
        } else {
            dirty = true
        }
    }

    /**
     * 立即保存到文件。
     */
    private fun saveImmediate() {
        lock.write {
            try {
                configFile.parentFile?.mkdirs()
                configFile.outputStream().use { output ->
                    properties.store(output, null)
                }
                dirty = false
                lastSaveTime = System.currentTimeMillis()
            } catch (e: Exception) {
                Logger.e("FileSettings", "Failed to save settings to ${configFile.absolutePath}", e)
            }
        }
    }

    /**
     * 执行延迟保存（如果需要）。
     * 此方法应在应用退出或定期检查时调用。
     */
    fun flush() {
        if (dirty) {
            saveImmediate()
        }
    }

    /**
     * 检查是否需要保存，并执行保存。
     * 用于定期检查，避免内存中设置丢失。
     */
    fun checkAndFlush() {
        if (dirty && System.currentTimeMillis() - lastSaveTime >= saveDelayMs) {
            saveImmediate()
        }
    }

    /**
     * 设置强制写入模式。
     * 用于关键设置场景，如用户主动保存。
     */
    fun setForceSaveMode(enabled: Boolean) {
        forceSave = enabled
        if (enabled && dirty) {
            saveImmediate()
        }
    }

    override fun getString(key: String, defaultValue: String): String {
        return lock.read { properties.getProperty(key, defaultValue) }
    }

    override fun putString(key: String, value: String) {
        lock.write {
            properties.setProperty(key, value)
            triggerSave()
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
            triggerSave()
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
            triggerSave()
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
            triggerSave()
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
            triggerSave()
        }
    }

    /**
     * 批量更新多个设置。
     * 在更新期间暂停自动保存，更新完成后统一触发一次保存。
     */
    fun batchUpdate(updates: Map<String, String>) {
        lock.write {
            updates.forEach { (key, value) ->
                properties.setProperty(key, value)
            }
            dirty = true
        }
        // 批量更新后立即保存
        saveImmediate()
    }

    fun remove(key: String) {
        lock.write {
            properties.remove(key)
            triggerSave()
        }
    }

    fun clear() {
        lock.write {
            properties.clear()
            triggerSave(immediate = true)
        }
    }
}
