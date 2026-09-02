package com.electrodig.objectdrumstudio.detection.color

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HsvColorPickerTest {
    @Test
    fun `red sample wraps hue range across zero`() {
        val range = HsvColorPicker.rangeFor(
            listOf(HsvColorPicker.Sample(178, 145, 145), HsvColorPicker.Sample(1, 150, 150))
        )!!

        assertTrue(range.hMin > range.hMax)
        assertEquals(185.coerceAtMost(255), range.sMax)
        assertEquals(185.coerceAtMost(255), range.vMax)
    }

    @Test
    fun `range is bounded at HSV limits`() {
        val range = HsvColorPicker.rangeFor(listOf(HsvColorPicker.Sample(90, 2, 250)))!!

        assertEquals(80, range.hMin)
        assertEquals(100, range.hMax)
        assertEquals(0, range.sMin)
        assertEquals(37, range.sMax)
        assertEquals(215, range.vMin)
        assertEquals(255, range.vMax)
    }

    @Test
    fun `empty sample cannot create a range`() {
        assertNull(HsvColorPicker.rangeFor(emptyList()))
    }
}
