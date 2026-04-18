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

/**
 * 音频格式枚举
 * @param value Android AudioFormat 编码值（PCM_24BIT 使用自定义值 6）
 * @param label 显示标签
 * @param bitsPerSample 每样本位数，用于计算比特率
 */
enum class AudioFormat(val value: Int, val label: String, val bitsPerSample: Int) {
    PCM_8BIT(3, "8-bit PCM", 8), // AudioFormat.ENCODING_PCM_8BIT = 3
    PCM_16BIT(2, "16-bit PCM", 16), // AudioFormat.ENCODING_PCM_16BIT = 2
    PCM_24BIT(6, "24-bit PCM", 24), // 自定义值，Android无原生24-bit常量
    PCM_FLOAT(4, "32-bit Float", 32) // AudioFormat.ENCODING_PCM_FLOAT = 4
}

