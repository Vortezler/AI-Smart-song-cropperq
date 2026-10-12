package com.example.smartmusiccropper

import android.app.Application
import android.media.MediaFormat
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.example.smartmusiccropper.audio.AudioDecoder
import com.example.smartmusiccropper.audio.AudioEncoder
import com.example.smartmusiccropper.audio.PcmResampler
import com.example.smartmusiccropper.audio.TrackTooLongException
import com.example.smartmusiccropper.audio.UnsupportedAudioException
import com.example.smartmusiccropper.data.OutputMode
import com.example.smartmusiccropper.data.SettingsRepository
import com.example.smartmusiccropper.files.FilePicker
import com.example.smartmusiccropper.files.FileWriter
import com.example.smartmusiccropper.ml.ClassifiedWindow
import com.example.smartmusiccropper.ml.CropPointDetector
import com.example.smartmusiccropper.ml.YamnetClassifier
import com.example.smartmusiccropper.ui.Waveform
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/** Everything the crop screen needs to know about an analyzed file. */
class AnalysisResult(
    val uri: Uri,
    val fileName: String,
    /** "MP3" or "WAV". */
    val formatLabel: String,
    val durationMs: Long,
    /** Start of the analyzed (and displayed) tail. */
    val tailStartMs: Long,
    /** Peak envelope of the tail, 0..1 per bucket, for the waveform. */
    val envelope: FloatArray,
    val windows: List<ClassifiedWindow>,
    val suggestedCropMs: Long,
    val musicFound: Boolean,
) {
    /** The crop line can move anywhere in the displayed tail, but never below [MIN_OUTPUT_MS]. */
    val minCropMs: Long get() = maxOf(tailStartMs, minOf(MIN_OUTPUT_MS, durationMs))

    fun clampCrop(ms: Long): Long = ms.coerceIn(minCropMs, durationMs)

    companion object {
        const val MIN_OUTPUT_MS = 1_000L
    }
}

sealed interface CropUiState {
    data object Idle : CropUiState

    /** [progress] is null while decoding, then 0..1 while the model runs. */
    data class Analyzing(val fileName: String, val progress: Float?) : CropUiState

    data class Ready(val analysis: AnalysisResult, val cropMs: Long) : CropUiState

    data class Exporting(val analysis: AnalysisResult, val cropMs: Long, val progress: Float) : CropUiState

    data class Done(val outputName: String, val location: String, val newDurationMs: Long) : CropUiState

    data class Error(val message: String) : CropUiState
}

sealed interface UiEvent {
    /** Ask the user where to save a new file (ACTION_CREATE_DOCUMENT). */
    data class CreateDocument(val request: FilePicker.CreateRequest) : UiEvent

    data class Toast(val message: String) : UiEvent
}

/**
 * Orchestrates pick → decode tail → YAMNet → suggested crop → export. Lives in the ViewModel so a
 * multi-second analysis survives rotation; the picked URI and crop point are also kept in
 * [SavedStateHandle] so the analysis re-runs after process death.
 */
