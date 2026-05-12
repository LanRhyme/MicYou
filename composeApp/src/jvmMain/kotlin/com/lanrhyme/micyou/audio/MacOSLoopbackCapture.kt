package com.lanrhyme.micyou.audio

import com.lanrhyme.micyou.Logger
import com.lanrhyme.micyou.platform.BlackHoleManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.TargetDataLine

/**
 * macOS loopback capture using BlackHole virtual audio driver.
 *
 * Requires BlackHole to be installed (https://github.com/ExistentialAudio/BlackHole).
 * Captures system audio by reading from the BlackHole input device,
 * which receives whatever is playing on the system output.
 */
class MacOSLoopbackCapture : LoopbackCapture {

    private var job: Job? = null
    @Volatile
    private var targetDataLine: TargetDataLine? = null
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

        if (!BlackHoleManager.isInstalled()) {
            Logger.e("MacOSLoopback", "BlackHole is not installed. Cannot start loopback capture.")
            return
        }

        format = LoopbackCapture.LoopbackFormat(sampleRate, channelCount, 16)
        isActive = true

        job = scope.launch {
            try {
                val audioFormat = AudioFormat(
                    sampleRate.toFloat(),
                    16, // bits per sample
                    channelCount,
                    true, // signed
                    true // big-endian (macOS native)
                )

                val blackHoleMixer = findBlackHoleMixer()
                if (blackHoleMixer == null) {
                    Logger.e("MacOSLoopback", "BlackHole mixer not found in Java Sound API")
                    isActive = false
                    return@launch
                }

                Logger.i("MacOSLoopback", "Found BlackHole mixer: ${blackHoleMixer.mixerInfo.name}")

                val info = javax.sound.sampled.DataLine.Info(
                    TargetDataLine::class.java,
                    audioFormat
                )

                if (!blackHoleMixer.isLineSupported(info)) {
                    Logger.e("MacOSLoopback", "BlackHole does not support format: ${sampleRate}Hz, ${channelCount}ch, 16-bit")
                    isActive = false
                    return@launch
                }

                targetDataLine = blackHoleMixer.getLine(info) as TargetDataLine
                targetDataLine?.open(audioFormat)
                targetDataLine?.start()

                Logger.i("MacOSLoopback", "Capture started: ${sampleRate}Hz, ${channelCount}ch")

                val buffer = ByteArray(4096)
                while (isActive && coroutineContext.isActive) {
                    val read = targetDataLine?.read(buffer, 0, buffer.size) ?: -1
                    if (read > 0) {
                        _capturedData.emit(buffer.copyOfRange(0, read))
                    } else if (read == -1) {
                        break
                    }
                }
            } catch (e: Exception) {
                Logger.e("MacOSLoopback", "Capture error: ${e.message}")
            } finally {
                stop()
            }
        }
    }

    override fun stop() {
        isActive = false
        try {
            targetDataLine?.stop()
            targetDataLine?.close()
        } catch (e: Exception) {
            Logger.w("MacOSLoopback", "Error closing TargetDataLine: ${e.message}")
        }
        targetDataLine = null
        job?.cancel()
        job = null
    }

    private fun findBlackHoleMixer(): javax.sound.sampled.Mixer? {
        val blackHolePattern = Regex("BlackHole\\s*\\d*ch", RegexOption.IGNORE_CASE)

        for (mixerInfo in AudioSystem.getMixerInfo()) {
            if (blackHolePattern.containsMatchIn(mixerInfo.name)) {
                return AudioSystem.getMixer(mixerInfo)
            }
        }
        return null
    }
}
