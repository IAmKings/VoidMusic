package com.electrodig.voidmusic.detection.hit

import com.electrodig.voidmusic.TestFixtures
import com.electrodig.voidmusic.detection.color.DrumPad
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PadTrackerTest {

    private val tracker = PadTracker(boxExpansion = 0.04f, nearRadius = 0.06f)

    // Zone at (0.5,0.5), box ±0.05 → [0.45,0.55]; expanded by 0.04 → [0.41,0.59].
    private val zone = TestFixtures.zoneAt(id = 1, cx = 0.5f, cy = 0.5f, halfW = 0.05f, halfH = 0.05f)
    private val zones = listOf(zone)

    @Test
    fun `point at centre returns the zone`() {
        assertEquals(zone.id, tracker.locate(zones, HitCandidate.Point(0.5f, 0.5f))?.id)
    }

    @Test
    fun `point inside box returns the zone`() {
        assertEquals(zone.id, tracker.locate(zones, HitCandidate.Point(0.52f, 0.48f))?.id)
    }

    @Test
    fun `point within expanded box margin returns the zone`() {
        // 0.58 is inside the expanded box [0.41,0.59] on both axes → box-hit.
        assertEquals(zone.id, tracker.locate(zones, HitCandidate.Point(0.58f, 0.5f))?.id)
    }

    @Test
    fun `point outside expanded box but within near radius returns the zone`() {
        // Use a fresh tracker with a larger nearRadius so we can place a point
        // beyond the expanded box yet still close to the centre.
        val t = PadTracker(boxExpansion = 0.02f, nearRadius = 0.12f)
        val z = TestFixtures.zoneAt(id = 7, cx = 0.5f, cy = 0.5f, halfW = 0.05f, halfH = 0.05f)
        // Expanded box edge at 0.5+0.05+0.02 = 0.57. Point at 0.60 is outside the
        // box but distance-to-centre is 0.10 <= nearRadius 0.12 → near-hit.
        assertEquals(7, t.locate(listOf(z), HitCandidate.Point(0.60f, 0.5f))?.id)
    }

    @Test
    fun `point beyond both box and near radius returns null`() {
        // 0.65: outside expanded box (0.59), distance-to-centre 0.15 > nearRadius 0.06.
        assertNull(tracker.locate(zones, HitCandidate.Point(0.65f, 0.5f)))
    }

    @Test
    fun `point far away returns null`() {
        assertNull(tracker.locate(zones, HitCandidate.Point(0.1f, 0.1f)))
    }

    @Test
    fun `picks nearest when two zones could match`() {
        val near = TestFixtures.zoneAt(id = 1, cx = 0.5f, cy = 0.5f)
        val far = TestFixtures.zoneAt(id = 2, cx = 0.62f, cy = 0.5f, pad = DrumPad.SNARE)
        // Point 0.5,0.5 lands inside near's box.
        assertEquals(1, tracker.locate(listOf(near, far), HitCandidate.Point(0.5f, 0.5f))?.id)
    }

    @Test
    fun `empty zones returns null`() {
        assertNull(tracker.locate(emptyList(), HitCandidate.Point(0.5f, 0.5f)))
    }
}
