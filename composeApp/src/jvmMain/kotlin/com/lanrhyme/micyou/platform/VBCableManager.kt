package com.lanrhyme.micyou.platform

import com.lanrhyme.micyou.AppLanguage
import com.lanrhyme.micyou.AppStrings
import com.lanrhyme.micyou.Logger
import com.lanrhyme.micyou.SettingsFactory
import com.lanrhyme.micyou.getStrings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit
import javax.sound.sampled.AudioSystem

object VBCableManager {
    private const val CABLE_OUTPUT_NAME = "CABLE Output"
    private const val CABLE_INPUT_NAME = "CABLE Input"
    private const val INSTALLER_NAME = "VBCABLE_Setup_x64.exe"
    private const val SOUND_VOLUME_VIEW_NAME = "SoundVolumeView.exe"
    private const val KEY_CONFIGURED = "vbcable_configured"
    private const val KEY_ORIGINAL_SPEAKER = "original_speaker"

    private val settings = SettingsFactory.getSettings()
    
    private var initialized = false
    private var originalSpeaker: String? = null
    
    init {
        initialized = settings.getBoolean(KEY_CONFIGURED, false)
        val savedSpeaker = settings.getString(KEY_ORIGINAL_SPEAKER, "")
        originalSpeaker = if (savedSpeaker.isNotBlank()) savedSpeaker else null
    }
    
    private val savedLanguageName: String
        get() = settings.getString("language", AppLanguage.System.name)
    
    private val language: AppLanguage
        get() = try { AppLanguage.valueOf(savedLanguageName) } catch(e: Exception) { AppLanguage.System }
    
    private val strings: AppStrings
        get() = getStrings(language)

    fun isInstalled(): Boolean {
        if (!PlatformInfo.isWindows) return false
        
        return try {
            val mixers = AudioSystem.getMixerInfo()
            mixers.any { 
                it.name.contains(CABLE_OUTPUT_NAME, ignoreCase = true) || 
                it.name.contains(CABLE_INPUT_NAME, ignoreCase = true) 
            }
        } catch (e: Exception) {
            Logger.e("VBCableManager", "Failed to check VB-Cable installation", e)
            false
        }
    }

    fun isInitialized(): Boolean = initialized

