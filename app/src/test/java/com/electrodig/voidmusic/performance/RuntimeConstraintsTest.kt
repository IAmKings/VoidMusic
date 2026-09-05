package com.electrodig.voidmusic.performance

import android.os.PowerManager
import com.electrodig.voidmusic.persistence.PerformanceLevel
import org.junit.Assert.assertEquals
import org.junit.Test

class RuntimeConstraintsTest {

    @Test
    fun `no constraint preserves every preferred tier`() {
        val constraints = RuntimeConstraints()
        PerformanceLevel.entries.forEach { preferred ->
            assertEquals(preferred, constraints.effectiveLevel(preferred))
        }
    }

    @Test
    fun `power saver temporarily constrains performance to low`() {
        val constrained = RuntimeConstraints(powerSaveMode = true)
        assertEquals(PerformanceLevel.LOW, constrained.effectiveLevel(PerformanceLevel.HIGH))

        val restored = constrained.copy(powerSaveMode = false)
        assertEquals(PerformanceLevel.HIGH, restored.effectiveLevel(PerformanceLevel.HIGH))
    }

    @Test
    fun `moderate thermal status constrains while light status does not`() {
        assertEquals(
            PerformanceLevel.HIGH,
            RuntimeConstraints(thermalStatus = PowerManager.THERMAL_STATUS_LIGHT)
                .effectiveLevel(PerformanceLevel.HIGH)
        )
        assertEquals(
            PerformanceLevel.LOW,
            RuntimeConstraints(thermalStatus = PowerManager.THERMAL_STATUS_MODERATE)
                .effectiveLevel(PerformanceLevel.HIGH)
        )
    }
}
