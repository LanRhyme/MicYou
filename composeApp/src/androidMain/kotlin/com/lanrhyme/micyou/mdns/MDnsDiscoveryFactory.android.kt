package com.lanrhyme.micyou.mdns

import com.lanrhyme.micyou.ContextHelper

actual fun createMDnsDiscovery(): MDnsDiscovery {
    val context = ContextHelper.getContext()
        ?: throw IllegalStateException("ContextHelper not initialized. Call ContextHelper.init() first.")
    return AndroidMDnsDiscovery(context)
}
