package com.lanrhyme.micyou.platform

import com.lanrhyme.micyou.AppLanguage
import com.lanrhyme.micyou.Logger
import com.lanrhyme.micyou.SettingsFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.sound.sampled.AudioSystem
import micyou.composeapp.generated.resources.*
import micyou.composeapp.generated.resources.Res
import org.jetbrains.compose.resources.getString

enum class VBCableInstallError {
    None,
    AlreadyInstalling,
    InstallerNotFound,
    DownloadFailed,
    ExtractionFailed,
    UacDenied,
    InstallationTimeout,
    ConfigurationFailed,
    DeviceNotDetected,
    Unknown
}

data class VBCableInstallResult(
    val success: Boolean,
    val error: VBCableInstallError = VBCableInstallError.None,
    val message: String = ""
)

object VBCableManager {
    private const val CABLE_OUTPUT_NAME = "CABLE Output"
    private const val CABLE_INPUT_NAME = "CABLE Input"
    private const val INSTALLER_NAME = "VBCABLE_Setup_x64.exe"
    private const val SOUND_VOLUME_VIEW_NAME = "SoundVolumeView.exe"
    private const val KEY_ORIGINAL_SPEAKER = "original_speaker"

    private val settings = SettingsFactory.getSettings()
    
    private val isInstalling = AtomicBoolean(false)
    private var originalSpeaker: String? = null
    
    init {
        val savedSpeaker = settings.getString(KEY_ORIGINAL_SPEAKER, "")
        originalSpeaker = if (savedSpeaker.isNotBlank()) savedSpeaker else null
    }
    
    private val savedLanguageName: String
        get() = settings.getString("language", AppLanguage.System.name)
    
    private val language: AppLanguage
        get() = try { AppLanguage.valueOf(savedLanguageName) } catch(e: Exception) { AppLanguage.System }

    fun isInstalled(): Boolean {
        if (!PlatformInfo.isWindows) return false
        
        return try {
            // First check: AudioSystem mixer detection
            val mixers = AudioSystem.getMixerInfo()
    val mixerDetected = mixers.any { 
                it.name.contains(CABLE_OUTPUT_NAME, ignoreCase = true) || 
                it.name.contains(CABLE_INPUT_NAME, ignoreCase = true) 
            }
            
            if (!mixerDetected) return false
            
            // Second check: Registry verification to avoid false positives
            // from cached/ghost devices after uninstallation
            val registryPaths = listOf(
                "HKLM\\SYSTEM\\CurrentControlSet\\Services\\VB-Cable",
                "HKLM\\SOFTWARE\\VB-Audio\\Cable",
                "HKLM\\SOFTWARE\\VB-Audio\\VB-Cable"
            )
    val registryFound = registryPaths.any { regPath ->
                try {
                    val process = ProcessBuilder(
                        "reg", "query", regPath
                    ).redirectErrorStream(true).start()
                    
                    process.waitFor(3, TimeUnit.SECONDS)
                    process.exitValue() == 0
                } catch (e: Exception) {
                    false
                }
            }
            
            registryFound
        } catch (e: Exception) {
            Logger.e("VBCableManager", "Failed to check VB-Cable installation", e)
            false
        }
    }

    fun isInitialized(): Boolean = isInstalled()

    fun markInitialized() {
    }

    private fun getSoundVolumeViewPath(): File? {
        val baseDir = File(System.getProperty("user.dir"))
    val toolsDir = File(baseDir, "tools")
    val svvInTools = File(toolsDir, SOUND_VOLUME_VIEW_NAME)
        if (svvInTools.exists()) return svvInTools
        
        val svvInBase = File(baseDir, SOUND_VOLUME_VIEW_NAME)
        if (svvInBase.exists()) return svvInBase
        
        return null
    }

    private fun getVBCableSetupPath(): File? {
        val baseDir = File(System.getProperty("user.dir"))
    val setupInDriverPack = File(baseDir, "VBCABLE_Driver_Pack45/$INSTALLER_NAME")
        if (setupInDriverPack.exists()) return setupInDriverPack
        
        val setupInBase = File(baseDir, INSTALLER_NAME)
        if (setupInBase.exists()) return setupInBase
        
        return null
    }

