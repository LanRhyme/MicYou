package com.lanrhyme.micyou.audio

import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import kotlin.test.Test
import kotlin.test.assertEquals

@RunWith(Parameterized::class)
class AudioFormatResolutionTest(
    private val requested: AudioFormat,
    private val expected: AudioFormat,
    private val expectedBytesPerSample: Int
) {
    @Test
    fun resolvesCaptureEncodingAndWireFormatTogether() {
        val resolved = resolveAudioFormat(requested)

        assertEquals(expected, resolved.captureFormat)
        assertEquals(expected.value, resolved.androidEncoding)
        assertEquals(expected, resolved.wireFormat)
        assertEquals(expectedBytesPerSample, resolved.bytesPerSample)
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0} -> {1}")
        fun formats(): List<Array<Any>> = listOf(
            arrayOf<Any>(AudioFormat.PCM_8BIT, AudioFormat.PCM_8BIT, 1),
            arrayOf<Any>(AudioFormat.PCM_16BIT, AudioFormat.PCM_16BIT, 2),
            arrayOf<Any>(AudioFormat.PCM_24BIT, AudioFormat.PCM_16BIT, 2),
            arrayOf<Any>(AudioFormat.PCM_FLOAT, AudioFormat.PCM_FLOAT, 4)
        )
    }
}
