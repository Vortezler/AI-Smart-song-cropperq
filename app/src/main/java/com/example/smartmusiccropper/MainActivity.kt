package com.example.smartmusiccropper

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.smartmusiccropper.files.FilePicker
import com.example.smartmusiccropper.ui.CropScreen
import com.example.smartmusiccropper.ui.SettingsScreen
import com.example.smartmusiccropper.ui.theme.SmartMusicCropperTheme

/** Compose host: the crop screen, plus a settings screen reachable from the top bar. */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            SmartMusicCropperTheme {
                val vm: MainViewModel = viewModel()
                val state by vm.state.collectAsStateWithLifecycle()
                val outputMode by vm.outputMode.collectAsStateWithLifecycle()
                var showSettings by rememberSaveable { mutableStateOf(false) }

                val openLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument(), vm::onFilePicked)
                val createLauncher = rememberLauncherForActivityResult(FilePicker.CreateM4aDocument(), vm::onSaveLocationPicked)

                val context = LocalContext.current
                val lifecycleOwner = LocalLifecycleOwner.current
                LaunchedEffect(vm, lifecycleOwner) {
                    lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                        vm.events.collect { event ->
                            when (event) {
                                is UiEvent.CreateDocument -> createLauncher.launch(event.request)
                                is UiEvent.Toast -> Toast.makeText(context, event.message, Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                }

                if (showSettings) {
                    SettingsScreen(
                        outputMode = outputMode,
                        onOutputModeChange = vm::setOutputMode,
                        onBack = { showSettings = false },
                    )
                } else {
                    CropScreen(
                        state = state,
                        outputMode = outputMode,
                        onPickFile = { openLauncher.launch(FilePicker.AUDIO_MIME_TYPES) },
                        onCropChange = vm::onCropChanged,
                        onResetCrop = vm::resetCrop,
                        onExport = vm::onExportClicked,
                        onReset = vm::reset,
                        onOpenSettings = { showSettings = true },
                    )
                }
            }
        }
    }
}