    private fun getVBCableLicenseKey(): String? {
        return try {
            val keyPaths = listOf(
                "SOFTWARE\\VB-Audio\\VB-Cable",
                "SOFTWARE\\WOW6432Node\\VB-Audio\\VB-Cable"
            )
            
            for (keyPath in keyPaths) {
                try {
                    val process = ProcessBuilder(
                        "reg", "query", "HKLM\\$keyPath"
                    ).redirectErrorStream(true).start()
    val output = process.inputStream.bufferedReader().readText()
                    process.waitFor(5, TimeUnit.SECONDS)
                    
                    for (valueName in listOf("License", "Key", "Serial", "ID", "GUID")) {
                        val regex = Regex("$valueName\\s+REG_\\w+\\s+(.+)", RegexOption.IGNORE_CASE)
    val match = regex.find(output)
                        if (match != null) {
                            return match.groupValues[1].trim()
                        }
                    }
                } catch (e: Exception) {
                    continue
                }
            }
            null
        } catch (e: Exception) {
            Logger.e("VBCableManager", "Failed to get VB-Cable license key", e)
            null
        }
    }

    private fun getDefaultPlaybackDevice(): String? {
        val svv = getSoundVolumeViewPath() ?: return null
        
        return try {
            val process = ProcessBuilder(
                svv.absolutePath, "/GetColumnValue", "DefaultRenderDevice", "Name"
            ).redirectErrorStream(true).start()
            
            process.waitFor(10, TimeUnit.SECONDS)
    val output = process.inputStream.readAllBytes().toString(Charsets.UTF_16LE).trim()
    val result = if (output.startsWith("\ufeff")) output.substring(1) else output
            if (result.isNotBlank()) result else null
        } catch (e: Exception) {
            Logger.e("VBCableManager", "Failed to get default playback device", e)
            null
        }
    }

    private fun setDefaultPlaybackDevice(deviceName: String): Boolean {
        val svv = getSoundVolumeViewPath() ?: return false
        
        return try {
            val process = ProcessBuilder(
                svv.absolutePath, "/SetDefault", deviceName, "all"
            ).redirectErrorStream(true).start()
    val exitCode = process.waitFor()
            if (exitCode == 0) {
                Logger.i("VBCableManager", "Restored default speaker: $deviceName")
                true
            } else {
                Logger.w("VBCableManager", "Failed to restore default speaker")
                false
            }
        } catch (e: Exception) {
            Logger.e("VBCableManager", "Error setting default playback device", e)
            false
        }
    }

    private fun disableCableInput16ch(): Boolean {
        val svv = getSoundVolumeViewPath() ?: return false
        
        return try {
            val process = ProcessBuilder(
                svv.absolutePath, "/Disable", "VB-Audio Virtual Cable\\Device\\CABLE In 16 Ch\\Render"
            ).redirectErrorStream(true).start()
            
            process.waitFor(10, TimeUnit.SECONDS)
            Logger.i("VBCableManager", "Disabled CABLE Input 16ch")
            true
        } catch (e: Exception) {
            Logger.e("VBCableManager", "Failed to disable CABLE Input 16ch", e)
            false
        }
    }

    private fun setDeviceFormat(deviceId: String, bits: Int, sampleRate: Int, channels: Int): Boolean {
        val svv = getSoundVolumeViewPath() ?: return false
        
        return try {
            val process = ProcessBuilder(
                svv.absolutePath, "/SetDefaultFormat", deviceId,
                bits.toString(), sampleRate.toString(), channels.toString()
            ).redirectErrorStream(true).start()
    val exitCode = process.waitFor()
            if (exitCode == 0) {
                Logger.i("VBCableManager", "Set $deviceId format: ${bits}bit, ${sampleRate}Hz, ${channels}ch")
                true
            } else {
                Logger.w("VBCableManager", "Failed to set device format for $deviceId")
                false
            }
        } catch (e: Exception) {
            Logger.e("VBCableManager", "Error setting device format", e)
            false
        }
    }

    private fun setCableOutputAsDefaultMic(): Boolean {
        val svv = getSoundVolumeViewPath() ?: return false
        
        return try {
            val process = ProcessBuilder(
                svv.absolutePath, "/SetDefault",
                "VB-Audio Virtual Cable\\Device\\CABLE Output\\Capture", "all"
            ).redirectErrorStream(true).start()
    val exitCode = process.waitFor()
            if (exitCode == 0) {
                Logger.i("VBCableManager", "Set CABLE Output as default microphone")
                true
            } else {
                Logger.w("VBCableManager", "Failed to set CABLE Output as default mic")
                false
            }
        } catch (e: Exception) {
            Logger.e("VBCableManager", "Error setting default microphone", e)
            false
        }
    }

