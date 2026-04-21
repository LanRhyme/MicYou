package com.lanrhyme.micyou.mdns

actual fun createMDnsDiscovery(): MDnsDiscovery {
    return DesktopMDnsDiscovery()
}
