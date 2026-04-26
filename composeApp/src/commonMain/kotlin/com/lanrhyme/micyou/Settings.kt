package com.lanrhyme.micyou
import org.jetbrains.compose.resources.getString

interface Settings {
    fun getString(key: String, defaultValue: String): String
    fun putString(key: String, value: String)
    fun getLong(key: String, defaultValue: Long): Long
    fun putLong(key: String, value: Long)
    fun getBoolean(key: String, defaultValue: Boolean): Boolean
    fun putBoolean(key: String, value: Boolean)
    fun getInt(key: String, defaultValue: Int): Int
    fun putInt(key: String, value: Int)
    fun getFloat(key: String, defaultValue: Float): Float
    fun putFloat(key: String, value: Float)
}

expect object SettingsFactory {
    fun getSettings(): Settings
}

