package com.lanrhyme.micyou.mdns

import com.lanrhyme.micyou.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.net.InetAddress
import javax.jmdns.JmDNS
import javax.jmdns.ServiceEvent
import javax.jmdns.ServiceInfo
import javax.jmdns.ServiceListener
import javax.jmdns.ServiceTypeListener

class DesktopMDnsDiscovery : MDnsDiscovery {
    
    private val _discoveryState = MutableStateFlow(DiscoveryState.Idle)
    override val discoveryState: StateFlow<DiscoveryState> = _discoveryState.asStateFlow()
    
    private val _discoveredServices = MutableStateFlow<List<DiscoveredService>>(emptyList())
    override val discoveredServices: StateFlow<List<DiscoveredService>> = _discoveredServices.asStateFlow()
    
    private var jmdns: JmDNS? = null
    private var serviceListener: ServiceListener? = null
    private var typeListener: ServiceTypeListener? = null
    private var registeredServiceInfo: ServiceInfo? = null
    
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    
    private var isDiscovering = false
    private var isRegistered = false

    override fun publishService(
        port: Int,
        serviceName: String,
        txtRecords: Map<String, String>
    ) {
        scope.launch {
            try {
                if (isRegistered) {
                    Logger.w("DesktopMDnsDiscovery", "Service already registered, unregistering first")
                    unpublishService()
                }
                
                if (jmdns == null) {
                    jmdns = JmDNS.create(InetAddress.getLocalHost(), serviceName)
                }
                
                val txtRecordBytes = txtRecords.entries.fold(ByteArray(0)) { acc, (key, value) ->
                    acc + byteArrayOf(value.length.toByte()) + key.toByteArray() + value.toByteArray()
                }
                
                val serviceInfo = ServiceInfo.create(
                    MDnsConstants.SERVICE_TYPE,
                    serviceName,
                    port,
                    0,
                    0,
                    true,
                    txtRecordBytes
                )
                
                jmdns?.registerService(serviceInfo)
                registeredServiceInfo = serviceInfo
                isRegistered = true
                
                Logger.i("DesktopMDnsDiscovery", "Service registered: $serviceName on port $port")
            } catch (e: Exception) {
                Logger.e("DesktopMDnsDiscovery", "Failed to register service", e)
                _discoveryState.value = DiscoveryState.Error
            }
        }
    }

    override fun unpublishService() {
        scope.launch {
            try {
                jmdns?.unregisterAllServices()
                registeredServiceInfo = null
                isRegistered = false
                Logger.i("DesktopMDnsDiscovery", "Service unregistered")
            } catch (e: Exception) {
                Logger.e("DesktopMDnsDiscovery", "Failed to unregister service", e)
            }
        }
    }

    override fun startDiscovery() {
        if (isDiscovering) {
            Logger.w("DesktopMDnsDiscovery", "Discovery already running")
            return
        }
        
        scope.launch {
            try {
                _discoveryState.value = DiscoveryState.Scanning
                _discoveredServices.value = emptyList()
                
                if (jmdns == null) {
                    jmdns = JmDNS.create()
                }
                
                serviceListener = object : ServiceListener {
                    override fun serviceAdded(event: ServiceEvent?) {
                        event?.let {
                            Logger.d("DesktopMDnsDiscovery", "Service added: ${it.name}")
                            jmdns?.requestServiceInfo(it.type, it.name, true)
                        }
                    }
                    
                    override fun serviceRemoved(event: ServiceEvent?) {
                        event?.let {
                            Logger.d("DesktopMDnsDiscovery", "Service removed: ${it.name}")
                            removeService(it.name)
                        }
                    }
                    
                    override fun serviceResolved(event: ServiceEvent?) {
                        event?.let { evt ->
                            val info = evt.info
                            Logger.i("DesktopMDnsDiscovery", "Service resolved: ${info.name} at ${info.inet4Addresses?.firstOrNull()?.hostAddress}:${info.port}")
                            
                            val host = info.inet4Addresses?.firstOrNull()?.hostAddress
                            if (host != null) {
                                val txtRecords = mutableMapOf<String, String>()
                                val propNames = info.propertyNames
                                while (propNames?.hasMoreElements() == true) {
                                    val key = propNames.nextElement() as? String ?: continue
                                    info.getPropertyString(key)?.let { value ->
                                        txtRecords[key] = value
                                    }
                                }
                                
                                val discovered = DiscoveredService(
                                    instanceName = info.name,
                                    host = host,
                                    port = info.port,
                                    txtRecords = txtRecords
                                )
                                
                                addService(discovered)
                            }
                        }
                    }
                }
                
                jmdns?.addServiceListener(MDnsConstants.SERVICE_TYPE, serviceListener)
                isDiscovering = true
                
                Logger.i("DesktopMDnsDiscovery", "Discovery started")
            } catch (e: Exception) {
                Logger.e("DesktopMDnsDiscovery", "Failed to start discovery", e)
                _discoveryState.value = DiscoveryState.Error
            }
        }
    }

    private fun addService(service: DiscoveredService) {
        val currentList = _discoveredServices.value.toMutableList()
        val existingIndex = currentList.indexOfFirst { it.instanceName == service.instanceName }
        
        if (existingIndex >= 0) {
            currentList[existingIndex] = service
        } else {
            currentList.add(service)
        }
        
        _discoveredServices.value = currentList.sortedBy { it.instanceName }
    }

    private fun removeService(serviceName: String) {
        val currentList = _discoveredServices.value.toMutableList()
        currentList.removeAll { it.instanceName == serviceName }
        _discoveredServices.value = currentList.sortedBy { it.instanceName }
    }

    override fun stopDiscovery() {
        scope.launch {
            try {
                serviceListener?.let {
                    jmdns?.removeServiceListener(MDnsConstants.SERVICE_TYPE, it)
                }
                serviceListener = null
                typeListener = null
                isDiscovering = false
                _discoveryState.value = DiscoveryState.Idle
                
                Logger.i("DesktopMDnsDiscovery", "Discovery stopped")
            } catch (e: Exception) {
                Logger.e("DesktopMDnsDiscovery", "Failed to stop discovery", e)
            }
        }
    }

    override fun clearDiscoveredServices() {
        _discoveredServices.value = emptyList()
    }
    
    fun cleanup() {
        stopDiscovery()
        unpublishService()
        try {
            jmdns?.close()
        } catch (e: Exception) {
            Logger.e("DesktopMDnsDiscovery", "Failed to close JmDNS", e)
        }
        jmdns = null
        scope.cancel()
    }
}
