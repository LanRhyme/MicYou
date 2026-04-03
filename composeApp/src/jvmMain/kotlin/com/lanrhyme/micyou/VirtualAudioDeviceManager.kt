package com.lanrhyme.micyou

import com.lanrhyme.micyou.platform.BlackHoleManager
import com.lanrhyme.micyou.platform.PipeWireManager
import com.lanrhyme.micyou.platform.PlatformInfo
import com.lanrhyme.micyou.platform.VBCableManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

object VirtualAudioDeviceManager {
    private val _installProgress = MutableStateFlow<String?>(null)
    val installProgress = _installProgress.asStateFlow()

    fun isVirtualDeviceInstalled(): Boolean {
        return when (PlatformInfo.currentOS) {
            PlatformInfo.OS.WINDOWS -> VBCableManager.isInstalled()
            PlatformInfo.OS.LINUX -> PipeWireManager.isInstalled()
            PlatformInfo.OS.MACOS -> BlackHoleManager.isInstalled()
            else -> false
        }
    }

    fun resetInstallState() {
        when (PlatformInfo.currentOS) {
            PlatformInfo.OS.WINDOWS -> VBCableManager.resetInstallState()
            else -> Logger.w("VirtualAudioDeviceManager", "Reset not supported on current platform")
        }
    }

    suspend fun installVirtualDevice() = withContext(Dispatchers.IO) {
        when (PlatformInfo.currentOS) {
            PlatformInfo.OS.WINDOWS -> {
                VBCableManager.install { progress -> _installProgress.value = progress }
            }
            PlatformInfo.OS.LINUX -> {
                installLinuxVirtualDevice()
            }
            PlatformInfo.OS.MACOS -> {
                handleMacOSInstallation()
            }
            PlatformInfo.OS.OTHER -> {
                val strings = getStrings(getCurrentLanguage())
                _installProgress.value = strings.installOsNotSupported
                delay(3000)
                _installProgress.value = null
            }
        }
    }
    
    private suspend fun installLinuxVirtualDevice() {
        val strings = getStrings(getCurrentLanguage())
        
        _installProgress.value = strings.installCheckingLinux
        
        try {
            if (PipeWireManager.isInstalled()) {
                _installProgress.value = strings.installLinuxExists
                delay(1000)
                _installProgress.value = strings.installConfigComplete
                delay(1000)
                _installProgress.value = null
                return
            }
            
            _installProgress.value = strings.installCreatingDevice
            
            val success = PipeWireManager.setup()
            
            if (success) {
                _installProgress.value = strings.installDeviceCreated
                delay(1000)
                _installProgress.value = strings.installConfigComplete
                delay(1000)
                _installProgress.value = null
            } else {
                _installProgress.value = strings.installDeviceFailed
                delay(3000)
                _installProgress.value = null
            }
        } catch (e: Exception) {
            Logger.e("VirtualAudioDeviceManager", "Installation error: ${e.message}", e)
            _installProgress.value = strings.installError.replace("%s", e.message ?: "Unknown error")
            delay(2000)
            _installProgress.value = null
        }
    }
    
    private suspend fun handleMacOSInstallation() {
        val strings = getStrings(getCurrentLanguage())
        
        if (BlackHoleManager.isInstalled()) {
            _installProgress.value = strings.blackHoleInstalled
            delay(2000)
            _installProgress.value = null
        } else {
            _installProgress.value = strings.blackHoleNotInstalled
            delay(3000)
            _installProgress.value = strings.blackHoleInstallHint
            delay(3000)
            _installProgress.value = null
        }
    }

    suspend fun setSystemDefaultMicrophone(toCable: Boolean = true) = withContext(Dispatchers.IO) {
        when (PlatformInfo.currentOS) {
            PlatformInfo.OS.WINDOWS -> {
                if (toCable) VBCableManager.setDefaultMicrophone() else VBCableManager.restoreDefaultMicrophone()
            }
            PlatformInfo.OS.LINUX -> {
                if (!toCable) PipeWireManager.cleanup()
            }
            PlatformInfo.OS.MACOS -> {
                if (toCable) setMacOSDefaultMicrophone() else BlackHoleManager.restoreOriginalInputDevice()
            }
            PlatformInfo.OS.OTHER -> Logger.w("VirtualAudioDeviceManager", "Current OS cannot set default microphone")
        }
    }

    private suspend fun setMacOSDefaultMicrophone() {
        if (!BlackHoleManager.isSwitchAudioSourceInstalled()) {
            Logger.w("VirtualAudioDeviceManager", "macOS: switchaudio-osx is not installed")
            Logger.w("VirtualAudioDeviceManager", "Please run `brew install switchaudio-osx` to install!")
            return
        }

        if (!BlackHoleManager.isInstalled()) {
            Logger.e("VirtualAudioDeviceManager", "macOS: BlackHole is not installed")
            return
        }

        BlackHoleManager.saveCurrentInputDevice()

        val json = BlackHoleManager.getInputDevicesJson()
        if (json == null) {
            Logger.e("VirtualAudioDeviceManager", "macOS: Failed to load input device list")
            return
        }

        val blackHoleDevice = BlackHoleManager.findBlackHoleInJson(json)
        if (blackHoleDevice == null) {
            Logger.e("VirtualAudioDeviceManager", "macOS: Cannot find BlackHole virtual input device")
            return
        }
        
        Logger.i("VirtualAudioDeviceManager", "macOS: Found BlackHole virtual device: ${blackHoleDevice.name} (ID: ${blackHoleDevice.id})")

        val success = BlackHoleManager.setDefaultInputDevice(blackHoleDevice.id)
        if (success) {
            Logger.i("VirtualAudioDeviceManager", "macOS: Successfully set default microphone to BlackHole")
        } else {
            Logger.e("VirtualAudioDeviceManager", "macOS: Failed to set default microphone")
        }
    }

    fun uninstallVBCable() {
        when (PlatformInfo.currentOS) {
            PlatformInfo.OS.WINDOWS -> kotlinx.coroutines.GlobalScope.launch(Dispatchers.IO) {
                VBCableManager.uninstall { progress -> _installProgress.value = progress }
            }
            else -> Logger.w("VirtualAudioDeviceManager", "Uninstall not supported on current platform")
        }
    }
    
    suspend fun uninstallVirtualDevice() = withContext(Dispatchers.IO) {
        when (PlatformInfo.currentOS) {
            PlatformInfo.OS.WINDOWS -> {
                VBCableManager.uninstall { progress -> _installProgress.value = progress }
            }
            else -> {
                _installProgress.value = "Uninstall not supported on this platform"
                delay(2000)
                _installProgress.value = null
            }
        }
    }
    
    private fun getCurrentLanguage(): AppLanguage {
        val settings = SettingsFactory.getSettings()
        val savedLanguageName = settings.getString("language", AppLanguage.System.name)
        return try { 
            AppLanguage.valueOf(savedLanguageName) 
        } catch(e: Exception) { 
            AppLanguage.System 
        }
    }
}
