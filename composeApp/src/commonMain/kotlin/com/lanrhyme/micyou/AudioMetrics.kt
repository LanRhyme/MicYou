package com.lanrhyme.micyou

import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * 音频指标数据
 * 用于监控比特率和延迟
 */
data class AudioMetrics(
    /** 比特率 (bps) = sampleRate * channels * bitsPerSample */
    val bitrate: Int,
    /** 音频延迟估算（毫秒） */
    val latencyMs: Long,
    /** 测量时间戳 */
    val timestamp: Long = System.currentTimeMillis()
) {
    /** 获取 kbps 格式的比特率 */
    fun bitrateKbps(): Int = bitrate / 1000

    /** 获取 Mbps 格式的比特率 */
    fun bitrateMbps(): Float = bitrate / 1_000_000f

    companion object {
        /**
         * 计算比特率
         * @param sampleRate 采样率
         * @param channels 通道数
         * @param bitsPerSample 每样本位数
         */
        fun calculateBitrate(sampleRate: Int, channels: Int, bitsPerSample: Int): Int {
            return sampleRate * channels * bitsPerSample
        }
    }
}

/**
 * 音频电平数据
 * 包含 RMS、峰值和分贝值
 */
data class AudioLevelData(
    /** RMS 电平 (0-1) */
    val rms: Float,
    /** 峰值电平 (0-1) */
    val peak: Float,
    /** RMS 分贝值 (-∞ to 0 dBFS) */
    val rmsDb: Float,
    /** 峰值分贝值 (-∞ to 0 dBFS) */
    val peakDb: Float
) {
    companion object {
        /** 最小可检测 RMS 值，避免 log10(0) */
        private const val MIN_RMS = 0.0001f

        /** 默认静音状态 */
        val SILENT = AudioLevelData(
            rms = 0f,
            peak = 0f,
            rmsDb = NEGATIVE_INFINITY_DB,
            peakDb = NEGATIVE_INFINITY_DB
        )

        /** 表示负无穷分贝的常量（用于静音状态） */
        const val NEGATIVE_INFINITY_DB = -100f

        /**
         * 从缓冲区计算 AudioLevelData
         * @param buffer 16-bit PCM 音频缓冲区
         * @return AudioLevelData
         */
        fun fromBuffer(buffer: ByteArray): AudioLevelData {
            if (buffer.isEmpty()) return SILENT

            var sum = 0.0
            var maxSample = 0.0
            var count = 0

            var i = 0
            while (i + 1 < buffer.size) {
                val lo = buffer[i].toInt() and 0xFF
                val hi = buffer[i + 1].toInt()
    val sample = (hi shl 8) or lo
                val normalized = sample / 32768.0

                sum += normalized * normalized
                maxSample = maxOf(maxSample, kotlin.math.abs(normalized))
                count++
                i += 2
            }

            if (count == 0) return SILENT

            val rms = sqrt(sum / count).toFloat().coerceIn(0f, 1f)
    val peak = maxSample.toFloat().coerceIn(0f, 1f)

            return fromRmsAndPeak(rms, peak)
        }

        /**
         * 从 RMS 值创建 AudioLevelData
         * @param rms RMS 电平 (0-1)
         */
        fun fromRms(rms: Float): AudioLevelData {
            return fromRmsAndPeak(rms, rms)
        }

        /**
         * 从 RMS 和峰值创建 AudioLevelData
         * @param rms RMS 电平 (0-1)
         * @param peak 峰值电平 (0-1)
         */
        fun fromRmsAndPeak(rms: Float, peak: Float): AudioLevelData {
            val safeRms = rms.coerceIn(MIN_RMS, 1f)
    val safePeak = peak.coerceIn(MIN_RMS, 1f)
    val rmsDb = 20f * log10(safeRms)
    val peakDb = 20f * log10(safePeak)

            return AudioLevelData(
                rms = rms.coerceIn(0f, 1f),
                peak = peak.coerceIn(0f, 1f),
                rmsDb = rmsDb.coerceIn(NEGATIVE_INFINITY_DB, 0f),
                peakDb = peakDb.coerceIn(NEGATIVE_INFINITY_DB, 0f)
            )
        }

        /**
         * 将分贝值转换为线性值
         * @param db 分贝值 (-∞ to 0)
         */
        fun dbToLinear(db: Float): Float {
            if (db <= NEGATIVE_INFINITY_DB) return 0f
            return 10f.pow(db / 20f)
        }
    }
}