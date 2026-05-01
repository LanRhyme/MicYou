package com.lanrhyme.micyou

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

actual class DeviceDiscoveryManager actual constructor() {
    private val _discoveredDevices = MutableStateFlow<List<DiscoveredDevice>>(emptyList())
    actual val discoveredDevices: StateFlow<List<DiscoveredDevice>> = _discoveredDevices.asStateFlow()

    private val _isDiscovering = MutableStateFlow(false)
    actual val isDiscovering: StateFlow<Boolean> = _isDiscovering.asStateFlow()

    actual fun startDiscovery() {
        // No-op on Desktop — desktop is the server, not the client
    }

    actual fun stopDiscovery() {
        _discoveredDevices.value = emptyList()
        _isDiscovering.value = false
    }
}
