package com.lanrhyme.micyou

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * 预设管理器 - 负责加载、保存和应用音频处理预设
 */
class PresetManager(private val settings: Settings) {
    private val customPresetsKey = "custom_presets_json"

    private val _presets = MutableStateFlow<List<AudioPreset>>(loadAllPresets())
    val presets: StateFlow<List<AudioPreset>> = _presets.asStateFlow()

    private val _currentPresetId = MutableStateFlow(loadCurrentPresetId())
    val currentPresetId: StateFlow<String> = _currentPresetId.asStateFlow()

    init {
        loadCustomPresets()
    }

    /**
     * 获取当前激活的预设
     */
    fun getCurrentPreset(): AudioPreset {
        return _presets.value.find { it.id == _currentPresetId.value }
            ?: BuiltInPresets.DEFAULT
    }

    /**
     * 应用指定预设
     * @param presetId 预设ID
     * @param applySettings 应用设置的回调函数
     */
    fun applyPreset(presetId: String, applySettings: (AudioPresetSettings) -> Unit) {
        val preset = _presets.value.find { it.id == presetId } ?: return

        applySettings(preset.settings)

        _currentPresetId.value = presetId
        settings.putString("current_preset", presetId)
    }

    /**
     * 保存当前配置为自定义预设
     * @param name 预设名称
     * @param settingsFrom 当前设置状态
     * @return 新创建的预设
     */
    fun saveCustomPreset(name: String, settingsFrom: AudioPresetSettings): AudioPreset {
        val id = "custom_${System.currentTimeMillis()}"
        val preset = AudioPreset(
            id = id,
            name = name,
            isBuiltIn = false,
            settings = settingsFrom
        )

        val updatedPresets = _presets.value + preset
        _presets.value = updatedPresets
        saveCustomPresetsToSettings(updatedPresets.filter { !it.isBuiltIn })

        return preset
    }

    /**
     * 删除自定义预设
     * @param presetId 预设ID
     */
    fun deleteCustomPreset(presetId: String) {
        val preset = _presets.value.find { it.id == presetId }
        if (preset != null && !preset.isBuiltIn) {
            val updatedPresets = _presets.value.filter { it.id != presetId }
            _presets.value = updatedPresets
            saveCustomPresetsToSettings(updatedPresets.filter { !it.isBuiltIn })

            // 如果删除的是当前预设，切换到默认预设
            if (_currentPresetId.value == presetId) {
                _currentPresetId.value = "default"
                settings.putString("current_preset", "default")
            }
        }
    }

    /**
     * 重命名自定义预设
     * @param presetId 预设ID
     * @param newName 新名称
     */
    fun renameCustomPreset(presetId: String, newName: String) {
        val preset = _presets.value.find { it.id == presetId }
        if (preset != null && !preset.isBuiltIn) {
            val updatedPreset = preset.copy(name = newName)
            val updatedPresets = _presets.value.map { if (it.id == presetId) updatedPreset else it }
            _presets.value = updatedPresets
            saveCustomPresetsToSettings(updatedPresets.filter { !it.isBuiltIn })
        }
    }

    /**
     * 检查预设是否存在
     */
    fun hasPreset(presetId: String): Boolean {
        return _presets.value.any { it.id == presetId }
    }

    /**
     * 获取所有自定义预设
     */
    fun getCustomPresets(): List<AudioPreset> {
        return _presets.value.filter { !it.isBuiltIn }
    }

    // ==================== 私有方法 ====================

    private fun loadAllPresets(): List<AudioPreset> {
        return BuiltInPresets.ALL
    }

    private fun loadCurrentPresetId(): String {
        return settings.getString("current_preset", "default")
    }

    private fun loadCustomPresets() {
        // 尝试从设置中加载自定义预设
        val customPresetsJson = settings.getString(customPresetsKey, "")
        if (customPresetsJson.isNotEmpty()) {
            try {
                // 解析 JSON 并添加自定义预设
                // 简化实现：使用基本的字符串解析
                val customPresets = parseCustomPresetsJson(customPresetsJson)
                _presets.update { it + customPresets }
            } catch (e: Exception) {
                Logger.e("PresetManager", "加载自定义预设失败: ${e.message}")
            }
        }
    }

    private fun saveCustomPresetsToSettings(customPresets: List<AudioPreset>) {
        if (customPresets.isEmpty()) {
            settings.putString(customPresetsKey, "")
        } else {
            val json = serializeCustomPresets(customPresets)
            settings.putString(customPresetsKey, json)
        }
    }

    /**
     * 简化的自定义预设 JSON 解析
     */
    private fun parseCustomPresetsJson(json: String): List<AudioPreset> {
        // 简化实现：按预设分隔符解析
        if (json.isEmpty()) return emptyList()

        val presets = mutableListOf<AudioPreset>()
        val presetStrings = json.split("||")

        for (presetStr in presetStrings) {
            if (presetStr.isEmpty()) continue

            val parts = presetStr.split("|")
            if (parts.size >= 13) {
                try {
                    val preset = AudioPreset(
                        id = parts[0],
                        name = parts[1],
                        isBuiltIn = false,
                        settings = AudioPresetSettings(
                            enableNS = parts[2] == "true",
                            nsType = NoiseReductionType.valueOf(parts[3]),
                            enableAGC = parts[4] == "true",
                            agcTargetLevel = parts[5].toInt(),
                            enableVAD = parts[6] == "true",
                            vadThreshold = parts[7].toInt(),
                            enableDereverb = parts[8] == "true",
                            dereverbLevel = parts[9].toFloat(),
                            amplification = parts[10].toFloat(),
                            sampleRate = if (parts[11] != "null") SampleRate.valueOf(parts[11]) else null,
                            channelCount = if (parts[12] != "null") ChannelCount.valueOf(parts[12]) else null,
                            audioFormat = if (parts.size > 13 && parts[13] != "null") AudioFormat.valueOf(parts[13]) else null
                        )
                    )
                    presets.add(preset)
                } catch (e: Exception) {
                    Logger.e("PresetManager", "解析预设失败: $presetStr")
                }
            }
        }

        return presets
    }

    /**
     * 简化的自定义预设 JSON 序列化
     */
    private fun serializeCustomPresets(presets: List<AudioPreset>): String {
        return presets.joinToString("||") { preset ->
            val settings = preset.settings
            "${preset.id}|${preset.name}|${settings.enableNS}|${settings.nsType.name}|${settings.enableAGC}|${settings.agcTargetLevel}|${settings.enableVAD}|${settings.vadThreshold}|${settings.enableDereverb}|${settings.dereverbLevel}|${settings.amplification}|${settings.sampleRate?.name ?: "null"}|${settings.channelCount?.name ?: "null"}|${settings.audioFormat?.name ?: "null"}"
        }
    }
}