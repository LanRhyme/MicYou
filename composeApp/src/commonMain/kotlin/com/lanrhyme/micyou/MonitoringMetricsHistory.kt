package com.lanrhyme.micyou

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 监控指标历史记录
 * 用于绘制延迟趋势图等
 * 
 * 注意：此类是线程安全的。
 */
class MonitoringMetricsHistory(
    /** 最大记录数量 */
    private val maxSamples: Int = 100,
    /** 采样间隔（毫秒） */
    private val sampleIntervalMs: Long = 500
) {
    private val samples = mutableListOf<AudioMetrics>()
    private var lastSampleTime: Long = 0
    private val mutex = Mutex()

    /**
     * 添加新指标样本
     */
    suspend fun addSample(metrics: AudioMetrics) {
        val now = System.currentTimeMillis()

        mutex.withLock {
            if (now - lastSampleTime >= sampleIntervalMs) {
                samples.add(metrics)
                lastSampleTime = now

                if (samples.size > maxSamples) {
                    samples.removeAt(0)
                }
            }
        }
    }

    /**
     * 获取所有样本
     */
    suspend fun getSamples(): List<AudioMetrics> = mutex.withLock {
        samples.toList()
    }

    /**
     * 清空历史记录
     */
    suspend fun clear() {
        mutex.withLock {
            samples.clear()
            lastSampleTime = 0
        }
    }

    suspend fun size(): Int = mutex.withLock { samples.size }
    suspend fun hasData(): Boolean = mutex.withLock { samples.isNotEmpty() }
}
