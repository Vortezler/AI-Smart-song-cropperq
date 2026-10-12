package com.example.smartmusiccropper.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.example.smartmusiccropper.data.OutputMode

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    outputMode: OutputMode,
    onOutputModeChange: (OutputMode) -> Unit,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Output", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            Column(Modifier.selectableGroup()) {
                OutputOption(
                    title = "Save as new file",
                    description = "Writes <name>_cropped.m4a. The save dialog opens in the original's folder.",
                    selected = outputMode == OutputMode.SAVE_AS_NEW,
                    onClick = { onOutputModeChange(OutputMode.SAVE_AS_NEW) },
                )
                OutputOption(
                    title = "Overwrite original",
                    description = "Replaces the original file, renaming it to .m4a where the storage allows. " +
                        "If the original can't be written, you'll be asked where to save a new copy instead.",
                    selected = outputMode == OutputMode.OVERWRITE_ORIGINAL,
                    onClick = { onOutputModeChange(OutputMode.OVERWRITE_ORIGINAL) },
                )
            }
            Spacer(Modifier.padding(top = 8.dp))
            Text("About", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            Text(
                "Cropped tracks are always exported as AAC in an .m4a file, since Android has no built-in MP3 encoder. " +
                    "Detection runs fully on-device with Google's YAMNet audio classifier (Apache 2.0); " +
                    "the app has no network access.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun OutputOption(title: String, description: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onClick, role = Role.RadioButton)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        RadioButton(selected = selected, onClick = null)
        Spacer(Modifier.width(12.dp))
        Column {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
