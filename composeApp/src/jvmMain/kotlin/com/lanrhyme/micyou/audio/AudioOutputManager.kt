package com.lanrhyme.micyou.audio

import com.lanrhyme.micyou.Logger
import com.lanrhyme.micyou.BlackHoleManager
import com.lanrhyme.micyou.platform.PlatformInfo
import com.lanrhyme.micyou.platform.VirtualAudioDevice
import javax.sound.sampled.*

class AudioOutputManager {
    private var outputLine: SourceDataLine? = null
    private var monitorLoopbackProcess: Process? = null
    private var monitorLine: SourceDataLine? = null
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
        
        Logger.d("AudioOutputManager", "Initialize audio output: Sample rate = \$sampleRate, Channel count = \$channelCount")
        
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
        Logger.d("AudioOutputManager", "Linux Platform: Try using PipeWire virtual devices")
        
        if (!VirtualAudioDevice.isAvailable()) {
            Logger.w("AudioOutputManager", "PipeWire is unavailable; falling back to the default device.")
            return false
        }
        
        if (!VirtualAudioDevice.isSetupComplete()) {
            Logger.i("AudioOutputManager", "Set up PipeWire virtual audio devices...")
            if (!VirtualAudioDevice.setup()) {
                Logger.e("AudioOutputManager", "Failed to set up virtual audio device")
                return false
            }
        }
        
        val sinkName = VirtualAudioDevice.virtualSinkName
        Logger.i("AudioOutputManager", "Attempt to connect to the virtual sink: \$sinkName")
        
        val mixers = AudioSystem.getMixerInfo()
        for (mixerInfo in mixers) {
            val mixerName = mixerInfo.name.lowercase()
            if (mixerName.contains("micyou") || mixerName.contains("virtual")) {
                try {
                    val mixer = AudioSystem.getMixer(mixerInfo)
                    if (mixer.isLineSupported(lineInfo)) {
                        outputLine = mixer.getLine(lineInfo) as SourceDataLine
                        isUsingVirtualDevice = true
                        Logger.i("AudioOutputManager", "Using virtual device: \${mixerInfo.name}")
                        return openAndStartLine(audioFormat)
                    }
                } catch (e: Exception) {
                    Logger.d("AudioOutputManager", "The mixer \${mixerInfo.name} does not support this format.")
                }
            }
        }
        
