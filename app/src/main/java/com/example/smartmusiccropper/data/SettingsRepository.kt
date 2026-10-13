package com.example.smartmusiccropper.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

enum class OutputMode {
    /** Write `<name>_cropped.m4a` as a new file. */
    SAVE_AS_NEW,

    /** Replace the original file's contents. */
    OVERWRITE_ORIGINAL,
}

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/** Persists the single user setting (output mode) with Jetpack DataStore. */
class SettingsRepository(context: Context) {

    private val dataStore = context.applicationContext.settingsDataStore

    val outputMode: Flow<OutputMode> = dataStore.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { prefs ->
            prefs[OUTPUT_MODE]?.let { stored -> OutputMode.entries.firstOrNull { it.name == stored } }
                ?: OutputMode.SAVE_AS_NEW
        }

    suspend fun setOutputMode(mode: OutputMode) {
        dataStore.edit { it[OUTPUT_MODE] = mode.name }
    }

    private companion object {
        val OUTPUT_MODE = stringPreferencesKey("output_mode")
    }
}
