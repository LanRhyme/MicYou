package com.lanrhyme.micyou

import com.lanrhyme.micyou.platform.BlackHoleManager
import com.lanrhyme.micyou.platform.PipeWireManager
import com.lanrhyme.micyou.platform.PlatformInfo
import com.lanrhyme.micyou.platform.VBCableManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import micyou.composeapp.generated.resources.*
import micyou.composeapp.generated.resources.Res
import org.jetbrains.compose.resources.getString

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
        _installProgress.value = null
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
                _installProgress.value = getString(Res.string.installOsNotSupported)
                delay(3000)
                _installProgress.value = null
            }
        }
        Unit
    }
    
    private suspend fun installLinuxVirtualDevice() {
        _installProgress.value = getString(Res.string.installCheckingLinux)
        
        try {
            if (PipeWireManager.isInstalled()) {
                _installProgress.value = getString(Res.string.installLinuxExists)
                delay(1000)
                _installProgress.value = getString(Res.string.installConfigComplete)
                delay(1000)
                _installProgress.value = null
                return
            }
            
            _installProgress.value = getString(Res.string.installCreatingDevice)
    val success = PipeWireManager.setup()
            
            if (success) {
                _installProgress.value = getString(Res.string.installDeviceCreated)
                delay(1000)
                _installProgress.value = getString(Res.string.installConfigComplete)
                delay(1000)
                _installProgress.value = null
            } else {
                _installProgress.value = getString(Res.string.installDeviceFailed)
                delay(3000)
                _installProgress.value = null
            }
        } catch (e: Exception) {
            Logger.e("VirtualAudioDeviceManager", "Installation error: ${e.message}", e)
            _installProgress.value = getString(Res.string.installError, e.message ?: "Unknown error")
            delay(2000)
            _installProgress.value = null
        }
    }
    
    private suspend fun handleMacOSInstallation() {
        if (BlackHoleManager.isInstalled()) {
            _installProgress.value = getString(Res.string.blackHoleInstalled)
            delay(2000)
            _installProgress.value = null
        } else {
            _installProgress.value = getString(Res.string.blackHoleNotInstalled)
            delay(3000)
            _installProgress.value = getString(Res.string.blackHoleInstallHint)
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
}
