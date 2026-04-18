package com.lanrhyme.micyou

/**
 * 音频处理预设配置
 * 包含一组音频处理参数的组合
 */
data class AudioPreset(
    /** 预设唯一标识 */
    val id: String,
    /** 预设显示名称 */
    val name: String,
    /** 是否为内置预设（内置预设不可删除） */
    val isBuiltIn: Boolean = true,
    /** 预设的音频处理参数 */
    val settings: AudioPresetSettings
)

/**
 * 预设中的音频处理参数设置
 */
data class AudioPresetSettings(
    /** 启用噪声抑制 */
    val enableNS: Boolean = false,
    /** 噪声抑制算法类型 */
    val nsType: NoiseReductionType = NoiseReductionType.Ulunas,
    /** 启用自动增益控制 */
    val enableAGC: Boolean = false,
    /** AGC 目标电平 */
    val agcTargetLevel: Int = 32000,
    /** 启用语音活动检测 */
    val enableVAD: Boolean = false,
    /** VAD 阈值 */
    val vadThreshold: Int = 10,
    /** 启用去混响 */
    val enableDereverb: Boolean = false,
    /** 去混响强度 */
    val dereverbLevel: Float = 0.5f,
    /** 增益放大（dB） */
    val amplification: Float = 15.0f,
    /** 采样率（可选，null 表示不覆盖） */
    val sampleRate: SampleRate? = null,
    /** 通道数（可选，null 表示不覆盖） */
    val channelCount: ChannelCount? = null,
    /** 音频格式（可选，null 表示不覆盖） */
    val audioFormat: AudioFormat? = null
)

/**
 * 内置音频预设定义
 */
object BuiltInPresets {
    /** 默认预设 - 不启用任何处理 */
    val DEFAULT = AudioPreset(
        id = "default",
        name = "Default",
        isBuiltIn = true,
        settings = AudioPresetSettings(
            enableNS = false,
            enableAGC = false,
            enableVAD = false,
            enableDereverb = false,
            amplification = 0.0f
        )
    )

    /** 会议模式 - 强降噪、AGC、VAD，适合会议通话 */
    val MEETING = AudioPreset(
        id = "meeting",
        name = "Meeting",
        isBuiltIn = true,
        settings = AudioPresetSettings(
            enableNS = true,
            nsType = NoiseReductionType.RNNoise,
            enableAGC = true,
            agcTargetLevel = 28000,
            enableVAD = true,
            vadThreshold = 15,
            enableDereverb = true,
            dereverbLevel = 0.4f,
            amplification = 5.0f,
            sampleRate = SampleRate.Rate16000,
            channelCount = ChannelCount.Mono
        )
    )

    /** 录音模式 - 高质量、低处理，适合后期编辑 */
    val RECORDING = AudioPreset(
        id = "recording",
        name = "Recording",
        isBuiltIn = true,
        settings = AudioPresetSettings(
            enableNS = true,
            nsType = NoiseReductionType.Ulunas,
            enableAGC = false,
            enableVAD = false,
            enableDereverb = false,
            dereverbLevel = 0.3f,
            amplification = 0.0f,
            sampleRate = SampleRate.Rate48000,
            channelCount = ChannelCount.Stereo,
            audioFormat = AudioFormat.PCM_FLOAT
        )
    )

    /** 直播模式 - 平衡延迟和质量，适合实时直播 */
    val LIVE_STREAM = AudioPreset(
        id = "livestream",
        name = "Live Stream",
        isBuiltIn = true,
        settings = AudioPresetSettings(
            enableNS = true,
            nsType = NoiseReductionType.Speexdsp,
            enableAGC = true,
            agcTargetLevel = 30000,
            enableVAD = true,
            vadThreshold = 20,
            enableDereverb = false,
            amplification = 10.0f,
            sampleRate = SampleRate.Rate48000,
            channelCount = ChannelCount.Mono
        )
    )

    /** 所有内置预设列表 */
    val ALL: List<AudioPreset> = listOf(DEFAULT, MEETING, RECORDING, LIVE_STREAM)

    /**
     * 根据 ID 查找内置预设
     */
    fun findById(id: String): AudioPreset? = ALL.find { it.id == id }
}