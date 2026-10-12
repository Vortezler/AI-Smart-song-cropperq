package com.example.smartmusiccropper.ml

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CropPointDetectorTest {

    private val labels = File("src/main/assets/yamnet_label_list.csv").inputStream().use(YamnetClassifier::parseLabels)
    private val detector = CropPointDetector(labels)

    private fun scores(vararg pairs: Pair<String, Float>) = FloatArray(YamnetClassifier.NUM_CLASSES).also { s ->
        pairs.forEach { (name, v) -> s[labels.indexOf(name)] = v }
    }

    private fun windows(pattern: String, hopMs: Long = 480, lenMs: Long = 975) =
        pattern.mapIndexed { i, c -> ClassifiedWindow(i * hopMs, i * hopMs + lenMs, c == 'M') }

    @Test
    fun labelMapParsesAllClassesIncludingQuotedNames() {
        assertEquals(521, labels.size)
        assertEquals("Music", labels[132])
        assertEquals("Silence", labels[494])
        assertEquals("Child speech, kid speaking", labels[1])
    }

    @Test
    fun musicBeatsSilence() {
        assertTrue(detector.isMusic(scores("Music" to 0.9f, "Guitar" to 0.3f)))
        assertFalse(detector.isMusic(scores("Silence" to 0.8f)))
        assertFalse(detector.isMusic(scores("Music" to 0.2f, "White noise" to 0.4f)))
    }

    @Test
    fun weakMusicBelowFloorIsNotMusic() {
        assertFalse(detector.isMusic(scores("Music" to 0.05f, "Speech" to 0.9f)))
    }

    @Test
    fun vocalMusicCountsAsMusic() {
        assertTrue(detector.isMusic(scores("Singing" to 0.6f)))
    }

    @Test
    fun suggestsEndOfLastMusicWindowPlusBuffer() {
        val w = windows("MMMMSSSS")
        val s = CropPointDetector.suggest(w, trackDurationMs = 10_000)
        assertTrue(s.musicFound)
        assertEquals(3 * 480L + 975 + CropPointDetector.BUFFER_MS, s.cropMs)
    }

    @Test
    fun ignoresEarlierPausesAndUsesLastMusic() {
        val s = CropPointDetector.suggest(windows("MMSSMMSS"), trackDurationMs = 10_000)
        assertEquals(5 * 480L + 975 + CropPointDetector.BUFFER_MS, s.cropMs)
    }

    @Test
    fun clampsToTrackDuration() {
        val s = CropPointDetector.suggest(windows("SSMM"), trackDurationMs = 2_000)
        assertEquals(2_000L, s.cropMs)
    }

    @Test
    fun noMusicMeansNoTrim() {
        val s = CropPointDetector.suggest(windows("SSSS"), trackDurationMs = 5_000)
        assertFalse(s.musicFound)
        assertEquals(5_000L, s.cropMs)
    }

    @Test
    fun windowsMapOntoTrackTime() {
        val raw = listOf(
            WindowScores(0, 15_600, scores("Music" to 1f)),
            WindowScores(7_680, 23_280, scores("Silence" to 1f)),
        )
        val mapped = detector.classifyWindows(raw, tailStartMs = 60_000, sampleRate = 16_000)
        assertEquals(ClassifiedWindow(60_000, 60_975, true), mapped[0])
        assertEquals(ClassifiedWindow(60_480, 61_455, false), mapped[1])
    }

    @Test
    fun windowStartsCoverTheEnd() {
        assertEquals(listOf(0), YamnetClassifier.windowStarts(1_000))
        val starts = YamnetClassifier.windowStarts(40_000)
        assertEquals(listOf(0, 7_680, 15_360, 23_040, 24_400), starts)
        assertEquals(40_000, starts.last() + YamnetClassifier.WINDOW_SAMPLES)
    }
}