    private fun configureVBCableDevices(sampleRate: Int = 48000, channelCount: Int = 2): Boolean {
        var success = true
        
        if (!disableCableInput16ch()) {
            Logger.w("VBCableManager", "Failed to disable CABLE Input 16ch, continuing...")
        }
        
        if (!setDeviceFormat("VB-Audio Virtual Cable\\Device\\CABLE Input\\Render", 16, sampleRate, channelCount)) {
            Logger.w("VBCableManager", "Failed to set CABLE Input format")
            success = false
        }
        
        if (!setDeviceFormat("VB-Audio Virtual Cable\\Device\\CABLE Output\\Capture", 16, sampleRate, 1)) {
            Logger.w("VBCableManager", "Failed to set CABLE Output format")
            success = false
        }
        
        return success
    }

    fun configureDevices(sampleRate: Int, channelCount: Int): VBCableInstallResult {
        if (!isInstalled()) {
            return VBCableInstallResult(false, VBCableInstallError.DeviceNotDetected, "VB-Cable not installed")
        }
    val configSuccess = configureVBCableDevices(sampleRate, channelCount)
    val micSuccess = setCableOutputAsDefaultMic()
        
        return if (configSuccess && micSuccess) {
            VBCableInstallResult(true, VBCableInstallError.None, "Configuration complete")
        } else {
            VBCableInstallResult(false, VBCableInstallError.ConfigurationFailed, 
                "Partial configuration failure: format=$configSuccess, mic=$micSuccess")
        }
    }

    suspend fun install(progressCallback: (String?) -> Unit): VBCableInstallResult = withContext(Dispatchers.IO) {
        if (!isInstalling.compareAndSet(false, true)) {
            Logger.w("VBCableManager", "Installation already in progress")
            progressCallback(getString(Res.string.installInstalling))
            return@withContext VBCableInstallResult(false, VBCableInstallError.AlreadyInstalling, "Installation already in progress")
        }
        
        try {
            installInternal(progressCallback)
        } finally {
            isInstalling.set(false)
        }
    }
    