class MainViewModel(
    application: Application,
    private val savedState: SavedStateHandle,
) : AndroidViewModel(application) {

    private val context get() = getApplication<Application>()
    private val settings = SettingsRepository(application)

    val outputMode: StateFlow<OutputMode> =
        settings.outputMode.stateIn(viewModelScope, SharingStarted.Eagerly, OutputMode.SAVE_AS_NEW)

    private val _state = MutableStateFlow<CropUiState>(CropUiState.Idle)
    val state: StateFlow<CropUiState> = _state.asStateFlow()

    private val _events = Channel<UiEvent>(Channel.BUFFERED)
    val events: Flow<UiEvent> = _events.receiveAsFlow()

    private val modelLock = Mutex()
    private var classifier: YamnetClassifier? = null
    private var detector: CropPointDetector? = null

    private var job: Job? = null

    /** An encoded export kept so a fallback (overwrite → new file) doesn't re-encode. */
    private class EncodedExport(val file: File, val uri: Uri, val cropMs: Long, val writtenMs: Long)
    private var encoded: EncodedExport? = null

    /** A save location chosen while the analysis was still being restored after process death. */
    private var deferredSaveUri: Uri? = null

    init {
        context.cacheDir.listFiles { f -> f.name.startsWith(EXPORT_PREFIX) }?.forEach { it.delete() }
        savedState.get<String>(KEY_URI)?.let { analyze(Uri.parse(it), savedState.get<Long>(KEY_CROP)) }
    }

    fun setOutputMode(mode: OutputMode) {
        viewModelScope.launch { settings.setOutputMode(mode) }
    }

    /** Result of the open-document picker. Null means the user cancelled: leave everything as is. */
    fun onFilePicked(uri: Uri?) {
        if (uri == null) return
        FilePicker.takePersistablePermission(context, uri)
        savedState[KEY_URI] = uri.toString()
        savedState.remove<Long>(KEY_CROP)
        analyze(uri, restoredCropMs = null)
    }

    fun onCropChanged(ms: Long) {
        val ready = _state.value as? CropUiState.Ready ?: return
        val clamped = ready.analysis.clampCrop(ms)
        savedState[KEY_CROP] = clamped
        _state.value = ready.copy(cropMs = clamped)
    }

    fun resetCrop() {
        val ready = _state.value as? CropUiState.Ready ?: return
        onCropChanged(ready.analysis.suggestedCropMs)
    }

    fun onExportClicked() {
        val ready = _state.value as? CropUiState.Ready ?: return
        when (outputMode.value) {
            OutputMode.SAVE_AS_NEW -> requestNewDocument(ready.analysis)
            OutputMode.OVERWRITE_ORIGINAL -> export(ready, target = null)
        }
    }

    /** Result of the create-document picker. Null means cancelled: stay on the crop screen. */
    fun onSaveLocationPicked(uri: Uri?) {
        if (uri == null) return
        val ready = _state.value as? CropUiState.Ready
        if (ready == null) {
            deferredSaveUri = uri
            return
        }
        export(ready, target = uri)
    }

    /** "Done" / "Pick another file": back to the start. */
    fun reset() {
        job?.cancel()
        deleteEncoded()
        savedState.remove<String>(KEY_URI)
        savedState.remove<Long>(KEY_CROP)
        _state.value = CropUiState.Idle
    }

    private fun requestNewDocument(analysis: AnalysisResult) {
        val request = FilePicker.CreateRequest(FilePicker.croppedFileName(analysis.fileName), analysis.uri)
        _events.trySend(UiEvent.CreateDocument(request))
    }

    private fun analyze(uri: Uri, restoredCropMs: Long?) {
        job?.cancel()
        deleteEncoded()
        val name = FilePicker.displayName(context, uri) ?: "Selected file"
        _state.value = CropUiState.Analyzing(name, progress = null)
        job = viewModelScope.launch {
            try {
                val analysis = withContext(Dispatchers.Default) {
                    runAnalysis(uri, name) { p -> _state.value = CropUiState.Analyzing(name, p) }
                }
                val crop = restoredCropMs?.let(analysis::clampCrop) ?: analysis.suggestedCropMs
                val ready = CropUiState.Ready(analysis, crop)
                _state.value = ready
                deferredSaveUri?.let {
                    deferredSaveUri = null
                    export(ready, target = it)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failAnalysis(e)
            } catch (e: OutOfMemoryError) {
                failAnalysis(e)
            }
        }
    }

    private fun failAnalysis(e: Throwable) {
        savedState.remove<String>(KEY_URI)
        savedState.remove<Long>(KEY_CROP)
        _state.value = CropUiState.Error(readErrorMessage(e))
    }

    private suspend fun runAnalysis(uri: Uri, name: String, onProgress: (Float) -> Unit): AnalysisResult {
        val info = AudioDecoder.probe(context, uri)
        if (info.durationUs > AudioDecoder.MAX_DURATION_US) throw TrackTooLongException()

        val tail = AudioDecoder.decodeTail(context, uri, info, TAIL_US)
        onProgress(0f)

        return modelLock.withLock {
            val model = classifier ?: YamnetClassifier(context).also { classifier = it }
            val det = detector ?: CropPointDetector(model.labels).also { detector = it }
            val scores = model.classify(tail.samples, onProgress)
            val windows = det.classifyWindows(scores, tail.startMs, PcmResampler.MODEL_SAMPLE_RATE)
            val suggestion = CropPointDetector.suggest(windows, tail.trackDurationMs)
            AnalysisResult(
                uri = uri,
                fileName = name,
                formatLabel = if (info.mime == MediaFormat.MIMETYPE_AUDIO_MPEG) "MP3" else "WAV",
                durationMs = tail.trackDurationMs,
                tailStartMs = tail.startMs,
                envelope = Waveform.envelope(tail.samples, ENVELOPE_BUCKETS),
                windows = windows,
                suggestedCropMs = suggestion.cropMs,
                musicFound = suggestion.musicFound,
            )
        }
    }

    /** [target] is a document chosen by the user, or null to overwrite the original. */
    private fun export(ready: CropUiState.Ready, target: Uri?) {
        val analysis = ready.analysis
        val cropMs = ready.cropMs
        _state.value = CropUiState.Exporting(analysis, cropMs, 0f)
        job?.cancel()
        job = viewModelScope.launch {
            try {
                val export = ensureEncoded(analysis, cropMs)
                val done = withContext(Dispatchers.IO) {
                    if (target != null) {
                        FileWriter.writeToDocument(context, export.file, target)
                        val name = FilePicker.displayName(context, target) ?: FilePicker.croppedFileName(analysis.fileName)
                        CropUiState.Done(
                            outputName = name,
                            location = FilePicker.describeFolder(context, target) ?: "Saved as a new file",
                            newDurationMs = export.writtenMs,
                        )
                    } else {
                        when (val r = FileWriter.overwriteOriginal(context, export.file, analysis.uri, analysis.fileName)) {
                            is FileWriter.OverwriteResult.Overwritten -> CropUiState.Done(
                                outputName = r.name,
                                location = listOfNotNull(
                                    FilePicker.describeFolder(context, r.uri),
                                    if (r.renamed) "Original replaced (renamed to .m4a)" else "Original replaced",
                                ).joinToString(" · "),
                                newDurationMs = export.writtenMs,
                            )
                            FileWriter.OverwriteResult.NotWritable -> null
                        }
                    }
                }
                if (done != null) {
                    deleteEncoded()
                    savedState.remove<String>(KEY_URI)
                    savedState.remove<Long>(KEY_CROP)
                    _state.value = done
                } else {
                    // The provider won't let us write to the original: save a new file instead.
                    _state.value = ready
                    _events.send(UiEvent.Toast("Can't overwrite the original here, so saving a new copy instead."))
                    requestNewDocument(analysis)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = ready
                _events.send(UiEvent.Toast(writeErrorMessage(e)))
            } catch (e: OutOfMemoryError) {
                _state.value = ready
                _events.send(UiEvent.Toast("Not enough memory to export this file on this device."))
            }
        }
    }

    private suspend fun ensureEncoded(analysis: AnalysisResult, cropMs: Long): EncodedExport {
        encoded?.let { if (it.uri == analysis.uri && it.cropMs == cropMs && it.file.exists()) return it }
        deleteEncoded()
        val file = File(context.cacheDir, "$EXPORT_PREFIX${System.currentTimeMillis()}.m4a")
        var lastReported = 0f
        val writtenMs = try {
            withContext(Dispatchers.Default) {
                AudioEncoder.encodeTrimmed(context, analysis.uri, cropMs, file) { p ->
                    if (p - lastReported >= 0.01f || p >= 1f) {
                        lastReported = p
                        _state.value = CropUiState.Exporting(analysis, cropMs, p)
                    }
                }
            }
        } catch (e: Throwable) {
            file.delete()
            throw e
        }
        return EncodedExport(file, analysis.uri, cropMs, writtenMs).also { encoded = it }
    }

    private fun deleteEncoded() {
        encoded?.file?.delete()
        encoded = null
    }

    private fun readErrorMessage(e: Throwable): String = when (e) {
        is TrackTooLongException, is UnsupportedAudioException -> e.message.orEmpty()
        is SecurityException -> "No permission to read this file. Try picking it again."
        is OutOfMemoryError -> "Not enough memory to process this file on this device."
        else -> "Couldn't read this file. It may be corrupt or in an unsupported format."
    }

    private fun writeErrorMessage(e: Throwable): String = when (e) {
        is SecurityException -> "No permission to write there. Try saving somewhere else."
        is IOException -> e.message ?: "Couldn't write the cropped file."
        else -> "Export failed: couldn't re-encode this file."
    }

    override fun onCleared() {
        deleteEncoded()
        // viewModelScope is already cancelled, but a window may still be mid-inference; close the
        // interpreter only once the model lock is free.
        val model = classifier ?: return
        classifier = null
        CoroutineScope(Dispatchers.Default).launch { modelLock.withLock { model.close() } }
    }

    private companion object {
        const val KEY_URI = "source_uri"
        const val KEY_CROP = "crop_ms"
        const val EXPORT_PREFIX = "export_"
        const val TAIL_US = 60_000_000L
        const val ENVELOPE_BUCKETS = 600
    }
}
