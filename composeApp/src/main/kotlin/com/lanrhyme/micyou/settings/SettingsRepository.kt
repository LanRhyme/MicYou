package com.lanrhyme.micyou.settings

import com.lanrhyme.micyou.audio.AudioFormat
import com.lanrhyme.micyou.audio.AudioEffectType
import com.lanrhyme.micyou.audio.ChannelCount
import com.lanrhyme.micyou.audio.EqualizerConfig
import com.lanrhyme.micyou.audio.SampleRate
import com.lanrhyme.micyou.theme.PaletteStyle
import com.lanrhyme.micyou.theme.ThemeMode
import com.lanrhyme.micyou.ui.background.BackgroundSettings
import com.lanrhyme.micyou.util.AppLanguage
import com.lanrhyme.micyou.util.Constants
import com.lanrhyme.micyou.viewmodel.ConnectionMode
import com.lanrhyme.micyou.viewmodel.NoiseReductionType
import com.lanrhyme.micyou.viewmodel.TransportProtocol
import com.lanrhyme.micyou.viewmodel.VisualizerStyle

/**
 * Centralized settings repository that wraps [AppSettings] with typed accessors
 * and default values. Eliminates duplication between AudioStreamViewModel and
 * SettingsViewModel.
 */
object SettingsRepository {

    private val settings get() = AppSettings

    // ---- Connection settings ----
    fun getConnectionMode(): ConnectionMode {
        val name = settings.getString("connection_mode", ConnectionMode.Wifi.name)

        return when (name) {
            "WifiUdp" -> ConnectionMode.Wifi
            else -> try {
                ConnectionMode.valueOf(name)
            } catch (_: Exception) {
                ConnectionMode.Wifi
            }
        }
    }

    fun putConnectionMode(mode: ConnectionMode) = settings.putString("connection_mode", mode.name)

    fun getTransportProtocol(): TransportProtocol {
        val name = settings.getString("transport_protocol", TransportProtocol.Both.name)
        return try {
            TransportProtocol.valueOf(name)
        } catch (_: Exception) {
            TransportProtocol.Both
        }
    }

    fun putTransportProtocol(protocol: TransportProtocol) = settings.putString("transport_protocol", protocol.name)

    fun getIpAddress(): String = settings.getString("ip_address", "192.168.1.5")
    fun putIpAddress(ip: String) = settings.putString("ip_address", ip)

    fun getPort(): String = settings.getString("port", Constants.DEFAULT_TCP_PORT.toString())
    fun putPort(port: String) = settings.putString("port", port)

    // ---- Audio format settings ----
    fun getSampleRate(): SampleRate {
        val name = settings.getString("sample_rate", SampleRate.Rate48000.name)
        return try {
            SampleRate.valueOf(name)
        } catch (_: Exception) {
            SampleRate.Rate48000
        }
    }

    fun putSampleRate(rate: SampleRate) = settings.putString("sample_rate", rate.name)

    fun getChannelCount(): ChannelCount {
        val name = settings.getString("channel_count", ChannelCount.Stereo.name)
        return try {
            ChannelCount.valueOf(name)
        } catch (_: Exception) {
            ChannelCount.Stereo
        }
    }

    fun putChannelCount(count: ChannelCount) = settings.putString("channel_count", count.name)

    fun getAudioFormat(): AudioFormat {
        val name = settings.getString("audio_format", AudioFormat.PCM_FLOAT.name)
        return try {
            AudioFormat.valueOf(name)
        } catch (_: Exception) {
            AudioFormat.PCM_FLOAT
        }
    }

    fun putAudioFormat(format: AudioFormat) = settings.putString("audio_format", format.name)

    // ---- Audio processing settings ----
    fun getEnableNS(): Boolean = settings.getBoolean("enable_ns", false)
    fun putEnableNS(enabled: Boolean) = settings.putBoolean("enable_ns", enabled)

    fun getNsType(): NoiseReductionType {
        val name = settings.getString("ns_type", NoiseReductionType.Ulunas.name)
        return try {
            NoiseReductionType.valueOf(name)
        } catch (_: Exception) {
            NoiseReductionType.Ulunas
        }
    }

    fun putNsType(type: NoiseReductionType) = settings.putString("ns_type", type.name)

    fun getEnableAGC(): Boolean = settings.getBoolean("enable_agc", false)
    fun putEnableAGC(enabled: Boolean) = settings.putBoolean("enable_agc", enabled)

    fun getAgcTarget(): Int = settings.getInt("agc_target", 32000)
    fun putAgcTarget(level: Int) = settings.putInt("agc_target", level)