    private suspend fun installInternal(progressCallback: (String?) -> Unit): VBCableInstallResult {
        if (isInstalled()) {
            Logger.i("VBCableManager", "VB-Cable already installed, configuring...")
            progressCallback(getString(Res.string.installConfiguring))
    val settings = SettingsFactory.getSettings()
    val savedSampleRateName = settings.getString("sample_rate", "Rate48000")
    val savedChannelCountName = settings.getString("channel_count", "Stereo")
    val sampleRate = when (savedSampleRateName) {
                "Rate16000" -> 16000
                "Rate44100" -> 44100
                else -> 48000
            }
    val channelCount = if (savedChannelCountName == "Mono") 1 else 2
            
            configureVBCableDevices(sampleRate, channelCount)
            setCableOutputAsDefaultMic()
            progressCallback(getString(Res.string.installConfigComplete))
            delay(1000)
            progressCallback(null)
            return VBCableInstallResult(true, VBCableInstallError.None, "Already installed and configured")
        }
    val currentSpeaker = getDefaultPlaybackDevice()
        if (currentSpeaker != null) {
            originalSpeaker = currentSpeaker
            settings.putString(KEY_ORIGINAL_SPEAKER, currentSpeaker)
            Logger.i("VBCableManager", "Saved current speaker: $currentSpeaker")
        }
        
        progressCallback(getString(Res.string.installCheckingPackage))
    var installerFile = getVBCableSetupPath()
    var downloadError: VBCableInstallError? = null
        var downloadErrorMsg: String? = null
        
        if (installerFile == null || !installerFile.exists()) {
            Logger.i("VBCableManager", "Installer not found locally. Attempting to download...")
            progressCallback(getString(Res.string.installDownloading))
    val downloadResult = downloadAndExtractInstaller()
            installerFile = downloadResult.file
            downloadError = downloadResult.error
            downloadErrorMsg = downloadResult.errorMessage
        }

        if (installerFile == null || !installerFile.exists()) {
            val error = downloadError ?: VBCableInstallError.InstallerNotFound
            val errorMsg = downloadErrorMsg ?: "Installer not found. Please place '$INSTALLER_NAME' in resources or ensure internet access."
            Logger.e("VBCableManager", "VB-Cable installer not found: $errorMsg")
            progressCallback(getString(Res.string.installDownloadFailed))
            delay(2000)
            progressCallback(null)
            return VBCableInstallResult(false, error, errorMsg)
        }

        Logger.i("VBCableManager", "Installing VB-Cable...")
        progressCallback(getString(Res.string.installInstalling))
        
        try {
            val licenseKey = getVBCableLicenseKey()
    val installArgs = mutableListOf("-i", "-h")
            
            if (licenseKey != null) {
                installArgs.addAll(listOf("-s", "-k", licenseKey))
                Logger.i("VBCableManager", "Using license key for installation")
            }
    val argsString = installArgs.joinToString("','")
    val powerShellCommand = "Start-Process -FilePath '${installerFile.absolutePath}' -ArgumentList '$argsString' -Verb RunAs -Wait"
            
            Logger.i("VBCableManager", "Running installer with elevated privileges via PowerShell")
    val processBuilder = ProcessBuilder("powershell", "-Command", powerShellCommand)
            processBuilder.redirectErrorStream(true)
    val process = processBuilder.start()
    val exitCode = process.waitFor(120, TimeUnit.SECONDS)
            
            if (!exitCode) {
                Logger.e("VBCableManager", "Installation process timed out")
                progressCallback(getString(Res.string.installError, "Installation timeout"))
                delay(2000)
                progressCallback(null)
                return VBCableInstallResult(false, VBCableInstallError.InstallationTimeout, 
                    "Installation process timed out after 120 seconds")
            }
            
            Logger.i("VBCableManager", "Waiting for device initialization...")
            progressCallback(getString(Res.string.vbcableWaitingDevice))
    var installed = false
            var waited = 0
            val maxWait = 30
            
            while (waited < maxWait) {
                delay(5000)
                waited += 5
                
                if (isInstalled()) {
                    installed = true
                    Logger.i("VBCableManager", "VB-Cable installation verified")
                    break
                } else {
                    Logger.i("VBCableManager", "Waiting for device... (${waited}s)")
                }
            }
            
            if (!installed && licenseKey != null) {
                Logger.i("VBCableManager", "Retrying without license key...")
    val retryPowerShellCommand = "Start-Process -FilePath '${installerFile.absolutePath}' -ArgumentList '-i','-h' -Verb RunAs -Wait"
                val retryProcess = ProcessBuilder(
                    "powershell", "-Command", retryPowerShellCommand
                ).redirectErrorStream(true).start()
                
                retryProcess.waitFor(60, TimeUnit.SECONDS)
                
                waited = 0
                while (waited < maxWait) {
                    delay(5000)
                    waited += 5
                    
                    if (isInstalled()) {
                        installed = true
                        Logger.i("VBCableManager", "VB-Cable installation verified (retry)")
                        break
                    }
                }
            }
            
            if (installed) {
                progressCallback(getString(Res.string.installConfiguring))
    val savedSampleRateName = settings.getString("sample_rate", "Rate48000")
    val savedChannelCountName = settings.getString("channel_count", "Stereo")
    val sampleRate = when (savedSampleRateName) {
                    "Rate16000" -> 16000
                    "Rate44100" -> 44100
                    else -> 48000
                }
    val channelCount = if (savedChannelCountName == "Mono") 1 else 2
                
                val configSuccess = configureVBCableDevices(sampleRate, channelCount)
    val micSuccess = setCableOutputAsDefaultMic()
                
                originalSpeaker?.let { speaker ->
                    setDefaultPlaybackDevice(speaker)
                }
                
                progressCallback(getString(Res.string.installConfigComplete))
                delay(2000)
                progressCallback(null)
                
                return if (configSuccess && micSuccess) {
                    VBCableInstallResult(true, VBCableInstallError.None, "Installation and configuration complete")
                } else {
                    VBCableInstallResult(true, VBCableInstallError.ConfigurationFailed, 
                        "Installed but configuration partially failed: format=$configSuccess, mic=$micSuccess")
                }
            } else {
                progressCallback(getString(Res.string.installNotCompleted))
                delay(2000)
                progressCallback(null)
                return VBCableInstallResult(false, VBCableInstallError.DeviceNotDetected, 
                    "Installation completed but device not detected after ${maxWait}s")
            }
        } catch (e: Exception) {
            Logger.e("VBCableManager", "Installation error: ${e.message}", e)
    val errorMsg = e.message ?: ""
            val errorType = when {
                errorMsg.contains("elevation", ignoreCase = true) ||
                errorMsg.contains("administrator", ignoreCase = true) ||
                errorMsg.contains("权限", ignoreCase = true) ||
                errorMsg.contains("提升", ignoreCase = true) ||
                errorMsg.contains("admin", ignoreCase = true) ||
                errorMsg.contains("denied", ignoreCase = true) -> VBCableInstallError.UacDenied
                
                errorMsg.contains("timeout", ignoreCase = true) -> VBCableInstallError.InstallationTimeout
                
                else -> VBCableInstallError.Unknown
            }
    val userMessage = when (errorType) {
                VBCableInstallError.UacDenied -> getString(Res.string.vbcableAdminRequired)
                VBCableInstallError.InstallationTimeout -> getString(Res.string.vbcableInstallTimeout)
                else -> getString(Res.string.installError, e.message ?: "Unknown error")
            }
            
            progressCallback(userMessage)
            delay(2000)
            progressCallback(null)
            
            return VBCableInstallResult(false, errorType, e.message ?: "Unknown error")
        }
    }

