package com.electrodig.voidmusic.persistence

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class SettingsSerializationTest {
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    @Test
    fun `session snapshot survives settings JSON round trip`() {
        val source = Settings(
            lastMode = "STEP",
            lastBpm = 136,
            sequenceGrid = List(4) { row -> List(16) { step -> row == 2 && step == 7 } },
            calibration = listOf(
                CalibrationPoint(0.1f, 0.2f),
                CalibrationPoint(0.9f, 0.2f),
                CalibrationPoint(0.9f, 0.8f),
                CalibrationPoint(0.1f, 0.8f)
            )
        )

        val restored = json.decodeFromString<Settings>(json.encodeToString(Settings.serializer(), source))

        assertEquals(source, restored)
    }

    @Test
    fun `missing session fields fall back to safe defaults for old settings`() {
        val restored = json.decodeFromString<Settings>("""{"lastBpm":128}""")

        assertEquals("TAP", restored.lastMode)
        assertEquals(4, restored.sequenceGrid.size)
        assertEquals(16, restored.sequenceGrid.first().size)
        assertEquals(emptyList<CalibrationPoint>(), restored.calibration)
    }

    @Test
    fun `legacy hit defaults migrate to current musical tuning`() {
        val legacy = json.decodeFromString<Settings>(
            """{"hitVelocityThreshold":0.6,"hitCooldownMs":110}"""
        )

        val migrated = legacy.withCurrentHitTuning()

        assertEquals(0.5f, migrated.hitVelocityThreshold)
        assertEquals(60L, migrated.hitCooldownMs)
        assertEquals(CURRENT_HIT_TUNING_VERSION, migrated.hitTuningVersion)
    }

    @Test
    fun `current tuning preserves user customization`() {
        val current = Settings(
            hitVelocityThreshold = 0.8f,
            hitCooldownMs = 150,
            hitTuningVersion = CURRENT_HIT_TUNING_VERSION
        )

        assertSame(current, current.withCurrentHitTuning())
    }

    @Test
    fun `legacy customized tuning is versioned without being overwritten`() {
        val legacyCustom = Settings(
            hitVelocityThreshold = 0.8f,
            hitCooldownMs = 150,
            hitTuningVersion = 0
        )

        val migrated = legacyCustom.withCurrentHitTuning()

        assertEquals(0.8f, migrated.hitVelocityThreshold)
        assertEquals(150L, migrated.hitCooldownMs)
        assertEquals(CURRENT_HIT_TUNING_VERSION, migrated.hitTuningVersion)
    }

    @Test
    fun `legacy built-in kit indexes migrate to stable ids`() {
        val defaultKit = json.decodeFromString<Settings>("""{"activeKitIndex":0}""")
            .withCurrentMigrations()
        val electroKit = json.decodeFromString<Settings>("""{"activeKitIndex":1}""")
            .withCurrentMigrations()

        assertEquals("default", defaultKit.activeKitId)
        assertEquals("electro", electroKit.activeKitId)
        assertEquals(CURRENT_KIT_SELECTION_VERSION, defaultKit.kitSelectionVersion)
        assertEquals(CURRENT_KIT_SELECTION_VERSION, electroKit.kitSelectionVersion)
    }

    @Test
    fun `invalid legacy kit index falls back to default`() {
        val migrated = Settings(activeKitIndex = 42).withCurrentMigrations()

        assertEquals("default", migrated.activeKitId)
        assertEquals(CURRENT_KIT_SELECTION_VERSION, migrated.kitSelectionVersion)
    }

    @Test
    fun `current stable kit selection migration is idempotent`() {
        val current = Settings(
            activeKitIndex = 0,
            activeKitId = "user-kit-1",
            kitSelectionVersion = CURRENT_KIT_SELECTION_VERSION,
            hitTuningVersion = CURRENT_HIT_TUNING_VERSION
        )

        assertSame(current, current.withCurrentMigrations())
    }
}
