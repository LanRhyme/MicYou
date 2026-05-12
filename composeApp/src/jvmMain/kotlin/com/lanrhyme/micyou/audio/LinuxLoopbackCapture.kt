package com.lanrhyme.micyou.audio

import com.lanrhyme.micyou.Logger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.io.InputStream

class LinuxLoopbackCapture : LoopbackCapture {
    private var job: Job? = null
    @Volatile
    private var process: Process? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    private val _capturedData = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)
    override val capturedData: SharedFlow<ByteArray> = _capturedData.asSharedFlow()
    
    @Volatile
    override var isActive: Boolean = false
        private set
        
    @Volatile
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

                // We'll try 'parec' (PulseAudio) first, then fallback to 'pw-record' (PipeWire)
                val commands = listOf(
                    listOf("parec", "--format=s16le", "--rate=$sampleRate", "--channels=$channelCount", "--device=$monitorSource"),
                    listOf("pw-record", "--format=s16le", "--rate=$sampleRate", "--channels=$channelCount", "--target=$monitorSource")
                )

                var started = false
                for (command in commands) {
                    try {
                        val p = ProcessBuilder(command).start()
                        // Check if it immediately exited
                        delay(500)
                        if (p.isAlive) {
                            process = p
                            started = true
                            Logger.i("LinuxLoopback", "Successfully started capture with ${command[0]}")
                            break
                        } else {
                            p.destroy()
                        }
                    } catch (e: Exception) {
                        Logger.w("LinuxLoopback", "Failed to start ${command[0]}: ${e.message}")
                    }
                }

                if (!started) throw Exception("Failed to start any capture tool (parec/pw-record)")

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
                if (e !is CancellationException) {
                    Logger.e("LinuxLoopback", "Capture error: ${e.message}")
                }
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
        var p: Process? = null
        return try {
            // Get default sink name
            p = ProcessBuilder("pactl", "get-default-sink").start()
            val defaultSink = p.inputStream.bufferedReader().readText().trim()
            p.waitFor()
            if (defaultSink.isNotEmpty()) {
                "$defaultSink.monitor"
            } else {
                null
            }
        } catch (e: Exception) {
            null
        } finally {
            p?.destroy()
        }
    }
}
