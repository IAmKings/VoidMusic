package com.electrodig.voidmusic.persistence

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json

/**
 * Persists [Settings] and app-level completion state via DataStore Preferences (PRD F8).
 *
 * Settings are serialised to a single JSON string under one key. This keeps the
 * schema flexible (no per-field migration) and the data is tiny.
 */
class SettingsRepository(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    val settings: Flow<Settings> = context.dataStore.data.map { prefs ->
        decodeSettings(prefs[KEY_SETTINGS])
    }

    /** App-level completion marker kept separate from resettable performance settings. */
    val onboardingCompleted: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[KEY_ONBOARDING_COMPLETED] ?: false
    }

    suspend fun save(settings: Settings) {
        context.dataStore.edit { prefs ->
            prefs[KEY_SETTINGS] = json.encodeToString(
                Settings.serializer(),
                settings.withCurrentHitTuning()
            )
        }
    }

    /** Patch a subset of fields without clobbering the rest. */
    suspend fun update(transform: (Settings) -> Settings) {
        context.dataStore.edit { prefs ->
            val current = decodeSettings(prefs[KEY_SETTINGS])
            prefs[KEY_SETTINGS] = json.encodeToString(
                Settings.serializer(),
                transform(current).withCurrentHitTuning()
            )
        }
    }

    private fun decodeSettings(encoded: String?): Settings = encoded
        ?.let { runCatching { json.decodeFromString<Settings>(it) }.getOrNull() }
        ?.withCurrentHitTuning()
        ?: Settings.DEFAULT

    suspend fun markOnboardingCompleted() {
        context.dataStore.edit { prefs ->
            prefs[KEY_ONBOARDING_COMPLETED] = true
        }
    }

    /** Reset performance settings to defaults without clearing app-level completion state. */
    suspend fun reset() {
        save(Settings.DEFAULT)
    }

    companion object {
        private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "ods_settings")
        private val KEY_SETTINGS = stringPreferencesKey("settings_json")
        private val KEY_ONBOARDING_COMPLETED = booleanPreferencesKey("onboarding_completed")
    }
}
