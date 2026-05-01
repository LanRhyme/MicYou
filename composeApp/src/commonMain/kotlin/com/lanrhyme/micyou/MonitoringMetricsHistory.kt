package com.lanrhyme.micyou

/**
 * 监控指标历史记录
 * 用于绘制延迟趋势图等
 */
class MonitoringMetricsHistory(
    /** 最大记录数量 */
    private val maxSamples: Int = 100,
    /** 采样间隔（毫秒） */
    private val sampleIntervalMs: Long = 500
) {
    private val samples = mutableListOf<AudioMetrics>()
    private var lastSampleTime: Long = 0

    /**
     * 添加新指标样本
     */
    fun addSample(metrics: AudioMetrics) {
        val now = System.currentTimeMillis()

        if (now - lastSampleTime >= sampleIntervalMs) {
            samples.add(metrics)
            lastSampleTime = now

            if (samples.size > maxSamples) {
                samples.removeAt(0)
            }
        }
    }

    /**
     * 获取所有样本
     */
    fun getSamples(): List<AudioMetrics> = samples.toList()

    /**
     * 清空历史记录
     */
    fun clear() {
        samples.clear()
        lastSampleTime = 0
    }

    fun size(): Int = samples.size
    fun hasData(): Boolean = samples.isNotEmpty()
}
