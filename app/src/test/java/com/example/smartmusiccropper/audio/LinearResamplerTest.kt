package com.example.smartmusiccropper.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt

class LinearResamplerTest {

    private fun sine(freq: Double, rate: Int, seconds: Double, amp: Float = 0.5f) =
        FloatArray((rate * seconds).toInt()) { i -> (amp * sin(2 * PI * freq * i / rate)).toFloat() }

    private fun rms(x: FloatArray, from: Int = 0) =
        sqrt(x.drop(from).sumOf { it.toDouble() * it } / (x.size - from))

    @Test
    fun outputLengthMatchesRateRatio() {
        val out = LinearResampler(44_100, 16_000, 1).process(sine(440.0, 44_100, 2.0))
        assertEquals(32_000.0, out.size.toDouble(), 2.0)
    }

    @Test
    fun chunkedProcessingMatchesOneShot() {
        val input = sine(1000.0, 44_100, 1.0)
        val oneShot = LinearResampler(44_100, 16_000, 1).process(input.copyOf())

        val chunked = LinearResampler(44_100, 16_000, 1)
        val parts = ArrayList<Float>()
        var i = 0
        val sizes = intArrayOf(1, 7, 1152, 333, 4096)
        var k = 0
        while (i < input.size) {
            val n = minOf(sizes[k++ % sizes.size], input.size - i)
            parts += chunked.process(input.copyOfRange(i, i + n)).toList()
            i += n
        }
        assertArrayEquals(oneShot, parts.toFloatArray(), 1e-4f)
    }

    @Test
    fun preservesInBandTone() {
        val out = LinearResampler(44_100, 16_000, 1).process(sine(1000.0, 44_100, 1.0))
        // Count zero crossings after the filter settles: a 1 kHz tone crosses ~2000 times a second.
        var crossings = 0
        for (j in 1 until out.size) if ((out[j - 1] < 0) != (out[j] < 0)) crossings++
        assertEquals(2000.0, crossings.toDouble(), 10.0)
        assertEquals(0.5 / sqrt(2.0), rms(out, 1000), 0.03)
    }

    @Test
    fun attenuatesContentAboveNewNyquist() {
        // 12 kHz would alias to 4 kHz at 16 kHz without the anti-aliasing filter.
        val out = LinearResampler(44_100, 16_000, 1).process(sine(12_000.0, 44_100, 1.0))
        assertTrue("aliasing too strong: ${rms(out, 1000)}", rms(out, 1000) < 0.02)
    }

    @Test
    fun stereoChannelsStayIndependent() {
        val frames = 48_000
        val input = FloatArray(frames * 2) { i -> if (i % 2 == 0) 0.25f else -0.5f }
        val out = LinearResampler(96_000, 48_000, 2).process(input)
        for (f in 2000 until out.size / 2) {
            assertEquals(0.25f, out[f * 2], 1e-3f)
            assertEquals(-0.5f, out[f * 2 + 1], 1e-3f)
        }
    }

    @Test
    fun upsamplingInterpolatesLinearly() {
        val out = LinearResampler(8_000, 16_000, 1).process(floatArrayOf(0f, 1f, 0f, -1f))
        assertArrayEquals(floatArrayOf(0f, 0.5f, 1f, 0.5f, 0f, -0.5f, -1f), out, 1e-6f)
    }

    @Test
    fun downmixAveragesChannels() {
        val mono = PcmResampler.downmixToMono(shortArrayOf(16384, -16384, 32767, 32767), 2, 2)
        assertEquals(0f, mono[0], 1e-6f)
        assertTrue(abs(mono[1] - 1f) < 1e-3f)
    }
}
