package com.lanrhyme.micyou.audio

import kotlin.math.*

/**
 * 均衡器效果器
 * 使用 10 段 Biquad 峰值滤波器实现
 */
class EqualizerEffect : AudioEffect {
    var enabled: Boolean = false
    var preAmpDb: Float = 0f
        set(value) {
            if (field != value) {
                field = value
                updateFilters()
            }
        }
    
    private var preAmpGain: Float = 1f
    private val bands = 10
    private val frequencies = floatArrayOf(31.25f, 62.5f, 125f, 250f, 500f, 1000f, 2000f, 4000f, 8000f, 16000f)
    
    private var filters = Array(2) { Array(bands) { BiquadFilter() } } // [channel][band]
    private var gains = FloatArray(bands) { 0f }
    private var sampleRate = 48000.0
    
    fun setGains(newGains: List<Float>) {
        if (newGains.size != bands) return
        var changed = false
        for (i in 0 until bands) {
            if (gains[i] != newGains[i]) {
                gains[i] = newGains[i]
                changed = true
            }
        }
        if (changed) {
            updateFilters()
        }
    }
    
    fun updateSampleRate(newSampleRate: Double) {
        if (sampleRate != newSampleRate) {
            sampleRate = newSampleRate
            updateFilters()
        }
    }

    private fun updateFilters() {
        preAmpGain = 10.0f.pow(preAmpDb / 20.0f)
        for (ch in filters.indices) {
            for (i in 0 until bands) {
                filters[ch][i].setPeakingEQ(sampleRate, frequencies[i].toDouble(), 1.0, gains[i].toDouble())
            }
        }
    }

    private fun ensureChannelCapacity(channelCount: Int) {
        if (filters.size >= channelCount) return
        
        val oldFilters = filters
        filters = Array(channelCount) { ch ->
            if (ch < oldFilters.size) {
                oldFilters[ch]
            } else {
                Array(bands) { b ->
                    BiquadFilter().apply {
                        setPeakingEQ(sampleRate, frequencies[b].toDouble(), 1.0, gains[b].toDouble())
                    }
                }
            }
        }
    }

    override fun process(input: ShortArray, channelCount: Int): ShortArray {
        if (!enabled) return input
        
        ensureChannelCapacity(channelCount)
        
        val output = ShortArray(input.size)
        for (i in input.indices) {
            val channel = i % channelCount
            var sample = input[i].toDouble() * preAmpGain
            
            val channelFilters = filters[channel]
            for (b in 0 until bands) {
                sample = channelFilters[b].process(sample)
            }
            
            output[i] = sample.toInt().coerceIn(-32768, 32767).toShort()
        }
        return output
    }

    override fun reset() {
        for (ch in filters.indices) {
            for (b in 0 until bands) {
                filters[ch][b].reset()
            }
        }
    }

    override fun release() {}

    private class BiquadFilter {
        private var a0 = 0.0
        private var a1 = 0.0
        private var a2 = 0.0
        private var b1 = 0.0
        private var b2 = 0.0
        
        private var x1 = 0.0
        private var x2 = 0.0
        private var y1 = 0.0
        private var y2 = 0.0
        
        fun setPeakingEQ(sampleRate: Double, centerFreq: Double, q: Double, dbGain: Double) {
            val w0 = 2.0 * PI * centerFreq / sampleRate
            val alpha = sin(w0) / (2.0 * q)
            val a = 10.0.pow(dbGain / 40.0)
            
            val b0_raw = 1.0 + alpha * a
            val b1_raw = -2.0 * cos(w0)
            val b2_raw = 1.0 - alpha * a
            val a0_raw = 1.0 + alpha / a
            val a1_raw = -2.0 * cos(w0)
            val a2_raw = 1.0 - alpha / a
            
            a0 = b0_raw / a0_raw
            a1 = b1_raw / a0_raw
            a2 = b2_raw / a0_raw
            b1 = a1_raw / a0_raw
            b2 = a2_raw / a0_raw
        }
        
        fun process(x: Double): Double {
            val y = a0 * x + a1 * x1 + a2 * x2 - b1 * y1 - b2 * y2
            x2 = x1
            x1 = x
            y2 = y1
            y1 = y
            return y
        }
        
        fun reset() {
            x1 = 0.0
            x2 = 0.0
            y1 = 0.0
            y2 = 0.0
        }
    }
}
