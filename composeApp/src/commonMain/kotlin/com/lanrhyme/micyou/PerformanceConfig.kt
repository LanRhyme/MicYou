package com.lanrhyme.micyou

/**
 * 性能配置参数
 * 用于调整音频处理管道的缓冲区和内存分配策略
 */
data class PerformanceConfig(
    // ==================== AudioProcessorPipeline 缓冲区配置 ====================
    /** Short 数组缓冲区初始容量 */
    val initialShortsCapacity: Int = 16384,
    /** Byte 数组缓冲区初始容量 */
    val initialBytesCapacity: Int = 32768,
    /** 缓冲区增长因子 */
    val bufferGrowthFactor: Float = 1.5f,

    // ==================== AudioOutputManager 缓冲区配置 ====================
    /** 输出缓冲时长（秒） */
    val outputBufferSeconds: Float = 0.25f,
    /** 最小输出缓冲区大小（字节） */
    val minOutputBufferBytes: Int = 8192,
    /** 最大输出缓冲区大小（字节） */
    val maxOutputBufferBytes: Int = 131072,

    // ==================== Channel 容量配置 ====================
    /** 音频包处理通道容量 */
    val audioPacketChannelCapacity: Int = 32,
    /** 控制消息发送通道容量 */
    val messageChannelCapacity: Int = 64,

    // ==================== 动态调整配置 ====================
    /** 启用动态缓冲区调整 */
    val enableDynamicBufferAdjustment: Boolean = false,
    /** 动态调整检查间隔（毫秒） */
    val bufferAdjustmentIntervalMs: Long = 5000,
    /** 低延迟阈值（毫秒） - 低于此值可能卡顿 */
    val lowLatencyThresholdMs: Long = 50,
    /** 高延迟阈值（毫秒） - 高于此值需减少缓冲 */
    val highLatencyThresholdMs: Long = 200
) {
    companion object {
        /** 默认配置 - 平衡性能和稳定性 */
        val DEFAULT = PerformanceConfig()

        /** 低延迟配置 - 适合实时通信 */
        val LOW_LATENCY = PerformanceConfig(
            initialShortsCapacity = 8192,
            initialBytesCapacity = 16384,
            bufferGrowthFactor = 1.3f,
            outputBufferSeconds = 0.1f,
            minOutputBufferBytes = 4096,
            maxOutputBufferBytes = 65536,
            audioPacketChannelCapacity = 16,
            enableDynamicBufferAdjustment = true,
            lowLatencyThresholdMs = 30,
            highLatencyThresholdMs = 100
        )

        /** 高质量配置 - 适合录音和高保真传输 */
        val HIGH_QUALITY = PerformanceConfig(
            initialShortsCapacity = 32768,
            initialBytesCapacity = 65536,
            bufferGrowthFactor = 2.0f,
            outputBufferSeconds = 0.5f,
            maxOutputBufferBytes = 262144,
            audioPacketChannelCapacity = 64,
            enableDynamicBufferAdjustment = false
        )

        /**
         * 根据模式名称获取配置
         * @param mode 模式名称 ("Default", "Low Latency", "High Quality")
         */
        fun fromMode(mode: String): PerformanceConfig {
            return when (mode) {
                "Low Latency" -> LOW_LATENCY
                "High Quality" -> HIGH_QUALITY
                else -> DEFAULT
            }
        }

        /**
         * 根据缓冲区大小倍数调整配置
         * @param multiplier 倍数 (0.5-2.0)
         */
        fun withBufferSizeMultiplier(multiplier: Float): PerformanceConfig {
            return DEFAULT.copy(
                outputBufferSeconds = DEFAULT.outputBufferSeconds * multiplier,
                initialShortsCapacity = (DEFAULT.initialShortsCapacity * multiplier).toInt(),
                initialBytesCapacity = (DEFAULT.initialBytesCapacity * multiplier).toInt()
            )
        }
    }
}