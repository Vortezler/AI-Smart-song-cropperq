package com.example.smartmusiccropper

import android.graphics.Bitmap
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.smartmusiccropper.data.OutputMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Drives the real activity + ViewModel from a picked file to an exported .m4a, in both output
 * modes, without going through the system picker UI. Screenshots land in the app's external files
 * dir for manual inspection.
 */
@RunWith(AndroidJUnit4::class)
class EditorFlowInstrumentedTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val appContext = instrumentation.targetContext

    private fun copyFixture(name: String, target: File): File {
        instrumentation.context.assets.open(name).use { input -> target.outputStream().use { input.copyTo(it) } }
        return target
    }

    private fun m4aDurationMs(file: File): Long {
        val ex = MediaExtractor()
        try {
            ex.setDataSource(file.absolutePath)
            val f = ex.getTrackFormat(0)
            assertEquals(MediaFormat.MIMETYPE_AUDIO_AAC, f.getString(MediaFormat.KEY_MIME))
            return f.getLong(MediaFormat.KEY_DURATION) / 1000
        } finally {
            ex.release()
        }
    }

    private fun setMode(vm: MainViewModel, mode: OutputMode) {
        vm.setOutputMode(mode)
        val deadline = SystemClock.uptimeMillis() + 30_000
        while (vm.outputMode.value != mode && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(100)
        assertEquals(mode, vm.outputMode.value)
    }

    private fun screenshot(name: String) {
        instrumentation.waitForIdleSync()
        SystemClock.sleep(3_000) // let Compose draw the new state on a slow emulator
        val bitmap = instrumentation.uiAutomation.takeScreenshot() ?: return
        val dir = appContext.getExternalFilesDir(null) ?: return
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun ActivityScenario<MainActivity>.vm(): MainViewModel {
        var vm: MainViewModel? = null
        onActivity { vm = ViewModelProvider(it)[MainViewModel::class.java] }
        return vm!!
    }

    private inline fun <reified T : CropUiState> waitFor(vm: MainViewModel, timeoutMs: Long = 600_000): T {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            val s = vm.state.value
            if (s is T) return s
            if (s is CropUiState.Error) throw AssertionError("Error state: ${s.message}")
            SystemClock.sleep(500)
        }
        throw AssertionError("Timed out waiting for ${T::class.simpleName}; state=${vm.state.value}")
    }

    @Test
    fun pickAdjustAndExportInBothModes() {
        val dir = File(appContext.cacheDir, "flow").apply { mkdirs() }
        val source = copyFixture("music30_silence5.wav", File(dir, "song.wav"))

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val vm = scenario.vm()

            // Save as new file
            setMode(vm, OutputMode.SAVE_AS_NEW)
            instrumentation.runOnMainSync { vm.onFilePicked(Uri.fromFile(source)) }
            val ready = waitFor<CropUiState.Ready>(vm)
            assertTrue(ready.analysis.musicFound)
            assertTrue("suggested ${ready.cropMs}", ready.cropMs in 29_500L..31_500L)
            screenshot("01_editor_suggested")

            // Simulate the user dragging the line 1 s earlier, then nudging +0.1 s.
            instrumentation.runOnMainSync {
                vm.onCropChanged(ready.cropMs - 1_000)
                vm.onCropChanged((vm.state.value as CropUiState.Ready).cropMs + 100)
            }
            val adjusted = (vm.state.value as CropUiState.Ready).cropMs
            assertEquals(ready.cropMs - 900, adjusted)
            screenshot("02_editor_adjusted")

            // Real touch: swipe across the waveform (phone portrait layout: the waveform sits at
            // ~45% of the screen height and spans ~8%..92% of the width) and check the line follows.
            val dm = appContext.resources.displayMetrics
            val y = (dm.heightPixels * 0.45f).toInt()
            val fromX = (dm.widthPixels * 0.80f).toInt()
            val toX = (dm.widthPixels * 0.46f).toInt()
            // Read the command's output to block until the swipe has been fully injected.
            ParcelFileDescriptor.AutoCloseInputStream(
                instrumentation.uiAutomation.executeShellCommand("input swipe $fromX $y $toX $y 800"),
            ).use { it.readBytes() }
            val deadline = SystemClock.uptimeMillis() + 60_000
            fun draggedMs() = (vm.state.value as CropUiState.Ready).cropMs
            while (draggedMs() !in 12_000L..20_000L && SystemClock.uptimeMillis() < deadline) {
                SystemClock.sleep(250)
            }
            val dragged = draggedMs()
            assertTrue("drag didn't move the crop line to the middle: $dragged", dragged in 12_000L..20_000L)
            screenshot("02b_editor_dragged")
            instrumentation.runOnMainSync { vm.onCropChanged(adjusted) }

            val newFile = File(dir, "song_cropped.m4a")
            instrumentation.runOnMainSync { vm.onSaveLocationPicked(Uri.fromFile(newFile)) }
            waitFor<CropUiState.Done>(vm)
            screenshot("03_done_new_file")
            assertEquals(adjusted.toDouble(), m4aDurationMs(newFile).toDouble(), 100.0)

            // Overwrite original
            instrumentation.runOnMainSync { vm.reset() }
            setMode(vm, OutputMode.OVERWRITE_ORIGINAL)
            instrumentation.runOnMainSync { vm.onFilePicked(Uri.fromFile(source)) }
            val ready2 = waitFor<CropUiState.Ready>(vm)
            val before = source.length()
            instrumentation.runOnMainSync { vm.onExportClicked() }
            val done = waitFor<CropUiState.Done>(vm)
            screenshot("04_done_overwrite")
            assertTrue("original not replaced", source.length() < before)
            assertEquals(ready2.cropMs.toDouble(), m4aDurationMs(source).toDouble(), 100.0)
            assertEquals(ready2.cropMs.toDouble(), done.newDurationMs.toDouble(), 100.0)
        }
    }
}
