package com.lanrhyme.micyou.audio

import com.lanrhyme.micyou.Logger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.io.InputStream

class LinuxLoopbackCapture : LoopbackCapture {
    private var job: Job? = null
    private var process: Process? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    private val _capturedData = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)
    override val capturedData: SharedFlow<ByteArray> = _capturedData.asSharedFlow()
    
    override var isActive: Boolean = false
        private set
        
    override var format: LoopbackCapture.LoopbackFormat = LoopbackCapture.LoopbackFormat(44100, 2, 16)
        private set

    override fun start(sampleRate: Int, channelCount: Int) {
        if (isActive) return
        
        format = LoopbackCapture.LoopbackFormat(sampleRate, channelCount, 16)
        isActive = true
        
        job = scope.launch {
            try {
                // Try to find the default monitor source
                val monitorSource = findMonitorSource() ?: "auto"
                Logger.i("LinuxLoopback", "Starting capture from source: $monitorSource")

                // We'll use 'parec' (PulseAudio) or 'pw-record' (PipeWire)
                // parec --format=s16le --rate=44100 --channels=2 --device=...
                val command = listOf(
                    "parec",
                    "--format=s16le",
                    "--rate=$sampleRate",
                    "--channels=$channelCount",
                    "--device=$monitorSource"
                )

                process = ProcessBuilder(command).start()
                val inputStream = process?.inputStream ?: throw Exception("Failed to open process input stream")

                val buffer = ByteArray(4096)
                while (isActive && coroutineContext.isActive) {
                    val read = withContext(Dispatchers.IO) {
                        inputStream.read(buffer)
                    }
                    if (read > 0) {
                        _capturedData.emit(buffer.copyOfRange(0, read))
                    } else if (read == -1) {
                        break
                    }
                }
            } catch (e: Exception) {
                Logger.e("LinuxLoopback", "Capture error: ${e.message}")
            } finally {
                stop()
            }
        }
    }

    override fun stop() {
        isActive = false
        process?.destroy()
        process = null
        job?.cancel()
        job = null
    }

    private fun findMonitorSource(): String? {
        return try {
            // Get default sink name
            val defaultSinkProcess = ProcessBuilder("pactl", "get-default-sink").start()
            val defaultSink = defaultSinkProcess.inputStream.bufferedReader().readText().trim()
            if (defaultSink.isNotEmpty()) {
                "$defaultSink.monitor"
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }
}
