package com.example.smartmusiccropper

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.smartmusiccropper.audio.AudioDecoder
import com.example.smartmusiccropper.audio.AudioEncoder
import com.example.smartmusiccropper.audio.PcmResampler
import com.example.smartmusiccropper.ml.CropPointDetector
import com.example.smartmusiccropper.ml.YamnetClassifier
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Runs the real on-device pipeline (MediaCodec decode → YAMNet → crop suggestion → AAC export) on
 * fixtures of 30 s of synthetic music followed by 5 s of silence.
 */
@RunWith(AndroidJUnit4::class)
class PipelineInstrumentedTest {

    private val appContext: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val testContext: Context = InstrumentationRegistry.getInstrumentation().context

    private fun fixture(name: String): Uri {
        val file = File(appContext.cacheDir, name)
        testContext.assets.open(name).use { input -> file.outputStream().use { input.copyTo(it) } }
        return Uri.fromFile(file)
    }

    private fun containerDurationMs(file: File): Pair<String, Long> {
        val ex = MediaExtractor()
        try {
            ex.setDataSource(file.absolutePath)
            val f = ex.getTrackFormat(0)
            return f.getString(MediaFormat.KEY_MIME)!! to f.getLong(MediaFormat.KEY_DURATION) / 1000
        } finally {
            ex.release()
        }
    }

    private fun runPipeline(name: String, expectedMime: String) = runBlocking {
        val uri = fixture(name)

        val info = AudioDecoder.probe(appContext, uri)
        assertEquals(expectedMime, info.mime)
        assertEquals(35_000.0, info.durationUs / 1000.0, 100.0)

        // Full decode duration matches the container.
        var frames = 0L
        var rate = 0
        AudioDecoder.decode(appContext, uri, 0L, onFormat = { rate = it.sampleRate }) { _, n, _ ->
            frames += n
            true
        }
        assertEquals(35_000.0, frames * 1000.0 / rate, 100.0)

        val tail = AudioDecoder.decodeTail(appContext, uri, info, 60_000_000L)
        // MP3 decoders report the first output after the encoder delay (~14 ms), not at 0.
        assertEquals(0.0, tail.startMs.toDouble(), 50.0)
        assertEquals(35_000.0, tail.trackDurationMs.toDouble(), 100.0)

        val suggestion = YamnetClassifier(appContext).use { model ->
            val windows = CropPointDetector(model.labels)
                .classifyWindows(model.classify(tail.samples), tail.startMs, PcmResampler.MODEL_SAMPLE_RATE)
            CropPointDetector.suggest(windows, tail.trackDurationMs)
        }
        assertTrue(suggestion.musicFound)
        assertTrue("suggested ${suggestion.cropMs} ms", suggestion.cropMs in 29_500L..31_500L)

        val out = File(appContext.cacheDir, "$name.m4a")
        val written = AudioEncoder.encodeTrimmed(appContext, uri, suggestion.cropMs, out) {}
        assertEquals(suggestion.cropMs.toDouble(), written.toDouble(), 30.0)
        val (outMime, outMs) = containerDurationMs(out)
        assertEquals(MediaFormat.MIMETYPE_AUDIO_AAC, outMime)
        assertEquals(suggestion.cropMs.toDouble(), outMs.toDouble(), 100.0)
    }

    @Test
    fun mp3PipelineCropsAtTheEndOfTheMusic() = runPipeline("music30_silence5.mp3", MediaFormat.MIMETYPE_AUDIO_MPEG)

    @Test
    fun wavPipelineCropsAtTheEndOfTheMusic() = runPipeline("music30_silence5.wav", MediaFormat.MIMETYPE_AUDIO_RAW)

    @Test
    fun corruptFileIsRejectedWithoutCrashing() {
        val uri = fixture("corrupt.mp3")
        try {
            AudioDecoder.probe(appContext, uri)
            fail("Expected corrupt file to be rejected")
        } catch (e: Exception) {
            // Any exception is fine: the ViewModel maps it to a user-facing message.
        }
    }
}
