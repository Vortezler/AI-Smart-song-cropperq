package com.example.smartmusiccropper.ml

import android.content.Context
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.support.common.FileUtil
import java.io.Closeable
import java.io.InputStream

/** Scores for one model window, positioned in samples of the 16 kHz input. */
class WindowScores(val startSample: Int, val endSample: Int, val scores: FloatArray)

/**
 * Runs the bundled YAMNet model (AudioSet, 521 classes) over 16 kHz mono PCM in overlapping windows.
 * Entirely on-device; the model and label map ship in assets.
 */
class YamnetClassifier(context: Context) : Closeable {

    private val interpreter = Interpreter(
        FileUtil.loadMappedFile(context, MODEL_FILE),
        Interpreter.Options().setNumThreads(NUM_THREADS),
    )

    val labels: List<String> = context.assets.open(LABEL_FILE).use(::parseLabels)

    init {
        check(labels.size == NUM_CLASSES) { "Expected $NUM_CLASSES labels, found ${labels.size}" }
    }

    /**
     * Classifies [pcm] (16 kHz mono) in [WINDOW_SAMPLES] windows every [HOP_SAMPLES]. A final window
     * is aligned to the very end so the last fraction of a second is always covered; input shorter
     * than one window is zero-padded.
     */
    suspend fun classify(pcm: FloatArray, onProgress: (Float) -> Unit = {}): List<WindowScores> {
        val starts = windowStarts(pcm.size)
        val input = FloatArray(WINDOW_SAMPLES)
        val results = ArrayList<WindowScores>(starts.size)
        starts.forEachIndexed { i, start ->
            currentCoroutineContext().ensureActive()
            input.fill(0f)
            val n = minOf(WINDOW_SAMPLES, pcm.size - start)
            System.arraycopy(pcm, start, input, 0, n)
            val output = Array(1) { FloatArray(NUM_CLASSES) }
            interpreter.run(input, output)
            results += WindowScores(start, start + n, output[0])
            onProgress((i + 1).toFloat() / starts.size)
        }
        return results
    }

    override fun close() = interpreter.close()

    companion object {
        const val MODEL_FILE = "yamnet.tflite"
        const val LABEL_FILE = "yamnet_label_list.csv"
        const val SAMPLE_RATE = 16_000
        /** YAMNet's fixed input: 0.975 s at 16 kHz. */
        const val WINDOW_SAMPLES = 15_600
        /** 0.48 s hop for finer time resolution near the tail. */
        const val HOP_SAMPLES = 7_680
        const val NUM_CLASSES = 521
        private const val NUM_THREADS = 4

        fun windowStarts(totalSamples: Int): List<Int> {
            if (totalSamples <= WINDOW_SAMPLES) return listOf(0)
            val starts = ArrayList<Int>()
            var s = 0
            while (s + WINDOW_SAMPLES <= totalSamples) {
                starts += s
                s += HOP_SAMPLES
            }
            val lastStart = totalSamples - WINDOW_SAMPLES
            if (starts.last() != lastStart) starts += lastStart
            return starts
        }

        /** Parses the `index,mid,display_name` class map; display names may be quoted and contain commas. */
        fun parseLabels(stream: InputStream): List<String> =
            stream.bufferedReader().readLines()
                .drop(1)
                .filter { it.isNotBlank() }
                .map { line ->
                    val afterIndex = line.indexOf(',')
                    val afterMid = line.indexOf(',', afterIndex + 1)
                    line.substring(afterMid + 1).trim().removeSurrounding("\"")
                }
    }
}
