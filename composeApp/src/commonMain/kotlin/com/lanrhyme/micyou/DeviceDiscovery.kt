package com.lanrhyme.micyou

import kotlinx.coroutines.flow.StateFlow

data class DiscoveredDevice(
    val name: String,
    val hostAddress: String,
    val port: Int
)

expect class DeviceDiscoveryManager() {
    val discoveredDevices: StateFlow<List<DiscoveredDevice>>
    val isDiscovering: StateFlow<Boolean>
    fun startDiscovery()
    fun stopDiscovery()
}
