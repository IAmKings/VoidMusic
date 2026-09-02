package com.electrodig.voidmusic.detection.color

import com.electrodig.voidmusic.TestFixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ZoneTrackerTest {
    @Test
    fun `two objects keep their own IDs across a moved frame`() {
        val tracker = ZoneTracker()
        val first = tracker.update(
            listOf(TestFixtures.zoneAt(0, 0.2f, 0.3f), TestFixtures.zoneAt(0, 0.7f, 0.6f)),
            0
        )
        val second = tracker.update(
            listOf(TestFixtures.zoneAt(0, 0.22f, 0.31f), TestFixtures.zoneAt(0, 0.68f, 0.59f)),
            33
        )

        assertEquals(first[0].id, second[0].id)
        assertEquals(first[1].id, second[1].id)
        assertNotEquals(second[0].id, second[1].id)
    }

    @Test
    fun `brief occlusion retains ID but expired object gets a fresh ID`() {
        val tracker = ZoneTracker(ttlMs = 100)
        val first = tracker.update(listOf(TestFixtures.zoneAt(0, 0.4f, 0.4f)), 0).single()

        assertEquals(emptyList<DrumZone>(), tracker.update(emptyList(), 50))
        val returned = tracker.update(listOf(TestFixtures.zoneAt(0, 0.41f, 0.4f)), 90).single()
        assertEquals(first.id, returned.id)

        tracker.update(emptyList(), 250)
        val afterExpiry = tracker.update(listOf(TestFixtures.zoneAt(0, 0.41f, 0.4f)), 251).single()
        assertNotEquals(first.id, afterExpiry.id)
    }

    @Test
    fun `different presets do not share a track`() {
        val tracker = ZoneTracker()
        val red = TestFixtures.zoneAt(0, 0.4f, 0.4f)
        val blue = red.copy(presetName = "blue", mappedPad = DrumPad.SNARE)

        val first = tracker.update(listOf(red), 0).single()
        val second = tracker.update(listOf(blue), 33).single()

        assertNotEquals(first.id, second.id)
    }
}
