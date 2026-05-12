package com.lanrhyme.micyou.audio

import com.lanrhyme.micyou.Logger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

class StubLoopbackCapture : LoopbackCapture {
    override fun start(sampleRate: Int, channelCount: Int) {
        Logger.w("StubLoopback", "Loopback capture is not implemented for this platform")
    }

    override fun stop() {}

    override val capturedData: Flow<ByteArray> = emptyFlow()
    override val isActive: Boolean = false
    override val format: LoopbackCapture.LoopbackFormat = LoopbackCapture.LoopbackFormat(44100, 2, 16)
}
