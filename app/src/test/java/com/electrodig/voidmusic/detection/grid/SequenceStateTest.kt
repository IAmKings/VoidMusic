package com.electrodig.voidmusic.detection.grid

import com.electrodig.voidmusic.detection.color.DrumPad
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SequenceStateTest {

    @Test
    fun `default state is 4x16 all off`() {
        val s = SequenceState()
        assertEquals(4, s.rows)
        assertEquals(16, s.steps)
        for (r in 0 until s.rows) for (c in 0 until s.steps) assertFalse(s.isOn(r, c))
    }

    @Test
    fun `row to pad mapping is stable`() {
        assertEquals(
            listOf(DrumPad.KICK, DrumPad.SNARE, DrumPad.CLAP, DrumPad.HIHAT),
            SequenceState.ROW_PADS
        )
    }

    @Test
    fun `toggling grid reflects in isOn`() {
        val s = SequenceState()
        s.grid[0][0] = true
        s.grid[3][15] = true
        assertTrue(s.isOn(0, 0))
        assertTrue(s.isOn(3, 15))
        assertFalse(s.isOn(0, 1))
    }

    @Test
    fun `isOn clamps out of range indices to false`() {
        val s = SequenceState()
        assertFalse(s.isOn(-1, 0))
        assertFalse(s.isOn(0, 99))
        assertFalse(s.isOn(99, 0))
    }

    @Test
    fun `copyWithGrid produces an independent copy`() {
        val s = SequenceState()
        val newGrid = List(s.rows) { List(s.steps) { false } }.map { it.toMutableList() }
        newGrid[1][2] = true
        val s2 = s.copyWithGrid(newGrid)
        assertTrue(s2.isOn(1, 2))
        assertFalse("original untouched", s.isOn(1, 2))
    }

    @Test
    fun `bpm and currentStep defaults`() {
        val s = SequenceState()
        assertEquals(SequenceState.DEFAULT_BPM, s.bpm)
        assertEquals(0, s.currentStep)
        assertFalse(s.isPlaying)
    }
}
