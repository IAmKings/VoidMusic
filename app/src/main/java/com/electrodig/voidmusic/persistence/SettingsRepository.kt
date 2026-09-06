package com.electrodig.voidmusic.persistence

import android.content.Context
import androidx.datastore.core.DataMigration
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

    private val json = SETTINGS_JSON

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
                settings.withCurrentMigrations()
            )
        }
    }

    /** Patch a subset of fields without clobbering the rest. */
    suspend fun update(transform: (Settings) -> Settings) {
        context.dataStore.edit { prefs ->
            val current = decodeSettings(prefs[KEY_SETTINGS])
            prefs[KEY_SETTINGS] = json.encodeToString(
                Settings.serializer(),
                transform(current).withCurrentMigrations()
            )
        }
    }

    private fun decodeSettings(encoded: String?): Settings = encoded
        ?.let { runCatching { json.decodeFromString<Settings>(it) }.getOrNull() }
        ?.withCurrentMigrations()
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
        private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(
            name = "ods_settings",
            produceMigrations = { listOf(SettingsPreferencesMigration()) }
        )
    }
}

private val SETTINGS_JSON = Json { ignoreUnknownKeys = true; encodeDefaults = true }
private val KEY_SETTINGS = stringPreferencesKey("settings_json")
private val KEY_ONBOARDING_COMPLETED = booleanPreferencesKey("onboarding_completed")

/** Persists the stable kit id before consumers observe legacy index-based settings. */
internal class SettingsPreferencesMigration : DataMigration<Preferences> {
    override suspend fun shouldMigrate(currentData: Preferences): Boolean {
        val encoded = currentData[KEY_SETTINGS] ?: return false
        val settings = runCatching {
            SETTINGS_JSON.decodeFromString<Settings>(encoded)
        }.getOrNull() ?: return false
        return settings.kitSelectionVersion < CURRENT_KIT_SELECTION_VERSION ||
            settings.activeKitId == null
    }

    override suspend fun migrate(currentData: Preferences): Preferences {
        val encoded = currentData[KEY_SETTINGS] ?: return currentData
        val settings = runCatching {
            SETTINGS_JSON.decodeFromString<Settings>(encoded)
        }.getOrNull() ?: return currentData
        return currentData.toMutablePreferences().apply {
            this[KEY_SETTINGS] = SETTINGS_JSON.encodeToString(
                Settings.serializer(),
                settings.withCurrentMigrations()
            )
        }
    }

    override suspend fun cleanUp() = Unit
}
