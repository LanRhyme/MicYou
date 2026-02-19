package com.lanrhyme.micyou

actual object PlatformAdaptor {
    @Volatile
    private var authToken: String = ""
    @Volatile
    private var videoProfile: VideoProfile = VideoProfile.FHD_1080P_30
    @Volatile
    private var videoQuality: Int = 85

    actual fun configureAudioOutput(): Any? = null
    actual fun restoreAudioOutput(token: Any?) {}
    actual fun runAdbReverse(port: Int): Boolean = true // Android doesn't need reverse
    actual fun cleanupTempFiles() {}
    actual val usesSystemAudioSinkForVirtualOutput: Boolean = false
    actual fun setAuthToken(token: String) {
        authToken = token.trim()
    }
    actual fun getAuthToken(): String = authToken
    actual fun setVideoProfile(profile: VideoProfile) {
        videoProfile = profile
    }
    actual fun getVideoProfile(): VideoProfile = videoProfile
    actual fun setVideoQuality(quality: Int) {
        videoQuality = quality.coerceIn(30, 95)
    }
    actual fun getVideoQuality(): Int = videoQuality
}
