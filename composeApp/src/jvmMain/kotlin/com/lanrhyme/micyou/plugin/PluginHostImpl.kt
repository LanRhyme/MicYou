package com.lanrhyme.micyou.plugin

import com.lanrhyme.micyou.AudioEngine
import com.lanrhyme.micyou.Settings
import com.lanrhyme.micyou.SettingsFactory

/**
 * JVM (桌面端) PluginHost 实现。
 *
 * 继承 BasePluginHostImpl，提供桌面端特定的平台信息和数据通道实现。
 */
class PluginHostImpl(
    audioEngine: AudioEngine,
    settings: Settings = SettingsFactory.getSettings(),
    private val showSnackbarCallback: (String) -> Unit,
    private val showNotificationCallback: (String, String) -> Unit,
    private val isDesktop: Boolean = true
) : BasePluginHostImpl(audioEngine, settings) {

    override val dataChannelProvider: PluginDataChannelProvider = PluginDataChannelProviderImpl()

    override fun showSnackbar(message: String) {
        showSnackbarCallback(message)
    }

    override fun showNotification(title: String, message: String) {
        showNotificationCallback(title, message)
    }

    override val platform: PluginHost.PlatformInfo = PluginHost.PlatformInfo(
        name = System.getProperty("os.name") ?: "Unknown",
        version = System.getProperty("os.version") ?: "Unknown",
        isDesktop = isDesktop,
        isMobile = !isDesktop
    )
}
