package com.lanrhyme.micyou.audio

class DereverbEffect : AudioEffect {
    var enableDereverb: Boolean = false
    var dereverbLevel: Float = 0.5f

    private var dereverbBufferLeft: IntArray? = null
    private var dereverbBufferRight: IntArray? = null
    private var dereverbIndex: Int = 0
    private var lastChannelCount: Int = -1

    override fun process(input: ShortArray, channelCount: Int): ShortArray {
        if (!enableDereverb || dereverbLevel <= 0f || channelCount <= 0) return input
        if (channelCount > 2) return input // 仅支持单声道/立体声

        if (lastChannelCount != channelCount) {
            lastChannelCount = channelCount
            reset()
        }
    val delay = 480
        if (channelCount == 1) {
            val buf = dereverbBufferLeft ?: IntArray(delay).also { dereverbBufferLeft = it }
            for (i in input.indices) {
                val delayed = buf[dereverbIndex]
                val current = input[i].toInt()
                buf[dereverbIndex] = current
                val out = (current - (delayed * dereverbLevel).toInt()).coerceIn(-32768, 32767)
                input[i] = out.toShort()
                dereverbIndex++
                if (dereverbIndex >= delay) dereverbIndex = 0
            }
        } else {
            val bufL = dereverbBufferLeft ?: IntArray(delay).also { dereverbBufferLeft = it }
    val bufR = dereverbBufferRight ?: IntArray(delay).also { dereverbBufferRight = it }
    var i = 0
            while (i + 1 < input.size) {
                val delayedL = bufL[dereverbIndex]
                val delayedR = bufR[dereverbIndex]
                val currentL = input[i].toInt()
    val currentR = input[i + 1].toInt()
                bufL[dereverbIndex] = currentL
                bufR[dereverbIndex] = currentR
                val outL = (currentL - (delayedL * dereverbLevel).toInt()).coerceIn(-32768, 32767)
    val outR = (currentR - (delayedR * dereverbLevel).toInt()).coerceIn(-32768, 32767)
                input[i] = outL.toShort()
                input[i + 1] = outR.toShort()
                dereverbIndex++
                if (dereverbIndex >= delay) dereverbIndex = 0
                i += 2
            }
        }
        return input
    }

    override fun reset() {
        dereverbBufferLeft = null
        dereverbBufferRight = null
        dereverbIndex = 0
    }

    override fun release() {
        reset()
    }
}
