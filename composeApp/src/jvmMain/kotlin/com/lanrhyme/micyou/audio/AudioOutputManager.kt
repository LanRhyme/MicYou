package com.lanrhyme.micyou.audio

import com.lanrhyme.micyou.Logger
import com.lanrhyme.micyou.PlatformUtils
import javax.sound.sampled.*

/**
 * 音频输出管理器
 * 负责管理 SourceDataLine（音频输出设备），包括：
 * 1. 初始化音频输出（优先尝试 VB-CABLE，失败则回退到默认设备）
 * 2. Linux 平台的音频重定向
 * 3. 写入音频数据
 * 4. 监听状态管理
 */
class AudioOutputManager {
    private var monitoringLine: SourceDataLine? = null
    private var isUsingCable = false
    private var isMonitoring = false
    private var originalDefaultSink: String? = null

    /**
     * 初始化音频输出设备
     * @param sampleRate 采样率
     * @param channelCount 声道数
     * @return 是否成功初始化
     */
    fun init(sampleRate: Int, channelCount: Int): Boolean {
        if (monitoringLine != null) {
            return true
        }

        Logger.d("AudioOutputManager", "初始化音频输出: 采样率=$sampleRate, 声道数=$channelCount")
        
        // 在 Linux 平台重定向音频输出到虚拟设备
        if (PlatformUtils.isLinux) {
            originalDefaultSink = PlatformUtils.getDefaultSink()
            Logger.i("AudioOutputManager", "Linux平台：保存原始默认音频输出设备: $originalDefaultSink")
            
            val success = PlatformUtils.redirectAudioToVirtualDevice()
            if (success) {
                Logger.i("AudioOutputManager", "成功重定向音频输出到虚拟设备")
            } else {
                Logger.w("AudioOutputManager", "音频重定向失败，可能影响内录功能")
            }
        }

        val audioFormat = AudioFormat(
            sampleRate.toFloat(),
            16,
            channelCount,
            true,
            false
        )

        val info = DataLine.Info(SourceDataLine::class.java, audioFormat)

        // 尝试寻找 VB-CABLE
        val mixers = AudioSystem.getMixerInfo()
        Logger.d("AudioOutputManager", "发现 ${mixers.size} 个混音器")
        
        val cableMixerInfo = mixers
            .filter { it.name.contains("CABLE Input", ignoreCase = true) }
            .find { mixerInfo ->
                try {
                    val mixer = AudioSystem.getMixer(mixerInfo)
                    mixer.isLineSupported(info)
                } catch (e: Exception) {
                    false
                }
            }

        if (cableMixerInfo != null) {
            try {
                val mixer = AudioSystem.getMixer(cableMixerInfo)
                monitoringLine = mixer.getLine(info) as SourceDataLine
                isUsingCable = true
                Logger.i("AudioOutputManager", "使用 VB-CABLE Input 进行音频输出: ${cableMixerInfo.name}")
            } catch (e: Exception) {
                Logger.e("AudioOutputManager", "初始化 VB-CABLE 失败", e)
            }
        }

        // 如果没有找到 VB-CABLE 或初始化失败，回退到默认设备
        if (monitoringLine == null) {
            Logger.w("AudioOutputManager", "未找到 VB-CABLE Input 或不支持该格式，尝试使用默认输出")
            try {
                monitoringLine = AudioSystem.getLine(info) as SourceDataLine
                isUsingCable = false
                Logger.i("AudioOutputManager", "使用系统默认音频输出")
            } catch (e: Exception) {
                Logger.e("AudioOutputManager", "获取默认系统输出线路失败", e)
                return false
            }
        }

        try {
            val bytesPerSecond = (sampleRate * channelCount * 2).coerceAtLeast(1)
            // 缓冲区大小设为约 200ms
            val bufferSizeBytes = (bytesPerSecond / 5).coerceIn(8192, 131072)
            monitoringLine?.open(audioFormat, bufferSizeBytes)
            monitoringLine?.start()
            return true
        } catch (e: Exception) {
            Logger.e("AudioOutputManager", "打开音频输出线路失败", e)
            return false
        }
    }

    /**
     * 写入音频数据
     * @param buffer 音频数据缓冲区
     * @param offset 偏移量
     * @param length 长度
     */
    fun write(buffer: ByteArray, offset: Int, length: Int) {
        // 只有在既没使用虚拟电缆也没开启监听时才静音
        // 在 Linux 平台上，音频需要发送到虚拟设备，不应静音
        if (!isUsingCable && !isMonitoring && !PlatformUtils.isLinux) {
            // 静音处理：将缓冲区填零
            buffer.fill(0, offset, offset + length)
        }

        try {
            monitoringLine?.write(buffer, offset, length)
        } catch (e: Exception) {
            Logger.e("AudioOutputManager", "写入音频数据失败", e)
        }
    }

    /**
     * 获取输出缓冲区中排队的数据时长（毫秒）
     * 用于控制播放速度（重采样）
     */
    fun getQueuedDurationMs(): Long {
        val line = monitoringLine ?: return 0L
        val bytesPerSecond = (line.format.sampleRate.toInt() * line.format.channels * 2).coerceAtLeast(1)
        val queuedBytes = (line.bufferSize - line.available()).coerceAtLeast(0)
        return queuedBytes * 1000L / bytesPerSecond.toLong()
    }

    /**
     * 清空输出缓冲区
     */
    fun flush() {
        monitoringLine?.flush()
    }

    /**
     * 设置是否监听（当不使用虚拟声卡时，控制是否通过扬声器播放）
     */
    fun setMonitoring(enabled: Boolean) {
        isMonitoring = enabled
    }

    /**
     * 释放资源
     */
    fun release() {
        monitoringLine?.drain()
        monitoringLine?.close()
        monitoringLine = null
        
        // 在 Linux 平台恢复原始默认音频输出设备
        if (PlatformUtils.isLinux && originalDefaultSink != null) {
            Logger.i("AudioOutputManager", "Linux平台：恢复原始默认音频输出设备: $originalDefaultSink")
            val success = PlatformUtils.restoreDefaultSink(originalDefaultSink!!)
            if (success) {
                Logger.i("AudioOutputManager", "成功恢复原始音频输出设备")
            } else {
                Logger.w("AudioOutputManager", "恢复原始音频输出设备失败")
            }
            originalDefaultSink = null
        }
    }
}