    fun markInitialized() {
        initialized = true
        settings.putBoolean(KEY_CONFIGURED, true)
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
        
        val resourcePath = "/$INSTALLER_NAME"
        val resourceUrl = VBCableManager::class.java.getResource(resourcePath)
        if (resourceUrl != null) {
            val tempFile = File(System.getProperty("java.io.tmpdir"), INSTALLER_NAME)
            if (!tempFile.exists()) {
                tempFile.outputStream().use { output ->
                    VBCableManager::class.java.getResourceAsStream(resourcePath)?.use { input ->
                        input.copyTo(output)
                    }
                }
            }
            if (tempFile.exists()) {
                Logger.i("VBCableManager", "Found VB-Cable installer in resources: ${tempFile.absolutePath}")
                return tempFile
            }
        }
        
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

    private fun configureVBCableDevices() {
        disableCableInput16ch()
        
        setDeviceFormat("VB-Audio Virtual Cable\\Device\\CABLE Input\\Render", 16, 48000, 2)
        setDeviceFormat("VB-Audio Virtual Cable\\Device\\CABLE Output\\Capture", 16, 48000, 1)
    }

    suspend fun install(progressCallback: (String?) -> Unit) = withContext(Dispatchers.IO) {
        if (initialized) {
            Logger.i("VBCableManager", "VB-Cable already configured, skipping")
            return@withContext
        }
        
        if (isInstalled()) {
            Logger.i("VBCableManager", "VB-Cable already installed, configuring...")
            progressCallback(strings.installConfiguring)
            configureVBCableDevices()
            setCableOutputAsDefaultMic()
            initialized = true
            settings.putBoolean(KEY_CONFIGURED, true)
            progressCallback(strings.installConfigComplete)
            delay(1000)
            progressCallback(null)
            return@withContext
        }
        
        val currentSpeaker = getDefaultPlaybackDevice()
        if (currentSpeaker != null) {
            originalSpeaker = currentSpeaker
            settings.putString(KEY_ORIGINAL_SPEAKER, currentSpeaker)
            Logger.i("VBCableManager", "Saved current speaker: $currentSpeaker")
        }
        
        progressCallback(strings.installCheckingPackage)
        
        var installerFile = getVBCableSetupPath()
        
        if (installerFile == null || !installerFile.exists()) {
            Logger.i("VBCableManager", "Installer not found locally. Attempting to download...")
            progressCallback(strings.installDownloading)
            installerFile = downloadAndExtractInstaller()
        }

        if (installerFile == null || !installerFile.exists()) {
            Logger.e("VBCableManager", "VB-Cable installer not found. Please place '$INSTALLER_NAME' in resources or ensure internet access.")
            progressCallback(strings.installDownloadFailed)
            delay(2000)
            progressCallback(null)
            return@withContext
        }

        Logger.i("VBCableManager", "Installing VB-Cable...")
        progressCallback(strings.installInstalling)
        
        try {
            val licenseKey = getVBCableLicenseKey()
            val args = mutableListOf(installerFile.absolutePath, "-i", "-h")
            
            if (licenseKey != null) {
                args.addAll(listOf("-s", "-k", licenseKey))
                Logger.i("VBCableManager", "Using license key for installation")
            }
            
            val processBuilder = ProcessBuilder(args)
            processBuilder.redirectErrorStream(true)
            val process = processBuilder.start()
            
            process.waitFor(60, TimeUnit.SECONDS)
            
            Logger.i("VBCableManager", "Waiting for device initialization...")
            progressCallback("Waiting for device initialization...")
            
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
                val retryProcess = ProcessBuilder(
                    installerFile.absolutePath, "-i", "-h"
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
                progressCallback(strings.installConfiguring)
                configureVBCableDevices()
                setCableOutputAsDefaultMic()
                
                originalSpeaker?.let { speaker ->
                    setDefaultPlaybackDevice(speaker)
                }
                
                initialized = true
                settings.putBoolean(KEY_CONFIGURED, true)
                progressCallback(strings.installConfigComplete)
            } else {
                progressCallback(strings.installNotCompleted)
            }
        } catch (e: Exception) {
            Logger.e("VBCableManager", "Installation error: ${e.message}", e)
            progressCallback(strings.installError.replace("%s", e.message ?: "Unknown error"))
        } finally {
            delay(2000)
            progressCallback(null)
        }
    }

    private fun downloadAndExtractInstaller(): File? {
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
                return setupFile
            }
            
            val found = outputDir.walkTopDown().find { it.name.equals(INSTALLER_NAME, ignoreCase = true) }
            if (found != null) {
                Logger.i("VBCableManager", "Found installer at ${found.absolutePath}")
                return found
            }
            
        } catch (e: Exception) {
            Logger.e("VBCableManager", "Failed to download or extract VB-Cable driver: ${e.message}", e)
        } finally {
            zipFile.delete()
        }
        
        return null
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

    suspend fun uninstall(progressCallback: (String?) -> Unit) = withContext(Dispatchers.IO) {
        if (!PlatformInfo.isWindows) {
            Logger.w("VBCableManager", "Uninstall not supported on this platform")
            return@withContext
        }

        if (!isInstalled()) {
            Logger.i("VBCableManager", "VB-Cable not installed, nothing to uninstall")
            progressCallback("VB-Cable not installed")
            delay(1500)
            progressCallback(null)
            return@withContext
        }

        progressCallback("Uninstalling VB-Cable driver...")

        val installerFile = getVBCableSetupPath()
        if (installerFile == null || !installerFile.exists()) {
            Logger.e("VBCableManager", "Installer not found for uninstallation")
            progressCallback("Installer not found, please uninstall from Control Panel")
            delay(3000)
            progressCallback(null)
            return@withContext
        }

        try {
            Logger.i("VBCableManager", "Uninstalling VB-Cable driver...")
            
            val processBuilder = ProcessBuilder(
                installerFile.absolutePath, "-u", "-h"
            )
            processBuilder.redirectErrorStream(true)
            val process = processBuilder.start()
            
            process.waitFor(60, TimeUnit.SECONDS)
            
            var uninstalled = false
            var waited = 0
            val maxWait = 30
            
            while (waited < maxWait) {
                delay(2000)
                waited += 2
                
                if (!isInstalled()) {
                    uninstalled = true
                    Logger.i("VBCableManager", "VB-Cable uninstall verified")
                    break
                } else {
                    Logger.i("VBCableManager", "Waiting for uninstall... (${waited}s)")
                }
            }
            
            if (uninstalled) {
                initialized = false
                settings.putBoolean(KEY_CONFIGURED, false)
                progressCallback("Uninstall completed")
            } else {
                progressCallback("Uninstall may require manual removal from Control Panel")
            }
        } catch (e: Exception) {
            Logger.e("VBCableManager", "Uninstall error: ${e.message}", e)
            progressCallback("Uninstall error: ${e.message}")
        } finally {
            delay(3000)
            progressCallback(null)
        }
    }
}
