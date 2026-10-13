package com.example.smartmusiccropper.ml

/** One analyzed window, in ms relative to the start of the track. */
data class ClassifiedWindow(val startMs: Long, val endMs: Long, val isMusic: Boolean)

data class CropSuggestion(
    /** Suggested end of the cropped track, in ms from the start of the track. */
    val cropMs: Long,
    /** False when no window in the analyzed tail looked like music. */
    val musicFound: Boolean,
)

/**
 * Turns per-window YAMNet scores into a music / non-music decision and a suggested crop point.
 *
 * A window counts as music when the summed scores of the music-related classes beat the summed
 * scores of the silence / noise / room-tone classes (and clear a small floor, so windows the model
 * has no opinion on aren't treated as music).
 */
class CropPointDetector(labels: List<String>) {

    private val musicClasses: IntArray
    private val silenceClasses: IntArray

    init {
        val index = labels.withIndex().associate { (i, name) -> name to i }
        fun idx(name: String) = index[name] ?: error("Label '$name' missing from the YAMNet class map")

        // AudioSet lists every music class (instruments, genres, moods) contiguously, from "Music"
        // to "Scary music"; vocals-only music lives with the human sounds.
        val musicRange = idx("Music")..idx("Scary music")
        musicClasses = (musicRange + VOCAL_MUSIC_CLASSES.map(::idx)).distinct().toIntArray()
        silenceClasses = SILENCE_CLASSES.map(::idx).toIntArray()
    }

    fun isMusic(scores: FloatArray): Boolean {
        var music = 0f
        for (i in musicClasses) music += scores[i]
        var silence = 0f
        for (i in silenceClasses) silence += scores[i]
        return music > silence && music >= MIN_MUSIC_SCORE
    }

    /** Maps raw window scores onto track time, given where the analyzed tail starts. */
    fun classifyWindows(windows: List<WindowScores>, tailStartMs: Long, sampleRate: Int): List<ClassifiedWindow> =
        windows.map { w ->
            ClassifiedWindow(
                startMs = tailStartMs + w.startSample * 1000L / sampleRate,
                endMs = tailStartMs + w.endSample * 1000L / sampleRate,
                isMusic = isMusic(w.scores),
            )
        }

    companion object {
        /** Added after the last music window so the final note's decay isn't clipped. */
        const val BUFFER_MS = 300L
        const val MIN_MUSIC_SCORE = 0.15f

        val VOCAL_MUSIC_CLASSES = listOf(
            "Singing", "Choir", "Yodeling", "Chant", "Mantra", "Child singing", "Synthetic singing",
            "Rapping", "Humming",
        )

        val SILENCE_CLASSES = listOf(
            "Silence", "Hiss", "Hum", "Mains hum", "Buzz", "Noise", "Environmental noise", "Static",
            "White noise", "Pink noise", "Inside, small room", "Inside, large room or hall",
            "Field recording",
        )

        /**
         * Walks back from the end of the analyzed tail to the last window classified as music and
         * suggests that window's end plus [BUFFER_MS], clamped to the track. If nothing in the tail
         * is music, suggests the full length (trim nothing) and reports [CropSuggestion.musicFound]
         * = false so the UI can ask the user to place the cut.
         */
        fun suggest(windows: List<ClassifiedWindow>, trackDurationMs: Long): CropSuggestion {
            val lastMusic = windows.lastOrNull { it.isMusic }
                ?: return CropSuggestion(trackDurationMs, musicFound = false)
            val crop = (lastMusic.endMs + BUFFER_MS).coerceIn(0L, trackDurationMs)
            return CropSuggestion(crop, musicFound = true)
        }
    }
}
