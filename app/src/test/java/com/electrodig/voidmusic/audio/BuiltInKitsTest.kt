package com.electrodig.voidmusic.audio

import com.electrodig.voidmusic.detection.color.DrumPad
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class BuiltInKitsTest {
    @Test
    fun `catalogue contains two complete and distinct kits`() {
        assertEquals(2, BuiltInKits.all.size)
        BuiltInKits.all.forEach { kit ->
            assertEquals(DrumPad.entries.toSet(), kit.samples.keys)
        }
        assertNotEquals(
            (BuiltInKits.DEFAULT.samples[DrumPad.KICK] as AudioSampleSource.BuiltIn).rawResId,
            (BuiltInKits.ELECTRO.samples[DrumPad.KICK] as AudioSampleSource.BuiltIn).rawResId
        )
    }

    @Test
    fun `unknown index resolves to default kit`() {
        assertEquals(BuiltInKits.DEFAULT, BuiltInKits.byIndex(99))
    }

    @Test
    fun `stable id resolves built-in kit and unknown id falls back`() {
        assertEquals(BuiltInKits.ELECTRO, BuiltInKits.byId("electro"))
        assertEquals(BuiltInKits.DEFAULT, BuiltInKits.byId("missing"))
        assertEquals(BuiltInKits.DEFAULT, BuiltInKits.byId(null))
    }
}
