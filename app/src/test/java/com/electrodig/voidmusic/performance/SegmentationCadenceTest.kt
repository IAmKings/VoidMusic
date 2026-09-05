package com.electrodig.voidmusic.performance

import com.electrodig.voidmusic.detection.color.DrumPad
import com.electrodig.voidmusic.detection.color.DrumZone
import org.junit.Assert.assertEquals
import org.junit.Test

class SegmentationCadenceTest {

    @Test
    fun `first frame segments immediately`() {
        assertEquals(true, SegmentationCadence(15).shouldSegment())
    }

    @Test
    fun `stable low tier scene backs off without exceeding freshness budget`() {
        val cadence = SegmentationCadence(analysisFrameCap = 15, stableRunsBeforeIdle = 2)
        val zone = zoneAt(0.4f, 0.5f)

        assertEquals(true, cadence.shouldSegment())
        cadence.record(listOf(zone))
        assertEquals(true, cadence.shouldSegment())
        cadence.record(listOf(zone))
        assertEquals(true, cadence.shouldSegment())
        cadence.record(listOf(zone))

        assertEquals(3, cadence.idleIntervalFrames)
        assertEquals(false, cadence.shouldSegment())
        assertEquals(false, cadence.shouldSegment())
        assertEquals(true, cadence.shouldSegment())
    }

    @Test
    fun `stable high tier scene uses five frame idle interval`() {
        val cadence = SegmentationCadence(analysisFrameCap = 30, stableRunsBeforeIdle = 1)
        val zone = zoneAt(0.4f, 0.5f)

        assertEquals(true, cadence.shouldSegment())
        cadence.record(listOf(zone))
        assertEquals(false, cadence.shouldSegment())
        assertEquals(true, cadence.shouldSegment())
        cadence.record(listOf(zone.copy(area = 101f)))

        assertEquals(6, cadence.idleIntervalFrames)
        repeat(5) { assertEquals(false, cadence.shouldSegment()) }
        assertEquals(true, cadence.shouldSegment())
    }

    @Test
    fun `input invalidation forces next frame`() {
        val cadence = SegmentationCadence(analysisFrameCap = 30)
        assertEquals(true, cadence.shouldSegment())
        assertEquals(false, cadence.shouldSegment())

        cadence.invalidate()

        assertEquals(true, cadence.shouldSegment())
    }

    @Test
    fun `zone movement returns cadence to active interval`() {
        val cadence = SegmentationCadence(analysisFrameCap = 30, stableRunsBeforeIdle = 1)
        val zone = zoneAt(0.4f, 0.5f)
        cadence.shouldSegment()
        cadence.record(listOf(zone))
        cadence.shouldSegment()
        cadence.shouldSegment()
        cadence.record(listOf(zone.copy(normalizedCenter = DrumZone.Point(0.7f, 0.5f))))

        assertEquals(false, cadence.shouldSegment())
        assertEquals(true, cadence.shouldSegment())
    }

    private fun zoneAt(x: Float, y: Float) = DrumZone(
        id = 1,
        center = DrumZone.Point(x * 100f, y * 100f),
        area = 100f,
        width = 10,
        height = 10,
        presetName = "红",
        mappedPad = DrumPad.KICK,
        normalizedCenter = DrumZone.Point(x, y),
        normalizedBox = DrumZone.Rect(x - 0.05f, y - 0.05f, x + 0.05f, y + 0.05f)
    )
}
