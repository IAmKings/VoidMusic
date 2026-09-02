package com.electrodig.voidmusic.persistence

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
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
}
