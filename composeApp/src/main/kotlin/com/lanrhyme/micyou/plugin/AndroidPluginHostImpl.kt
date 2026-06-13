package com.lanrhyme.micyou.plugin

import android.os.Build
import com.lanrhyme.micyou.AudioEngine
import com.lanrhyme.micyou.Settings
import com.lanrhyme.micyou.SettingsFactory

/**
 * Android PluginHost 实现。
 *
 * 继承 BasePluginHostImpl，提供 Android 特定的平台信息和数据通道实现。
 */
class AndroidPluginHostImpl(
    audioEngine: AudioEngine,
    settings: Settings = SettingsFactory.getSettings(),
    private val showSnackbarCallback: (String) -> Unit,
    private val showNotificationCallback: (String, String) -> Unit
) : BasePluginHostImpl(audioEngine, settings) {

    override val dataChannelProvider: PluginDataChannelProvider = AndroidPluginDataChannelProvider()

    override fun showSnackbar(message: String) {
        showSnackbarCallback(message)
    }

    override fun showNotification(title: String, message: String) {
        showNotificationCallback(title, message)
    }

    override val platform: PluginHost.PlatformInfo = PluginHost.PlatformInfo(
        name = "Android",
        version = Build.VERSION.RELEASE,
        isDesktop = false,
        isMobile = true
    )
}
