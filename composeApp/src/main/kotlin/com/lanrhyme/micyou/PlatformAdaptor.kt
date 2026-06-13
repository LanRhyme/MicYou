package com.lanrhyme.micyou

/**
 * Platform adaptor for platform-specific operations.
 */
object PlatformAdaptor {
    fun configureAudioOutput(): Any? = null
    fun restoreAudioOutput(token: Any?) {}
    suspend fun runAdbReverse(port: Int): Boolean = true
    fun cleanupTempFiles() {}
    val usesSystemAudioSinkForVirtualOutput: Boolean = false
}