    private data class DownloadResult(
        val file: File?,
        val error: VBCableInstallError = VBCableInstallError.None,
        val errorMessage: String = ""
    )

    private fun downloadAndExtractInstaller(): DownloadResult {
        val downloadUrl = "https://download.vb-audio.com/Download_CABLE/VBCABLE_Driver_Pack45.zip"
        val zipFile = File.createTempFile("vbcable_pack", ".zip")
    val outputDir = File(System.getProperty("java.io.tmpdir"), "vbcable_extracted_${System.currentTimeMillis()}")
        
        Logger.i("VBCableManager", "Downloading VB-Cable driver from $downloadUrl...")
        
        try {
            val url = java.net.URI(downloadUrl).toURL()
    val connection = url.openConnection()
            connection.connectTimeout = 30000
            connection.readTimeout = 60000
            connection.connect()
            
            connection.getInputStream().use { input ->
                FileOutputStream(zipFile).use { output ->
                    input.copyTo(output)
                }
            }
            
            Logger.i("VBCableManager", "Download complete. Extracting...")
            
            if (!outputDir.exists()) outputDir.mkdirs()
            
            java.util.zip.ZipFile(zipFile).use { zip ->
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
    val entryFile = File(outputDir, entry.name)
                    
                    if (entry.isDirectory) {
                        entryFile.mkdirs()
                    } else {
                        entryFile.parentFile?.mkdirs()
                        zip.getInputStream(entry).use { input ->
                            FileOutputStream(entryFile).use { output ->
                                input.copyTo(output)
                            }
                        }
                    }
                }
            }
    val setupFile = File(outputDir, INSTALLER_NAME)
            if (setupFile.exists()) {
                Logger.i("VBCableManager", "Found installer at ${setupFile.absolutePath}")
                return DownloadResult(setupFile)
            }
    val found = outputDir.walkTopDown().find { it.name.equals(INSTALLER_NAME, ignoreCase = true) }
            if (found != null) {
                Logger.i("VBCableManager", "Found installer at ${found.absolutePath}")
                return DownloadResult(found)
            }
            
            return DownloadResult(null, VBCableInstallError.ExtractionFailed, 
                "Installer not found in downloaded package")
                
        } catch (e: java.net.SocketTimeoutException) {
            Logger.e("VBCableManager", "Download timeout: ${e.message}")
            return DownloadResult(null, VBCableInstallError.DownloadFailed, "Download timeout: ${e.message}")
        } catch (e: java.net.UnknownHostException) {
            Logger.e("VBCableManager", "Network error: ${e.message}")
            return DownloadResult(null, VBCableInstallError.DownloadFailed, "Network error: ${e.message}")
        } catch (e: java.io.IOException) {
            Logger.e("VBCableManager", "I/O error: ${e.message}")
            return DownloadResult(null, VBCableInstallError.DownloadFailed, "I/O error: ${e.message}")
        } catch (e: Exception) {
            Logger.e("VBCableManager", "Failed to download or extract VB-Cable driver: ${e.message}", e)
            return DownloadResult(null, VBCableInstallError.Unknown, e.message ?: "Unknown error")
        } finally {
            zipFile.delete()
        }
    }

    fun setDefaultMicrophone(): Boolean {
        if (!PlatformInfo.isWindows) return false
        return setCableOutputAsDefaultMic()
    }

    fun restoreDefaultMicrophone() {
        if (!PlatformInfo.isWindows) return
        
        originalSpeaker?.let { speaker ->
            setDefaultPlaybackDevice(speaker)
            Logger.i("VBCableManager", "Restored original speaker: $speaker")
        } ?: Logger.w("VBCableManager", "No original speaker saved to restore")
    }
}
