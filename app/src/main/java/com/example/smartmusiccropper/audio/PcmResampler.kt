package com.example.smartmusiccropper.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sin

/** Helpers for turning decoder output into what the model (or the encoder) wants. */
object PcmResampler {

    /** YAMNet expects 16 kHz mono. */
    const val MODEL_SAMPLE_RATE = 16_000

    /** Interleaved 16-bit PCM → mono float in [-1, 1] (simple channel average). */
    fun downmixToMono(pcm: ShortArray, frames: Int, channels: Int): FloatArray {
        val out = FloatArray(frames)
        val scale = 1f / (32768f * channels)
        var i = 0
        for (f in 0 until frames) {
            var sum = 0
            for (c in 0 until channels) sum += pcm[i++]
            out[f] = sum * scale
        }
        return out
    }

    /** Interleaved 16-bit PCM → interleaved float in [-1, 1], keeping only the first [outChannels] channels. */
    fun toFloat(pcm: ShortArray, frames: Int, inChannels: Int, outChannels: Int): FloatArray {
        val out = FloatArray(frames * outChannels)
        var o = 0
        for (f in 0 until frames) {
            val base = f * inChannels
            for (c in 0 until outChannels) out[o++] = pcm[base + c] / 32768f
        }
        return out
    }

    /** Interleaved float → interleaved 16-bit PCM, clamped. */
    fun toShort(samples: FloatArray, count: Int = samples.size): ShortArray {
        val out = ShortArray(count)
        for (i in 0 until count) {
            out[i] = (samples[i].coerceIn(-1f, 1f) * 32767f).roundToInt().toShort()
        }
        return out
    }
}

/**
 * Streaming linear-interpolation resampler for interleaved float PCM.
 *
 * Feed consecutive chunks to [process]; state carries across chunk boundaries so the output is the
 * same as resampling the whole signal at once. When downsampling, a 4th-order Butterworth low-pass
 * (two cascaded biquads) runs first so content above the new Nyquist frequency doesn't alias down.
 */
class LinearResampler(
    private val inRate: Int,
    private val outRate: Int,
    private val channels: Int,
) {
    init {
        require(inRate > 0 && outRate > 0) { "Sample rates must be positive" }
        require(channels > 0) { "Channel count must be positive" }
    }

    /** Input frames advanced per output frame. */
    private val step = inRate.toDouble() / outRate

    /**
     * Position of the next output frame, in input frames, relative to frame 0 of the chunk being
     * processed. -1 refers to [prev], the last frame of the previous chunk.
     */
    private var pos = 0.0
    private val prev = FloatArray(channels)

    private val filters: Array<Array<Biquad>>? =
        if (outRate < inRate) {
            val cutoff = 0.45 * outRate
            Array(channels) {
                arrayOf(
                    Biquad.lowPass(inRate.toDouble(), cutoff, 0.5411961),
                    Biquad.lowPass(inRate.toDouble(), cutoff, 1.3065630),
                )
            }
        } else {
            null
        }

    /** Resamples [frames] interleaved frames from [input]. May modify [input] in place (filtering). */
    fun process(input: FloatArray, frames: Int = input.size / channels): FloatArray {
        if (frames <= 0) return FloatArray(0)
        if (inRate == outRate) return input.copyOf(frames * channels)

        filters?.let { chains ->
            for (c in 0 until channels) {
                val chain = chains[c]
                var i = c
                for (f in 0 until frames) {
                    var x = input[i].toDouble()
                    for (bq in chain) x = bq.process(x)
                    input[i] = x.toFloat()
                    i += channels
                }
            }
        }

        val maxOut = ((frames - pos) / step).toInt() + 2
        val out = FloatArray(maxOut * channels)
        var written = 0
        val last = frames - 1
        while (pos <= last) {
            val idx = floor(pos).toInt()
            val frac = (pos - idx).toFloat()
            for (c in 0 until channels) {
                val a = if (idx < 0) prev[c] else input[idx * channels + c]
                val b = input[(idx + 1).coerceAtMost(last) * channels + c]
                out[written * channels + c] = a + (b - a) * frac
            }
            written++
            pos += step
        }
        pos -= frames
        for (c in 0 until channels) prev[c] = input[last * channels + c]
        return if (written == maxOut) out else out.copyOf(written * channels)
    }

    /** RBJ-cookbook biquad, transposed direct form II. */
    private class Biquad(
        private val b0: Double,
        private val b1: Double,
        private val b2: Double,
        private val a1: Double,
        private val a2: Double,
    ) {
        private var z1 = 0.0
        private var z2 = 0.0

        fun process(x: Double): Double {
            val y = b0 * x + z1
            z1 = b1 * x - a1 * y + z2
            z2 = b2 * x - a2 * y
            return y
        }

        companion object {
            fun lowPass(sampleRate: Double, cutoff: Double, q: Double): Biquad {
                val w0 = 2 * PI * cutoff / sampleRate
                val cosW = cos(w0)
                val alpha = sin(w0) / (2 * q)
                val a0 = 1 + alpha
                return Biquad(
                    b0 = (1 - cosW) / 2 / a0,
                    b1 = (1 - cosW) / a0,
                    b2 = (1 - cosW) / 2 / a0,
                    a1 = -2 * cosW / a0,
                    a2 = (1 - alpha) / a0,
                )
            }
        }
    }
}
