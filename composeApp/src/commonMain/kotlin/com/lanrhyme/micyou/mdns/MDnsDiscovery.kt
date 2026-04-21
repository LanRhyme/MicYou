package com.lanrhyme.micyou.mdns

data class DiscoveredService(
    val instanceName: String,
    val host: String,
    val port: Int,
    val txtRecords: Map<String, String> = emptyMap()
)

enum class DiscoveryState {
    Idle,
    Scanning,
    Error
}

interface MDnsDiscovery {
    val discoveryState: kotlinx.coroutines.flow.StateFlow<DiscoveryState>
    val discoveredServices: kotlinx.coroutines.flow.StateFlow<List<DiscoveredService>>
    
    fun publishService(
        port: Int,
        serviceName: String,
        txtRecords: Map<String, String> = emptyMap()
    )
    
    fun unpublishService()
    
    fun startDiscovery()
    
    fun stopDiscovery()
    
    fun clearDiscoveredServices()
}

object MDnsConstants {
    const val SERVICE_TYPE = "_micyou._tcp.local."
    const val SERVICE_TYPE_ANDROID = "_micyou._tcp"
    const val DEFAULT_SERVICE_NAME_PREFIX = "MicYou"
}
