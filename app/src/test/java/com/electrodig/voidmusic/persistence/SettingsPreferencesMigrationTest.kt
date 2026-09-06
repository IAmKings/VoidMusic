package com.electrodig.voidmusic.persistence

import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsPreferencesMigrationTest {
    private val settingsKey = stringPreferencesKey("settings_json")
    private val json = Json { ignoreUnknownKeys = true }
    private val migration = SettingsPreferencesMigration()

    @Test
    fun `legacy preference migrates once to stable kit id`() = runBlocking {
        val legacy = mutablePreferencesOf(settingsKey to """{"activeKitIndex":1}""")

        assertTrue(migration.shouldMigrate(legacy))

        val migrated = migration.migrate(legacy)
        val settings = json.decodeFromString<Settings>(requireNotNull(migrated[settingsKey]))

        assertEquals("electro", settings.activeKitId)
        assertEquals(CURRENT_KIT_SELECTION_VERSION, settings.kitSelectionVersion)
        assertFalse(migration.shouldMigrate(migrated))
    }

    @Test
    fun `corrupt preference remains untouched and does not block startup`() = runBlocking {
        val corrupt = mutablePreferencesOf(settingsKey to "not-json")

        assertFalse(migration.shouldMigrate(corrupt))
        assertEquals(corrupt, migration.migrate(corrupt))
    }
}
