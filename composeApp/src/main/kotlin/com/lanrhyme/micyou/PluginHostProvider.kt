package com.lanrhyme.micyou

import com.lanrhyme.micyou.plugin.BasePluginHostImpl
import com.lanrhyme.micyou.plugin.DataChannelConfig
import com.lanrhyme.micyou.plugin.PluginDataChannel
import com.lanrhyme.micyou.plugin.PluginDataChannelProvider
import com.lanrhyme.micyou.plugin.PluginHost

class StubPluginHostImpl(
    audioEngine: AudioEngine,
    settings: Settings,
    private val showSnackbarCallback: (String) -> Unit,
    private val showNotificationCallback: (String, String) -> Unit
) : BasePluginHostImpl(audioEngine, settings) {

    override val dataChannelProvider: PluginDataChannelProvider = object : PluginDataChannelProvider {
        override fun createChannel(id: String, config: DataChannelConfig): PluginDataChannel {
            throw UnsupportedOperationException("Data channel not supported")
        }
        override fun getChannel(id: String): PluginDataChannel? = null
        override fun closeChannel(id: String) {}
        override fun closeAllChannels() {}
    }

    override fun showSnackbar(message: String) {
        showSnackbarCallback(message)
    }

    override fun showNotification(title: String, message: String) {
        showNotificationCallback(title, message)
    }

    override val platform: PluginHost.PlatformInfo = PluginHost.PlatformInfo(
        name = "Android",
        version = getAppVersion(),
        isDesktop = false,
        isMobile = true
    )
}

fun createPluginHost(
    audioEngine: AudioEngine,
    showSnackbarCallback: (String) -> Unit,
    showNotificationCallback: (String, String) -> Unit
): PluginHost {
    return StubPluginHostImpl(audioEngine, SettingsFactory.getSettings(), showSnackbarCallback, showNotificationCallback)
}
