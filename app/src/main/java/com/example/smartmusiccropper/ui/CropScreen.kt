package com.example.smartmusiccropper.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.smartmusiccropper.AnalysisResult
import com.example.smartmusiccropper.CropUiState
import com.example.smartmusiccropper.data.OutputMode
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CropScreen(
    state: CropUiState,
    outputMode: OutputMode,
    onPickFile: () -> Unit,
    onCropChange: (Long) -> Unit,
    onResetCrop: () -> Unit,
    onExport: () -> Unit,
    onReset: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Smart Music Cropper") },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = "Settings")
                    }
                },
            )
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = 640.dp)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                when (state) {
                    CropUiState.Idle -> IdleContent(onPickFile)
                    is CropUiState.Analyzing -> AnalyzingContent(state)
                    is CropUiState.Ready -> EditorContent(
                        analysis = state.analysis,
                        cropMs = state.cropMs,
                        exportProgress = null,
                        outputMode = outputMode,
                        onCropChange = onCropChange,
                        onResetCrop = onResetCrop,
                        onExport = onExport,
                        onPickFile = onPickFile,
                    )
                    is CropUiState.Exporting -> EditorContent(
                        analysis = state.analysis,
                        cropMs = state.cropMs,
                        exportProgress = state.progress,
                        outputMode = outputMode,
                        onCropChange = onCropChange,
                        onResetCrop = onResetCrop,
                        onExport = onExport,
                        onPickFile = onPickFile,
                    )
                    is CropUiState.Done -> DoneContent(state, onReset)
                    is CropUiState.Error -> ErrorContent(state.message, onPickFile)
                }
            }
        }
    }
}

@Composable
private fun IdleContent(onPickFile: () -> Unit) {
    Spacer(Modifier.height(32.dp))
    Text(
        "Trim the dead air at the end of a track",
        style = MaterialTheme.typography.headlineSmall,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )
    Text(
        "Pick an MP3 or WAV (up to 20 minutes). An on-device model listens to the ending, suggests " +
            "where the music stops, and you confirm the cut. Nothing leaves your phone.",
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )
    Button(onClick = onPickFile, modifier = Modifier.fillMaxWidth().height(56.dp)) {
        Text("Pick an audio file")
    }
}

@Composable
private fun AnalyzingContent(state: CropUiState.Analyzing) {
    Spacer(Modifier.height(32.dp))
    Text(
        state.fileName,
        style = MaterialTheme.typography.titleMedium,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        if (state.progress == null) {
            CircularProgressIndicator(modifier = Modifier.size(56.dp))
        } else {
            CircularProgressIndicator(progress = { state.progress }, modifier = Modifier.size(56.dp))
        }
    }
    Text(
        if (state.progress == null) "Decoding the ending…" else "Listening for where the music stops…",
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun EditorContent(
    analysis: AnalysisResult,
    cropMs: Long,
    exportProgress: Float?,
    outputMode: OutputMode,
    onCropChange: (Long) -> Unit,
    onResetCrop: () -> Unit,
    onExport: () -> Unit,
    onPickFile: () -> Unit,
) {
    val exporting = exportProgress != null
    val trimmedMs = analysis.durationMs - cropMs

    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(analysis.fileName, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(
            "${analysis.formatLabel} · ${formatTime(analysis.durationMs)}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                if (trimmedMs > 0) "Trimming last ${formatSeconds(trimmedMs)}" else "Nothing trimmed",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                "New length ${formatTime(cropMs)} (was ${formatTime(analysis.durationMs)})",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            WaveformView(
                envelope = analysis.envelope,
                windows = analysis.windows,
                startMs = analysis.tailStartMs,
                endMs = analysis.durationMs,
                cropMs = cropMs,
                suggestedMs = analysis.suggestedCropMs,
                enabled = !exporting,
                onCropChange = onCropChange,
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(formatTime(analysis.tailStartMs), style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace)
                Text("Drag the line to adjust", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(formatTime(analysis.durationMs), style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace)
            }
            Legend()
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(onClick = { onCropChange(cropMs - NUDGE_MS) }, enabled = !exporting) { Text("−0.1s") }
                OutlinedButton(onClick = { onCropChange(cropMs + NUDGE_MS) }, enabled = !exporting) { Text("+0.1s") }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onResetCrop, enabled = !exporting && cropMs != analysis.suggestedCropMs) {
                    Text("Reset")
                }
            }
            Text(
                "Suggested cut: ${formatTime(analysis.suggestedCropMs)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    if (!analysis.musicFound) {
        Notice("The model didn't hear music in the analyzed ending, so nothing is trimmed by default. Drag the line to choose a cut.")
    }

    if (exportProgress != null) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Exporting… ${(exportProgress * 100).toInt()}%", style = MaterialTheme.typography.bodyMedium)
            LinearProgressIndicator(progress = { exportProgress }, modifier = Modifier.fillMaxWidth())
        }
    } else {
        Button(onClick = onExport, modifier = Modifier.fillMaxWidth().height(56.dp)) {
            Text(if (outputMode == OutputMode.SAVE_AS_NEW) "Export as new file" else "Export & overwrite original")
        }
    }
    Text(
        "Exported as .m4a (AAC)",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedButton(onClick = onPickFile, enabled = !exporting, modifier = Modifier.fillMaxWidth()) {
        Text("Pick another file")
    }
}

@Composable
private fun Legend() {
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
        LegendItem(MaterialTheme.colorScheme.tertiary, "Music (model)")
        LegendItem(MaterialTheme.colorScheme.outlineVariant, "Silence / other")
        LegendItem(MaterialTheme.colorScheme.error, "Cut")
    }
}

@Composable
private fun LegendItem(color: androidx.compose.ui.graphics.Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        androidx.compose.foundation.Canvas(Modifier.size(10.dp)) { drawCircle(color) }
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Notice(text: String) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
        Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Filled.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
            Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSecondaryContainer)
        }
    }
}

@Composable
private fun DoneContent(state: CropUiState.Done, onReset: () -> Unit) {
    Spacer(Modifier.height(32.dp))
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Icon(
            Icons.Filled.CheckCircle,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(64.dp),
        )
    }
    Text("Saved", style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.padding(16.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(state.outputName, style = MaterialTheme.typography.titleMedium)
            Text(state.location, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                "New length ${formatTime(state.newDurationMs)}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    Button(onClick = onReset, modifier = Modifier.fillMaxWidth().height(56.dp)) { Text("Done") }
}

@Composable
private fun ErrorContent(message: String, onPickFile: () -> Unit) {
    Spacer(Modifier.height(32.dp))
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(Icons.Filled.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.onErrorContainer)
            Text(message, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onErrorContainer)
        }
    }
    Button(onClick = onPickFile, modifier = Modifier.fillMaxWidth().height(56.dp)) { Text("Pick a file") }
}

private const val NUDGE_MS = 100L

/** 192400 → "3:12.4" */
fun formatTime(ms: Long): String {
    val clamped = ms.coerceAtLeast(0)
    val minutes = clamped / 60_000
    val seconds = (clamped % 60_000) / 1000
    val tenths = (clamped % 1000) / 100
    return String.format(Locale.US, "%d:%02d.%d", minutes, seconds, tenths)
}

/** 8340 → "8.3s" */
fun formatSeconds(ms: Long): String = String.format(Locale.US, "%.1fs", ms / 1000.0)
