package com.lanrhyme.micyou.audio

import com.lanrhyme.micyou.Logger
import com.lanrhyme.micyou.BlackHoleManager
import com.lanrhyme.micyou.platform.PlatformInfo
import com.lanrhyme.micyou.platform.VirtualAudioDevice
import javax.sound.sampled.*

class AudioOutputManager {
    private var outputLine: SourceDataLine? = null
    private var isUsingVirtualDevice = false
    private var isMonitoring = false
    private var currentSampleRate = 0
    private var currentChannelCount = 0
    
    fun init(sampleRate: Int, channelCount: Int): Boolean {
        if (outputLine != null) {
            if (currentSampleRate == sampleRate && currentChannelCount == channelCount) {
                return true
            }
            release()
        }
        
        Logger.d("AudioOutputManager", "初始化音频输出: 采样率=$sampleRate, 声道数=$channelCount")
        
        currentSampleRate = sampleRate
        currentChannelCount = channelCount
        
        val audioFormat = AudioFormat(
            sampleRate.toFloat(),
            16,
            channelCount,
            true,
            false
        )
        
        val lineInfo = DataLine.Info(SourceDataLine::class.java, audioFormat)
        
        if (PlatformInfo.isLinux) {
            val success = initLinux(audioFormat, lineInfo)
            if (success) return true
        }
        
        if (PlatformInfo.isMacOS) {
            val success = initMacOS(audioFormat, lineInfo)
            if (success) return true
        }
        
        return initDefault(audioFormat, lineInfo)
    }
    
    private fun initLinux(audioFormat: AudioFormat, lineInfo: DataLine.Info): Boolean {
        Logger.d("AudioOutputManager", "Linux 平台: 尝试使用 PipeWire 虚拟设备")
        
        if (!VirtualAudioDevice.isAvailable()) {
            Logger.w("AudioOutputManager", "PipeWire 不可用，回退到默认设备")
            return false
        }
        
        if (!VirtualAudioDevice.isSetupComplete()) {
            Logger.i("AudioOutputManager", "设置 PipeWire 虚拟音频设备...")
            if (!VirtualAudioDevice.setup()) {
                Logger.e("AudioOutputManager", "设置虚拟音频设备失败")
                return false
            }
        }
        
        val sinkName = VirtualAudioDevice.virtualSinkName
        Logger.i("AudioOutputManager", "尝试连接到虚拟 Sink: $sinkName")
        
        val mixers = AudioSystem.getMixerInfo()
        for (mixerInfo in mixers) {
            val mixerName = mixerInfo.name.lowercase()
            if (mixerName.contains("micyou") || mixerName.contains("virtual")) {
                try {
                    val mixer = AudioSystem.getMixer(mixerInfo)
                    if (mixer.isLineSupported(lineInfo)) {
                        outputLine = mixer.getLine(lineInfo) as SourceDataLine
                        isUsingVirtualDevice = true
                        Logger.i("AudioOutputManager", "使用虚拟设备: ${mixerInfo.name}")
                        return openAndStartLine(audioFormat)
                    }
                } catch (e: Exception) {
                    Logger.d("AudioOutputManager", "混音器 ${mixerInfo.name} 不支持此格式")
                }
            }
        }
        
        Logger.w("AudioOutputManager", "未找到 PipeWire 虚拟设备混音器，使用 PulseAudio 方式")
        return initPulseAudio(audioFormat)
    }
    
    private fun initPulseAudio(audioFormat: AudioFormat): Boolean {
        try {
            val pulseMixer = AudioSystem.getMixerInfo().find { 
                it.name.contains("pulse", ignoreCase = true) 
            }
            
            if (pulseMixer != null) {
                val mixer = AudioSystem.getMixer(pulseMixer)
                if (mixer.isLineSupported(DataLine.Info(SourceDataLine::class.java, audioFormat))) {
                    outputLine = mixer.getLine(DataLine.Info(SourceDataLine::class.java, audioFormat)) as SourceDataLine
                    isUsingVirtualDevice = true
                    Logger.i("AudioOutputManager", "使用 PulseAudio 混音器")
                    return openAndStartLine(audioFormat)
                }
            }
        } catch (e: Exception) {
            Logger.w("AudioOutputManager", "PulseAudio 方式失败: ${e.message}")
        }
        
        return false
    }
    
    private fun initMacOS(audioFormat: AudioFormat, lineInfo: DataLine.Info): Boolean {
        Logger.d("AudioOutputManager", "macOS 平台: 尝试使用 BlackHole 虚拟设备")
        
        if (!BlackHoleManager.isInstalled()) {
            Logger.w("AudioOutputManager", "BlackHole 未安装，回退到默认设备")
            return false
        }
        
        val blackHoleMixer = findBlackHoleMixer(lineInfo)
        if (blackHoleMixer != null) {
            try {
                outputLine = blackHoleMixer.getLine(lineInfo) as SourceDataLine
                isUsingVirtualDevice = true
                Logger.i("AudioOutputManager", "使用 BlackHole 虚拟设备: ${blackHoleMixer.mixerInfo.name}")
                return openAndStartLine(audioFormat)
            } catch (e: Exception) {
                Logger.e("AudioOutputManager", "初始化 BlackHole 失败", e)
            }
        }
        
        Logger.w("AudioOutputManager", "未找到 BlackHole 混音器，回退到默认设备")
        return false
    }
    
