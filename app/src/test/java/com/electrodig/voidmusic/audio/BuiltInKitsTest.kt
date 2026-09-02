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
            BuiltInKits.DEFAULT.samples[DrumPad.KICK]?.rawResId,
            BuiltInKits.ELECTRO.samples[DrumPad.KICK]?.rawResId
        )
    }

    @Test
    fun `unknown index resolves to default kit`() {
        assertEquals(BuiltInKits.DEFAULT, BuiltInKits.byIndex(99))
    }
}
