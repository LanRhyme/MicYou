package com.lanrhyme.micyou.plugin

interface PluginContext {
    val pluginId: String
    val pluginDataDir: String
    fun getString(key: String, defaultValue: String): String
    fun putString(key: String, value: String)
    fun getBoolean(key: String, defaultValue: Boolean): Boolean
    fun putBoolean(key: String, value: Boolean)
    fun getInt(key: String, defaultValue: Int): Int
    fun putInt(key: String, value: Int)
    fun getFloat(key: String, defaultValue: Float): Float
    fun putFloat(key: String, value: Float)
    fun log(message: String)
    fun logError(message: String, throwable: Throwable? = null)
}