    fun getAgcAttackRate(): Float = settings.getFloat("agc_attack_rate", 0.01f)
    fun putAgcAttackRate(rate: Float) = settings.putFloat("agc_attack_rate", rate)

    fun getAgcDecayRate(): Float = settings.getFloat("agc_decay_rate", 0.005f)
    fun putAgcDecayRate(rate: Float) = settings.putFloat("agc_decay_rate", rate)

    fun getEnableVAD(): Boolean = settings.getBoolean("enable_vad", false)
    fun putEnableVAD(enabled: Boolean) = settings.putBoolean("enable_vad", enabled)

    fun getVadThreshold(): Int = settings.getInt("vad_threshold", 10)
    fun putVadThreshold(threshold: Int) = settings.putInt("vad_threshold", threshold)

    fun getEnableDereverb(): Boolean = settings.getBoolean("enable_dereverb", false)
    fun putEnableDereverb(enabled: Boolean) = settings.putBoolean("enable_dereverb", enabled)

    fun getDereverbLevel(): Float = settings.getFloat("dereverb_level", 0.5f)
    fun putDereverbLevel(level: Float) = settings.putFloat("dereverb_level", level)

    fun getAmplification(): Float = settings.getFloat("amplification", 15.0f)
    fun putAmplification(amp: Float) = settings.putFloat("amplification", amp)

    fun getNsIntensity(): Float = settings.getFloat("ns_intensity", 1.0f)
    fun putNsIntensity(intensity: Float) = settings.putFloat("ns_intensity", intensity)

    fun getProcessingChain(): List<AudioEffectType> {
        val saved = settings.getString("processing_chain", "")
        if (saved.isEmpty()) return defaultProcessingChain()
        return saved.split(",").mapNotNull { name -> AudioEffectType.entries.find { it.name == name } }
            .toMutableList().apply {
                if (!contains(AudioEffectType.Equalizer)) {
                    val ampIndex = indexOf(AudioEffectType.Amplifier)
                    if (ampIndex != -1) add(ampIndex, AudioEffectType.Equalizer)
                    else add((size - 2).coerceAtLeast(0), AudioEffectType.Equalizer)
                }
            }
    }

    fun putProcessingChain(chain: List<AudioEffectType>) =
        settings.putString("processing_chain", chain.joinToString(",") { it.name })

    fun defaultProcessingChain() = listOf(
        AudioEffectType.NoiseReduction,
        AudioEffectType.Dereverb,
        AudioEffectType.Equalizer,
        AudioEffectType.Amplifier,
        AudioEffectType.AGC,
        AudioEffectType.VAD
    )

    fun getEqualizerConfig(): EqualizerConfig {
        val enabled = settings.getBoolean("equalizer_enabled", false)
        val preAmp = settings.getFloat("equalizer_preamp", 0f)
        val gainsStr = settings.getString("equalizer_gains", "")
        val gains = if (gainsStr.isEmpty()) List(11) { 0f }
        else gainsStr.split(",").mapNotNull { it.toFloatOrNull() }.takeIf { it.size == 11 } ?: List(11) { 0f }
        return EqualizerConfig(enabled, gains, preAmp)
    }

    fun putEqualizerConfig(config: EqualizerConfig) {
        settings.putBoolean("equalizer_enabled", config.enabled)
        settings.putFloat("equalizer_preamp", config.preAmp)
        settings.putString("equalizer_gains", config.gains.joinToString(","))
    }

    fun getAndroidAudioSource(): String = settings.getString("android_audio_source", "Mic")
    fun putAndroidAudioSource(source: String) = settings.putString("android_audio_source", source)

    fun getIsAutoConfig(): Boolean = settings.getBoolean("is_auto_config", true)
    fun putIsAutoConfig(enabled: Boolean) = settings.putBoolean("is_auto_config", enabled)

    fun getPerformanceMode(): String = settings.getString("performance_mode", "Default")
    fun putPerformanceMode(mode: String) = settings.putString("performance_mode", mode)

    fun getBufferSizeMultiplier(): Float = settings.getFloat("buffer_size_multiplier", 1.0f)
    fun putBufferSizeMultiplier(multiplier: Float) = settings.putFloat("buffer_size_multiplier", multiplier)

    // ---- Theme / Appearance settings ----
    fun getThemeMode(): ThemeMode {
        val name = settings.getString("theme_mode", ThemeMode.System.name)
        return try {
            ThemeMode.valueOf(name)
        } catch (_: Exception) {
            ThemeMode.System
        }
    }

    fun putThemeMode(mode: ThemeMode) = settings.putString("theme_mode", mode.name)

    fun getSeedColor(): Long = settings.getLong("seed_color", 0xFF4A672D)
    fun putSeedColor(color: Long) = settings.putLong("seed_color", color)

