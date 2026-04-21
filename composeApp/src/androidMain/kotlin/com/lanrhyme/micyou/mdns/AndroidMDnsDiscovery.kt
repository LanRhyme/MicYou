package com.lanrhyme.micyou.mdns

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import com.lanrhyme.micyou.Logger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class AndroidMDnsDiscovery(private val context: Context) : MDnsDiscovery {
    
    private val nsdManager: NsdManager by lazy {
        context.getSystemService(Context.NSD_SERVICE) as NsdManager
    }
    
    private val _discoveryState = MutableStateFlow(DiscoveryState.Idle)
    override val discoveryState: StateFlow<DiscoveryState> = _discoveryState.asStateFlow()
    
    private val _discoveredServices = MutableStateFlow<List<DiscoveredService>>(emptyList())
    override val discoveredServices: StateFlow<List<DiscoveredService>> = _discoveredServices.asStateFlow()
    
    private var registrationListener: NsdManager.RegistrationListener? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private var resolveListeners = mutableMapOf<String, NsdManager.ResolveListener>()
    
    private var isDiscovering = false
    private var isRegistered = false

    override fun publishService(
        port: Int,
        serviceName: String,
        txtRecords: Map<String, String>
    ) {
        if (isRegistered) {
            Logger.w("AndroidMDnsDiscovery", "Service already registered, unregistering first")
            unpublishService()
        }
        
        val serviceInfo = NsdServiceInfo().apply {
            this.serviceName = serviceName
            this.serviceType = MDnsConstants.SERVICE_TYPE_ANDROID
            this.port = port
            txtRecords.forEach { (key, value) ->
                setAttribute(key, value)
            }
        }
        
        registrationListener = object : NsdManager.RegistrationListener {
            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo?, errorCode: Int) {
                Logger.e("AndroidMDnsDiscovery", "Service registration failed: $errorCode")
                _discoveryState.value = DiscoveryState.Error
            }
            
            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo?, errorCode: Int) {
                Logger.e("AndroidMDnsDiscovery", "Service unregistration failed: $errorCode")
            }
            
            override fun onServiceRegistered(serviceInfo: NsdServiceInfo?) {
                Logger.i("AndroidMDnsDiscovery", "Service registered: ${serviceInfo?.serviceName}")
                isRegistered = true
            }
            
            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo?) {
                Logger.i("AndroidMDnsDiscovery", "Service unregistered: ${serviceInfo?.serviceName}")
                isRegistered = false
            }
        }
        
        try {
            nsdManager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, registrationListener)
            Logger.i("AndroidMDnsDiscovery", "Registering service: $serviceName on port $port")
        } catch (e: Exception) {
            Logger.e("AndroidMDnsDiscovery", "Failed to register service", e)
            _discoveryState.value = DiscoveryState.Error
        }
    }

    override fun unpublishService() {
        registrationListener?.let {
            try {
                nsdManager.unregisterService(it)
            } catch (e: Exception) {
                Logger.e("AndroidMDnsDiscovery", "Failed to unregister service", e)
            }
            registrationListener = null
        }
        isRegistered = false
    }

    override fun startDiscovery() {
        if (isDiscovering) {
            Logger.w("AndroidMDnsDiscovery", "Discovery already running")
            return
        }
        
        _discoveryState.value = DiscoveryState.Scanning
        _discoveredServices.value = emptyList()
        
        discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onStartDiscoveryFailed(serviceType: String?, errorCode: Int) {
                Logger.e("AndroidMDnsDiscovery", "Discovery start failed: $errorCode")
                _discoveryState.value = DiscoveryState.Error
                isDiscovering = false
            }
            
            override fun onStopDiscoveryFailed(serviceType: String?, errorCode: Int) {
                Logger.e("AndroidMDnsDiscovery", "Discovery stop failed: $errorCode")
            }
            
            override fun onDiscoveryStarted(serviceType: String?) {
                Logger.i("AndroidMDnsDiscovery", "Discovery started")
                isDiscovering = true
            }
            
            override fun onDiscoveryStopped(serviceType: String?) {
                Logger.i("AndroidMDnsDiscovery", "Discovery stopped")
                isDiscovering = false
                if (_discoveryState.value == DiscoveryState.Scanning) {
                    _discoveryState.value = DiscoveryState.Idle
                }
            }
            
            override fun onServiceFound(serviceInfo: NsdServiceInfo?) {
                serviceInfo?.let { info ->
                    Logger.d("AndroidMDnsDiscovery", "Service found: ${info.serviceName}")
                    resolveService(info)
                }
            }
            
            override fun onServiceLost(serviceInfo: NsdServiceInfo?) {
                serviceInfo?.let { info ->
                    Logger.d("AndroidMDnsDiscovery", "Service lost: ${info.serviceName}")
                    removeService(info.serviceName)
                }
            }
        }
        
        try {
            nsdManager.discoverServices(
                MDnsConstants.SERVICE_TYPE_ANDROID,
                NsdManager.PROTOCOL_DNS_SD,
                discoveryListener
            )
        } catch (e: Exception) {
            Logger.e("AndroidMDnsDiscovery", "Failed to start discovery", e)
            _discoveryState.value = DiscoveryState.Error
        }
    }

    private fun resolveService(serviceInfo: NsdServiceInfo) {
        val serviceName = serviceInfo.serviceName
        
        val resolveListener = object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo?, errorCode: Int) {
                Logger.e("AndroidMDnsDiscovery", "Resolve failed for $serviceName: $errorCode")
                resolveListeners.remove(serviceName)
            }
            
            override fun onServiceResolved(serviceInfo: NsdServiceInfo?) {
                serviceInfo?.let { info ->
                    Logger.i("AndroidMDnsDiscovery", "Service resolved: ${info.serviceName} at ${info.host?.hostAddress}:${info.port}")
                    
                    val host = info.host?.hostAddress
                    if (host != null) {
                        val txtRecords = mutableMapOf<String, String>()
                        info.attributes?.forEach { (key, value) ->
                            txtRecords[key] = value?.toString(Charsets.UTF_8) ?: ""
                        }
                        
                        val discovered = DiscoveredService(
                            instanceName = info.serviceName,
                            host = host,
                            port = info.port,
                            txtRecords = txtRecords
                        )
                        
                        addService(discovered)
                    }
                }
                resolveListeners.remove(serviceName)
            }
        }
        
        resolveListeners[serviceName] = resolveListener
        
        try {
            nsdManager.resolveService(serviceInfo, resolveListener)
        } catch (e: Exception) {
            Logger.e("AndroidMDnsDiscovery", "Failed to resolve service", e)
            resolveListeners.remove(serviceName)
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
        
        _discoveredServices.value = currentList
    }

    private fun removeService(serviceName: String) {
        val currentList = _discoveredServices.value.toMutableList()
        currentList.removeAll { it.instanceName == serviceName }
        _discoveredServices.value = currentList
    }

    override fun stopDiscovery() {
        discoveryListener?.let {
            try {
                nsdManager.stopServiceDiscovery(it)
            } catch (e: Exception) {
                Logger.e("AndroidMDnsDiscovery", "Failed to stop discovery", e)
            }
            discoveryListener = null
        }
        
        resolveListeners.clear()
        isDiscovering = false
        _discoveryState.value = DiscoveryState.Idle
    }

    override fun clearDiscoveredServices() {
        _discoveredServices.value = emptyList()
    }
    
    fun cleanup() {
        stopDiscovery()
        unpublishService()
    }
}
