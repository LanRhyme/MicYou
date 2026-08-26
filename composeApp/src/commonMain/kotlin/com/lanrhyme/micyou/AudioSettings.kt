package com.lanrhyme.micyou

enum class SampleRate(val value: Int) {
    Rate16000(16000),
    Rate44100(44100),
    Rate48000(48000)
}

enum class ChannelCount(val value: Int, val label: String) {
    Mono(1, "Mono"),
    Stereo(2, "Stereo")
}

enum class AudioFormat(val value: Int, val label: String) {
    PCM_8BIT(3, "8-bit PCM"), // AudioFormat.ENCODING_PCM_8BIT = 3
    PCM_16BIT(2, "16-bit PCM"), // AudioFormat.ENCODING_PCM_16BIT = 2
    PCM_FLOAT(4, "32-bit Float") // AudioFormat.ENCODING_PCM_FLOAT = 4
}

/**
 * 返回当前平台可用的音频格式列表（用于设置 UI）。
 * Android: PCM_FLOAT 在 API < 23 时不显示
 * Desktop: 所有格式可用
 */
expect fun availableAudioFormats(): List<AudioFormat>

/**
 * 返回当前平台的安全默认格式。
 * Android: API < 23 → PCM_16BIT，API >= 23 → PCM_FLOAT
 * Desktop: PCM_FLOAT
 */
expect fun defaultAudioFormat(): AudioFormat

