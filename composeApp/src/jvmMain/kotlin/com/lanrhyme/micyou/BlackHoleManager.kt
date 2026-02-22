package com.lanrhyme.micyou

import com.lanrhyme.micyou.platform.PlatformInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.sound.sampled.AudioSystem

object BlackHoleManager {

    private val BLACKHOLE_PATTERN = Regex("BlackHole\\s*\\d*ch", RegexOption.IGNORE_CASE)
    private const val SWITCH_AUDIO_COMMAND = "SwitchAudioSource"

    private var originalInputDevice: AudioDevice? = null

    fun isInstalled(): Boolean {
        if (!PlatformInfo.isMacOS) return false

        return try {
            AudioSystem.getMixerInfo().any { BLACKHOLE_PATTERN.matches(it.name) }
        } catch (e: Exception) {
            Logger.e("BlackHoleManager", "Failed when finding BlackHole virtual device", e)
            false
        }
    }

    fun isSwitchAudioSourceInstalled(): Boolean {
        if (!PlatformInfo.isMacOS) return false

        return try {
            ProcessBuilder("which", SWITCH_AUDIO_COMMAND)
                .redirectErrorStream(true).start().waitFor() == 0
        } catch (e: Exception) {
            Logger.e("BlackHoleManager", "Failed when finding SwitchAudioSource", e)
            false
        }
    }

    suspend fun getInputDevicesJson(): String? = withContext(Dispatchers.IO) {
        if (!PlatformInfo.isMacOS) return@withContext null

        try {
            val process = ProcessBuilder(SWITCH_AUDIO_COMMAND, "-a", "-t", "input", "-f", "json")
                .redirectErrorStream(true).start()

            val output = process.inputStream.bufferedReader().readText()
            if (process.waitFor() == 0 && output.isNotBlank()) output else null
        } catch (e: Exception) {
            Logger.e("BlackHoleManager", "Failed when fetching device list in json", e)
            null
        }
    }

    fun findBlackHoleInJson(json: String): AudioDevice? {
        return try {
            parseDevicesJson(json).find { BLACKHOLE_PATTERN.matches(it.name) }
        } catch (e: Exception) {
            Logger.e("BlackHoleManager", "Failed when parsing json to find BlackHole", e)
            null
        }
    }

    private fun parseDevicesJson(json: String): List<AudioDevice> {
        val devices = mutableListOf<AudioDevice>()
        var content = json.trim().removeSurrounding("[", "]")

        Regex("\\{[^}]+\\}").findAll(content).forEach { matchResult ->
            val objStr = matchResult.value
            val id = extractField(objStr, "id") ?: return@forEach
            val name = extractField(objStr, "name") ?: return@forEach
            val uid = extractField(objStr, "uid")
            devices.add(AudioDevice(id, name, uid ?: ""))
        }
        return devices
    }

    private fun extractField(json: String, field: String): String? {
        return Regex("\"$field\"\\s*:\\s*\"([^\"]+)\"").find(json)?.groupValues?.get(1)
    }

    suspend fun setDefaultInputDevice(deviceId: String): Boolean = withContext(Dispatchers.IO) {
        if (!PlatformInfo.isMacOS) return@withContext false

        try {
            ProcessBuilder(SWITCH_AUDIO_COMMAND, "-t", "input", "-i", deviceId)
                .redirectErrorStream(true).start().waitFor() == 0
        } catch (e: Exception) {
            Logger.e("BlackHoleManager", "Failed when setting default device", e)
            false
        }
    }

    suspend fun getCurrentInputDevice(): AudioDevice? = withContext(Dispatchers.IO) {
        if (!PlatformInfo.isMacOS) return@withContext null

        try {
            val process = ProcessBuilder(SWITCH_AUDIO_COMMAND, "-c", "-t", "input", "-f", "json")
                .redirectErrorStream(true).start()

            val output = process.inputStream.bufferedReader().readText()
            if (process.waitFor() == 0 && output.isNotBlank()) {
                val id = extractField(output, "id")
                val name = extractField(output, "name")
                val uid = extractField(output, "uid")
                if (id != null && name != null) AudioDevice(id, name, uid ?: "") else null
            } else null
        } catch (e: Exception) {
            Logger.e("BlackHoleManager", "Failed when fetching device list", e)
            null
        }
    }

    suspend fun saveCurrentInputDevice() {
        if (!PlatformInfo.isMacOS) return

        getCurrentInputDevice()?.let {
            originalInputDevice = it
            Logger.i("BlackHoleManager", "Saved the original input device: ${it.name}")
        }
    }

    suspend fun restoreOriginalInputDevice(): Boolean {
        val device = originalInputDevice ?: return false

        if (getCurrentInputDevice()?.id == device.id) return true

        return setDefaultInputDevice(device.id).also {
            if (it) Logger.i("BlackHoleManager", "Restore to original device: ${device.name}")
        }
    }

    data class AudioDevice(val id: String, val name: String, val uid: String)
}
