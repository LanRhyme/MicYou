package com.lanrhyme.micyou

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

actual class DeviceDiscoveryManager actual constructor() {
    private val _discoveredDevices = MutableStateFlow<List<DiscoveredDevice>>(emptyList())
    actual val discoveredDevices: StateFlow<List<DiscoveredDevice>> = _discoveredDevices.asStateFlow()

    private val _isDiscovering = MutableStateFlow(false)
    actual val isDiscovering: StateFlow<Boolean> = _isDiscovering.asStateFlow()

    private var nsdManager: NsdManager? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private var discoveryActive = false
    private val pendingResolution = mutableSetOf<String>()

    private val resolveListener = object : NsdManager.ResolveListener {
        override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
            Logger.w("DeviceDiscovery", "Resolve failed: $errorCode for ${serviceInfo.serviceName}")
            pendingResolution.remove(serviceInfo.serviceName)
        }

        override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
            pendingResolution.remove(serviceInfo.serviceName)
            val host = serviceInfo.host?.hostAddress ?: return
            val port = serviceInfo.port
            val name = serviceInfo.serviceName

            Logger.i("DeviceDiscovery", "Resolved: $name at $host:$port")

            _discoveredDevices.value = _discoveredDevices.value.toMutableList().apply {
                removeAll { it.hostAddress == host && it.port == port }
                add(DiscoveredDevice(name = name, hostAddress = host, port = port))
            }
        }
    }

    actual fun startDiscovery() {
        if (discoveryActive) return
        val context = ContextHelper.getContext() ?: run {
            Logger.w("DeviceDiscovery", "No application context available")
            return
        }

        nsdManager = context.getSystemService(Context.NSD_SERVICE) as? NsdManager ?: run {
            Logger.w("DeviceDiscovery", "NsdManager not available")
            return
        }

        discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {
                Logger.i("DeviceDiscovery", "Discovery started for $serviceType")
                discoveryActive = true
                _isDiscovering.value = true
            }

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                val name = serviceInfo.serviceName
                if (name !in pendingResolution) {
                    pendingResolution.add(name)
                    try {
                        nsdManager?.resolveService(serviceInfo, resolveListener)
                    } catch (e: Exception) {
                        Logger.w("DeviceDiscovery", "Failed to resolve $name: ${e.message}")
                        pendingResolution.remove(name)
                    }
                }
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                Logger.i("DeviceDiscovery", "Service lost: ${serviceInfo.serviceName}")
                _discoveredDevices.value = _discoveredDevices.value.toMutableList().apply {
                    removeAll { it.name == serviceInfo.serviceName }
                }
            }

            override fun onDiscoveryStopped(serviceType: String) {
                Logger.i("DeviceDiscovery", "Discovery stopped")
                discoveryActive = false
                _isDiscovering.value = false
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Logger.w("DeviceDiscovery", "Discovery start failed: $errorCode")
                discoveryActive = false
                _isDiscovering.value = false
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                Logger.w("DeviceDiscovery", "Discovery stop failed: $errorCode")
            }
        }

        try {
            nsdManager?.discoverServices("_micyou._tcp.", NsdManager.PROTOCOL_DNS_SD, discoveryListener)
        } catch (e: Exception) {
            Logger.e("DeviceDiscovery", "Failed to start discovery", e)
            discoveryActive = false
        }
    }

    actual fun stopDiscovery() {
        if (!discoveryActive) return
        try {
            discoveryListener?.let { nsdManager?.stopServiceDiscovery(it) }
        } catch (e: Exception) {
            Logger.w("DeviceDiscovery", "Error stopping discovery: ${e.message}")
        }
        discoveryListener = null
        discoveryActive = false
        _isDiscovering.value = false
        pendingResolution.clear()
        // Don't clear device list here — let restartDiscovery() manage it
    }
}
