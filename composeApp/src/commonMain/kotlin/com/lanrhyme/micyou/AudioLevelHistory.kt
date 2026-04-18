package com.lanrhyme.micyou

/**
 * 音频电平历史记录
 * 用于绘制历史图表和统计峰值
 */
class AudioLevelHistory(
    /** 最大记录时长（秒） */
    private val maxDurationSeconds: Int = 10,
    /** 采样间隔（毫秒） */
    private val sampleIntervalMs: Long = 100
) {
    /** 单个电平样本 */
    data class AudioLevelSample(
        /** 采样时间戳 */
        val timestamp: Long,
        /** RMS 电平 */
        val rms: Float,
        /** 峰值电平 */
        val peak: Float,
        /** RMS 分贝值 */
        val rmsDb: Float,
        /** 峰值分贝值 */
        val peakDb: Float
    )

    private val samples = mutableListOf<AudioLevelSample>()
    private var lastSampleTime: Long = 0

    /**
     * 添加新样本
     * 按采样间隔控制添加频率，自动移除过期样本
     */
    fun addSample(levelData: AudioLevelData) {
        val now = System.currentTimeMillis()

        // 按采样间隔添加样本
        if (now - lastSampleTime >= sampleIntervalMs) {
            samples.add(AudioLevelSample(
                timestamp = now,
                rms = levelData.rms,
                peak = levelData.peak,
                rmsDb = levelData.rmsDb,
                peakDb = levelData.peakDb
            ))
            lastSampleTime = now

            // 移除过期样本
            val cutoffTime = now - (maxDurationSeconds * 1000L)
            samples.removeAll { it.timestamp < cutoffTime }
        }
    }

    /**
     * 获取所有样本（用于绘制历史图表）
     */
    fun getSamples(): List<AudioLevelSample> = samples.toList()

    /**
     * 获取最近指定秒数内的峰值
     * @param seconds 时间范围（秒）
     * @return 该范围内的最大峰值电平
     */
    fun getPeakInRange(seconds: Int): Float {
        val cutoff = System.currentTimeMillis() - (seconds * 1000L)
        return samples.filter { it.timestamp >= cutoff }
            .maxOfOrNull { it.peak } ?: 0f
    }

    /**
     * 获取最近指定秒数内的平均 RMS
     * @param seconds 时间范围（秒）
     * @return 该范围内的平均 RMS 电平
     */
    fun getAverageRms(seconds: Int): Float {
        val cutoff = System.currentTimeMillis() - (seconds * 1000L)
        val relevantSamples = samples.filter { it.timestamp >= cutoff }
        if (relevantSamples.isEmpty()) return 0f
        return relevantSamples.sumOf { it.rms.toDouble() }.toFloat() / relevantSamples.size
    }

    /**
     * 清空历史记录
     */
    fun clear() {
        samples.clear()
        lastSampleTime = 0
    }

    /**
     * 获取样本数量
     */
    fun size(): Int = samples.size

    /**
     * 是否有样本数据
     */
    fun hasData(): Boolean = samples.isNotEmpty()
}