    private fun findBlackHoleMixer(lineInfo: DataLine.Info): Mixer? {
        val blackHolePattern = Regex("BlackHole\\s*\\d*ch", RegexOption.IGNORE_CASE)
        val mixers = AudioSystem.getMixerInfo()
        
        for (mixerInfo in mixers) {
            if (blackHolePattern.matches(mixerInfo.name)) {
                try {
                    val mixer = AudioSystem.getMixer(mixerInfo)
                    if (mixer.isLineSupported(lineInfo)) {
                        Logger.d("AudioOutputManager", "找到 BlackHole 混音器: ${mixerInfo.name}")
                        return mixer
                    }
                } catch (e: Exception) {
                    Logger.d("AudioOutputManager", "BlackHole 混音器检查失败: ${e.message}")
                }
            }
        }
        
        return null
    }
    
    private fun initDefault(audioFormat: AudioFormat, lineInfo: DataLine.Info): Boolean {
        Logger.d("AudioOutputManager", "尝试使用默认音频设备")
        
        if (PlatformInfo.isWindows) {
            val cableMixer = findVBCableMixer(lineInfo)
            if (cableMixer != null) {
                try {
                    outputLine = cableMixer.getLine(lineInfo) as SourceDataLine
                    isUsingVirtualDevice = true
                    Logger.i("AudioOutputManager", "使用 VB-CABLE Input")
                    return openAndStartLine(audioFormat)
                } catch (e: Exception) {
                    Logger.e("AudioOutputManager", "初始化 VB-CABLE 失败", e)
                }
            }
        }
        
        return try {
            outputLine = AudioSystem.getLine(lineInfo) as SourceDataLine
            isUsingVirtualDevice = false
            Logger.i("AudioOutputManager", "使用系统默认音频输出")
            openAndStartLine(audioFormat)
        } catch (e: Exception) {
            Logger.e("AudioOutputManager", "获取默认系统输出线路失败", e)
            false
        }
    }
    
    private fun findVBCableMixer(lineInfo: DataLine.Info): Mixer? {
        val mixers = AudioSystem.getMixerInfo()
        
        for (mixerInfo in mixers) {
            if (mixerInfo.name.contains("CABLE Input", ignoreCase = true)) {
                try {
                    val mixer = AudioSystem.getMixer(mixerInfo)
                    if (mixer.isLineSupported(lineInfo)) {
                        return mixer
                    }
                } catch (e: Exception) {
                    Logger.d("AudioOutputManager", "VB-CABLE 混音器检查失败: ${e.message}")
                }
            }
        }
        
        return null
    }
    
    private fun openAndStartLine(audioFormat: AudioFormat): Boolean {
        return try {
            val bytesPerSecond = (currentSampleRate * currentChannelCount * 2).coerceAtLeast(1)
            val bufferSizeBytes = (bytesPerSecond / 10).coerceIn(4096, 65536)
            
            outputLine?.open(audioFormat, bufferSizeBytes)
            outputLine?.start()
            
            Logger.d("AudioOutputManager", "音频输出线路已启动 (缓冲区: ${bufferSizeBytes}字节)")
            true
        } catch (e: Exception) {
            Logger.e("AudioOutputManager", "打开音频输出线路失败", e)
            outputLine = null
            false
        }
    }
    
    fun write(buffer: ByteArray, offset: Int, length: Int) {
        val shouldMute = !isUsingVirtualDevice && !isMonitoring && !usesSystemAudioSinkForVirtualOutput()
        
        if (shouldMute) {
            buffer.fill(0, offset, offset + length)
        }
        
        try {
            outputLine?.write(buffer, offset, length)
        } catch (e: Exception) {
            Logger.e("AudioOutputManager", "写入音频数据失败", e)
        }
    }
    
    fun getQueuedDurationMs(): Long {
        val line = outputLine ?: return 0L
        val bytesPerSecond = (line.format.sampleRate.toInt() * line.format.channels * 2).coerceAtLeast(1)
        val queuedBytes = (line.bufferSize - line.available()).coerceAtLeast(0)
        return queuedBytes * 1000L / bytesPerSecond.toLong()
    }
    
    fun flush() {
        outputLine?.flush()
    }
    
    fun setMonitoring(enabled: Boolean) {
        isMonitoring = enabled
    }
    
    fun isUsingVirtualDevice(): Boolean = isUsingVirtualDevice
    
    fun release() {
        Logger.d("AudioOutputManager", "释放音频输出资源")
        
        try {
            outputLine?.drain()
            outputLine?.close()
        } catch (e: Exception) {
            Logger.e("AudioOutputManager", "关闭音频输出线路时出错", e)
        }
        
        outputLine = null
        isUsingVirtualDevice = false
        
        if (PlatformInfo.isLinux && VirtualAudioDevice.isSetupComplete()) {
            Logger.i("AudioOutputManager", "清理 Linux 虚拟音频设备")
            VirtualAudioDevice.cleanup()
        }
    }
    
    private fun usesSystemAudioSinkForVirtualOutput(): Boolean {
        return PlatformInfo.isLinux || PlatformInfo.isMacOS
    }
}
