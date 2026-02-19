package com.lanrhyme.micyou

/**
 * 平台相关功能的 JVM 实现。
 * 处理音频设备重定向和 ADB 操作。
 */
actual object PlatformAdaptor {
    @Volatile
    private var authToken: String = ""
    @Volatile
    private var videoProfile: VideoProfile = VideoProfile.FHD_1080P_30
    @Volatile
    private var videoQuality: Int = 85

    actual fun configureAudioOutput(): Any? {
        if (PlatformUtils.isLinux) {
            val original = PlatformUtils.getDefaultSink()
            Logger.i("PlatformAdaptor", "Linux: 保存原始音频输出: $original")
            if (PlatformUtils.redirectAudioToVirtualDevice()) {
                Logger.i("PlatformAdaptor", "Linux: 音频已重定向到虚拟设备")
                return original
            } else {
                Logger.w("PlatformAdaptor", "Linux: 音频重定向失败")
            }
        }
        return null
    }

    actual fun restoreAudioOutput(token: Any?) {
        if (PlatformUtils.isLinux && token is String) {
            Logger.i("PlatformAdaptor", "Linux: 恢复原始音频输出: $token")
            PlatformUtils.restoreDefaultSink(token)
        }
    }
    
    actual fun runAdbReverse(port: Int): Boolean {
        return try {
            AdbManager.runAdbReverse(port)
            true
        } catch (e: Exception) {
            Logger.e("PlatformAdaptor", "ADB reverse 失败", e)
            false
        }
    }
    
    actual fun cleanupTempFiles() {
        PlatformUtils.cleanupTempFiles()
    }

    actual val usesSystemAudioSinkForVirtualOutput: Boolean
        get() = PlatformUtils.isLinux

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
