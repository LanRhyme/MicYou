package com.lanrhyme.micyou

/**
 * Platform adaptor for platform-specific operations.
 * Abstracts OS-dependent logic like audio redirection, ADB commands, etc.
 */
expect object PlatformAdaptor {
    /**
     * Configure audio output environment.
     * On Linux, this may redirect audio to a virtual device.
     * Returns a token representing the previous state, or null.
     */
    fun configureAudioOutput(): Any?

    /**
     * Restore audio output environment.
     * @param token The token returned by configureAudioOutput.
     */
    fun restoreAudioOutput(token: Any?)
    
    /**
     * Execute ADB reverse for USB connection.
     * @param port The port to reverse.
     * @return true if successful or not needed, false if failed.
     */
    fun runAdbReverse(port: Int): Boolean
    
    /**
     * Clean up temporary files.
     */
    fun cleanupTempFiles()

    /**
     * Whether the platform uses system audio sink as virtual output.
     * If true, audio should be written to the system output even if monitoring is disabled.
     */
    val usesSystemAudioSinkForVirtualOutput: Boolean

    /**
     * Shared auth token for connection authentication.
     * Empty token means authentication is disabled.
     */
    fun setAuthToken(token: String)
    fun getAuthToken(): String

    fun setVideoProfile(profile: VideoProfile)
    fun getVideoProfile(): VideoProfile
    fun setVideoQuality(quality: Int)
    fun getVideoQuality(): Int
}
