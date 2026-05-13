package com.lanrhyme.micyou.audio

import com.lanrhyme.micyou.AudioPlaybackMessage
import com.lanrhyme.micyou.Logger
import com.lanrhyme.micyou.platform.PlatformInfo
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest

/**
 * Manages desktop audio loopback capture and transmission.
 */
class LoopbackManager(
    private val onCapturedData: suspend (AudioPlaybackMessage) -> Unit
) {
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var captureJob: Job? = null
    
    private val capture: LoopbackCapture = when {
        PlatformInfo.isWindows -> WindowsLoopbackCapture()
        PlatformInfo.isLinux -> LinuxLoopbackCapture()
        PlatformInfo.isMacOS -> MacOSLoopbackCapture()
        else -> StubLoopbackCapture()
    }
    
    private var seqNum: Long = 0

    fun setSpeakerMode(enabled: Boolean) {
        if (enabled) {
            start()
        } else {
            stop()
        }
    }

    private fun start() {
        if (capture.isActive) return
        
        Logger.i("LoopbackManager", "Starting loopback capture...")
        // Reset sequence number for each new capture session
        seqNum = 0
        // WASAPI Mix Format is usually 48000Hz Stereo. We'll let the implementation report its format.
        capture.start(48000, 2)
        
        captureJob = scope.launch {
            capture.capturedData.collect { data ->
                val captureFormat = capture.format
                val protocolFormat = when(captureFormat.bitsPerSample) {
                    8 -> com.lanrhyme.micyou.AudioFormat.PCM_8BIT.value
                    16 -> com.lanrhyme.micyou.AudioFormat.PCM_16BIT.value
                    24 -> com.lanrhyme.micyou.AudioFormat.PCM_24BIT.value
                    32 -> com.lanrhyme.micyou.AudioFormat.PCM_FLOAT.value
                    else -> com.lanrhyme.micyou.AudioFormat.PCM_16BIT.value
                }
                
                // Send the full WASAPI buffer as one packet (~3840 bytes for 48kHz stereo float).
                // Previous 1024-byte chunking caused ~375 packets/sec which overwhelmed WiFi and
                // caused burst packet loss. One packet per capture (~93/sec) is much more reliable
                // even with IP fragmentation on local networks.
                val message = AudioPlaybackMessage(
                    buffer = data,
                    sampleRate = captureFormat.sampleRate,
                    channelCount = captureFormat.channelCount,
                    audioFormat = protocolFormat,
                    sequenceNumber = (seqNum++).toInt()
                )
                onCapturedData(message)
            }
        }
    }

    private fun stop() {
        Logger.i("LoopbackManager", "Stopping loopback capture...")
        capture.stop()
        captureJob?.cancel()
        captureJob = null
    }
}