        Logger.w("AudioOutputManager", "PipeWire virtual device mixer not found; using PulseAudio method instead.")
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
                    Logger.i("AudioOutputManager", "Using the PulseAudio mixer")
                    return openAndStartLine(audioFormat)
                }
            }
        } catch (e: Exception) {
            Logger.w("AudioOutputManager", "PulseAudio method failed: \${e.message}")
        }
        
        return false
    }
    
    private fun initMacOS(audioFormat: AudioFormat, lineInfo: DataLine.Info): Boolean {
        Logger.d("AudioOutputManager", "macOS: Try using the BlackHole virtual device")
        
        if (!BlackHoleManager.isInstalled()) {
            Logger.w("AudioOutputManager", "BlackHole not installed, reverting to default device")
            return false
        }
        
        val blackHoleMixer = findBlackHoleMixer(lineInfo)
        if (blackHoleMixer != null) {
            try {
                outputLine = blackHoleMixer.getLine(lineInfo) as SourceDataLine
                isUsingVirtualDevice = true
                Logger.i("AudioOutputManager", "Using the BlackHole virtual device: \${blackHoleMixer.mixerInfo.name}")
                return openAndStartLine(audioFormat)
            } catch (e: Exception) {
                Logger.e("AudioOutputManager", "Failed to initialize BlackHole", e)
            }
        }
        
        Logger.w("AudioOutputManager", "BlackHole mixer not found; reverting to default device.")
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
                        Logger.d("AudioOutputManager", "Found BlackHole mixer: \${mixerInfo.name}")
                        return mixer
                    }
                } catch (e: Exception) {
                    Logger.d("AudioOutputManager", "BlackHole mixer Check Failed: \${e.message}")
                }
            }
        }
        
        return null
    }
    
    private fun initDefault(audioFormat: AudioFormat, lineInfo: DataLine.Info): Boolean {
        Logger.d("AudioOutputManager", "Try using the default audio device")
        
        if (PlatformInfo.isWindows) {
            val cableMixer = findVBCableMixer(lineInfo)
            if (cableMixer != null) {
                try {
                    outputLine = cableMixer.getLine(lineInfo) as SourceDataLine
                    isUsingVirtualDevice = true
                    Logger.i("AudioOutputManager", "Using VB-CABLE Input")
                    return openAndStartLine(audioFormat)
                } catch (e: Exception) {
                    Logger.e("AudioOutputManager", "Failed to initialize VB-CABLE", e)
                }
            }
        }
        
        return try {
            outputLine = AudioSystem.getLine(lineInfo) as SourceDataLine
            isUsingVirtualDevice = false
            Logger.i("AudioOutputManager", "Use the system's default audio output")
            openAndStartLine(audioFormat)
        } catch (e: Exception) {
            Logger.e("AudioOutputManager", "Failed to obtain the default system output device.", e)
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
                    Logger.d("AudioOutputManager", "VB-CABLE mixer check failed: ${e.message}")
                }
            }
        }
        
        return null
    }
    
    private fun openAndStartLine(audioFormat: AudioFormat): Boolean {
        return try {
            val bytesPerSecond = (currentSampleRate * currentChannelCount * 2).coerceAtLeast(1)
            val bufferSizeBytes = (bytesPerSecond / 4).coerceIn(8192, 131072)
            
            outputLine?.open(audioFormat, bufferSizeBytes)
            outputLine?.start()
            
            Logger.d("AudioOutputManager", "Audio output line has been activated (Buffer: \${bufferSizeBytes} bytes)")
            true
        } catch (e: Exception) {
            Logger.e("AudioOutputManager", "Failed to open audio output line", e)
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
            Logger.e("AudioOutputManager", "Failed to write audio data", e)
        }
        
        try {
            monitorLine?.write(buffer, offset, length)
        } catch (e: Exception) {
            Logger.e("AudioOutputManager", "Failed to write monitor audio data", e)
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
        monitorLine?.flush()
    }
    
    fun setMonitoring(enabled: Boolean) {
        isMonitoring = enabled
        if (enabled) {
            startMonitorLoopback()
        } else {
            stopMonitorLoopback()
        }
    }
    
    private fun startMonitorLoopback() {
        if (PlatformInfo.isLinux) {
            startLinuxMonitorLoopback()
        } else {
            openMonitorLine()
        }
    }
    
    private fun startLinuxMonitorLoopback() {
        if (monitorLoopbackProcess?.isAlive == true) return
        if (!VirtualAudioDevice.isSetupComplete()) {
            Logger.w("AudioOutputManager", "Monitor loopback not available: virtual device not setup")
            return
        }
        try {
            val sinkName = VirtualAudioDevice.virtualSinkName
            val process = ProcessBuilder(
                "pw-loopback",
                "--capture-props={\"node.target\": \"$sinkName\", \"media.class\": \"Stream/Input/Audio\", \"stream.capture.sink\": true}",
                "--playback-props={\"media.class\": \"Stream/Output/Audio\"}"
            ).redirectErrorStream(true).start()
            
            Thread.sleep(200)
            
            if (process.isAlive) {
                monitorLoopbackProcess = process
                Logger.i("AudioOutputManager", "Monitor loopback started (pid: ${process.pid()})")
            } else {
                val output = process.inputStream.bufferedReader().readText()
                Logger.e("AudioOutputManager", "Monitor loopback failed to start: $output")
            }
        } catch (e: Exception) {
            Logger.e("AudioOutputManager", "Failed to start monitor loopback", e)
        }
    }
    
    private fun openMonitorLine() {
        if (monitorLine != null) return
        if (currentSampleRate == 0 || currentChannelCount == 0) return
        
        val audioFormat = AudioFormat(
            currentSampleRate.toFloat(), 16, currentChannelCount, true, false
        )
        val lineInfo = DataLine.Info(SourceDataLine::class.java, audioFormat)
        
        try {
            val line = AudioSystem.getLine(lineInfo) as SourceDataLine
            val bytesPerSecond = (currentSampleRate * currentChannelCount * 2).coerceAtLeast(1)
            line.open(audioFormat, (bytesPerSecond / 4).coerceIn(8192, 131072))
            line.start()
            monitorLine = line
            Logger.i("AudioOutputManager", "Monitor line opened (system default speaker)")
        } catch (e: Exception) {
            Logger.e("AudioOutputManager", "Failed to open monitor line", e)
        }
    }
    
    private fun stopMonitorLoopback() {
        monitorLoopbackProcess?.let { process ->
            try {
                if (process.isAlive) {
                    process.destroy()
                    Logger.i("AudioOutputManager", "Monitor loopback stopped")
                }
            } catch (e: Exception) {
                Logger.e("AudioOutputManager", "Error stopping monitor loopback", e)
            }
        }
        monitorLoopbackProcess = null
        
        try {
            monitorLine?.stop()
            monitorLine?.close()
        } catch (e: Exception) {
            Logger.e("AudioOutputManager", "Error closing monitor line", e)
        }
        monitorLine = null
    }
    
    fun isUsingVirtualDevice(): Boolean = isUsingVirtualDevice
    
    fun release() {
        Logger.d("AudioOutputManager", "Release audio output resources")
        
        stopMonitorLoopback()
        
        try {
            outputLine?.drain()
            outputLine?.close()
        } catch (e: Exception) {
            Logger.e("AudioOutputManager", "Error occurred while disabling the audio output line.", e)
        }
        
        outputLine = null
        isUsingVirtualDevice = false
        
        if (PlatformInfo.isLinux && VirtualAudioDevice.isSetupComplete()) {
            Logger.i("AudioOutputManager", "Cleaning Up Linux Virtual Audio Devices")
            VirtualAudioDevice.cleanup()
        }
    }
    
    private fun usesSystemAudioSinkForVirtualOutput(): Boolean {
        return PlatformInfo.isLinux || PlatformInfo.isMacOS
    }
}