    fun getUseDynamicColor(): Boolean = settings.getBoolean("use_dynamic_color", false)
    fun putUseDynamicColor(enable: Boolean) = settings.putBoolean("use_dynamic_color", enable)

    fun getOledPureBlack(): Boolean = settings.getBoolean("oled_pure_black", false)
    fun putOledPureBlack(enabled: Boolean) = settings.putBoolean("oled_pure_black", enabled)

    fun getPaletteStyle(): PaletteStyle {
        val name = settings.getString("palette_style", PaletteStyle.TonalSpot.name)
        return try {
            PaletteStyle.valueOf(name)
        } catch (_: Exception) {
            PaletteStyle.TonalSpot
        }
    }

    fun putPaletteStyle(style: PaletteStyle) = settings.putString("palette_style", style.name)

    fun getUseExpressiveShapes(): Boolean = settings.getBoolean("use_expressive_shapes", true)
    fun putUseExpressiveShapes(enabled: Boolean) = settings.putBoolean("use_expressive_shapes", enabled)

    // ---- Language ----
    fun getLanguage(): AppLanguage {
        val name = settings.getString("language", AppLanguage.System.name)
        return try {
            AppLanguage.valueOf(name)
        } catch (_: Exception) {
            AppLanguage.System
        }
    }

    fun putLanguage(language: AppLanguage) = settings.putString("language", language.name)

    // ---- Feature toggles ----
    fun getAutoStart(): Boolean = settings.getBoolean("auto_start", false)
    fun putAutoStart(enabled: Boolean) = settings.putBoolean("auto_start", enabled)

    fun getEnableStreamingNotification(): Boolean = settings.getBoolean("enable_streaming_notification", true)
    fun putEnableStreamingNotification(enabled: Boolean) =
        settings.putBoolean("enable_streaming_notification", enabled)

    fun getKeepScreenOn(): Boolean = settings.getBoolean("keep_screen_on", false)
    fun putKeepScreenOn(enabled: Boolean) = settings.putBoolean("keep_screen_on", enabled)

    fun getAutoCheckUpdate(): Boolean = settings.getBoolean("auto_check_update", true)
    fun putAutoCheckUpdate(enabled: Boolean) = settings.putBoolean("auto_check_update", enabled)

    fun getUseMirrorDownload(): Boolean = settings.getBoolean("use_mirror_download", false)
    fun putUseMirrorDownload(enabled: Boolean) = settings.putBoolean("use_mirror_download", enabled)

    fun getMirrorCdk(): String = settings.getString("mirror_cdk", "")
    fun putMirrorCdk(cdk: String) = settings.putString("mirror_cdk", cdk)

    // ---- First launch ----
    fun hasLaunchedBefore(): Boolean = settings.getBoolean("has_launched_before", false)
    fun markLaunched() = settings.putBoolean("has_launched_before", true)

    // ---- Visualizer ----
    fun getVisualizerStyle(): VisualizerStyle {
        val name = settings.getString("visualizer_style", VisualizerStyle.VolumeRing.name)
        return try {
            VisualizerStyle.valueOf(name)
        } catch (_: Exception) {
            VisualizerStyle.VolumeRing
        }
    }

    fun putVisualizerStyle(style: VisualizerStyle) = settings.putString("visualizer_style", style.name)

    // ---- Background ----
    fun getBackgroundSettings(): BackgroundSettings {
        return BackgroundSettings(
            imagePath = settings.getString("background_image_path", ""),
            brightness = settings.getFloat("background_brightness", 0.5f),
            blurRadius = settings.getFloat("background_blur", 0f),
            cardOpacity = settings.getFloat("card_opacity", 1f),
            enableHazeEffect = settings.getBoolean("enable_haze_effect", false)
        )
    }

    fun putBackgroundImage(path: String) = settings.putString("background_image_path", path)
    fun putBackgroundBrightness(brightness: Float) = settings.putFloat("background_brightness", brightness)
    fun putBackgroundBlur(blur: Float) = settings.putFloat("background_blur", blur)
    fun putCardOpacity(opacity: Float) = settings.putFloat("card_opacity", opacity)
    fun putEnableHazeEffect(enabled: Boolean) = settings.putBoolean("enable_haze_effect", enabled)

    // ---- Monitoring ----
    fun getMonitoringEnabled(): Boolean = settings.getBoolean("monitoring_enabled", false)
    fun putMonitoringEnabled(enabled: Boolean) = settings.putBoolean("monitoring_enabled", enabled)

    fun getBindAddress(): String = settings.getString("bind_address", "0.0.0.0")
    fun getIsAutoBindAddress(): Boolean = settings.getBoolean("is_auto_bind_address", false)
}
