package com.electrodig.objectdrumstudio.detection.hit

import com.electrodig.objectdrumstudio.TestFixtures
import com.electrodig.objectdrumstudio.detection.hand.Hand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HitDetectorTest {

    // velocityThreshold is a DISPLACEMENT threshold (normalised [0,1] units per
    // inter-frame interval). 0.03 ≈ 3% of frame width/height — above hand
    // jitter (~0.02) but below a real tap peak (0.05+). Uses the PEAK interval
    // in the window, not the average, so a momentary tap isn't diluted by
    // neighbouring quiescent frames. Robust to low frame rates because
    // displacement doesn't dilute with long dt the way speed does.
    private val detector = HitDetector(velocityThreshold = 0.03f, cooldownMs = 120, windowSize = 3)

    @Test
    fun `no movement produces no candidates`() {
        repeat(5) { i ->
            val out = detector.update(listOf(TestFixtures.handAtFingertip(0.5f, 0.5f)), ts(i))
            assertEquals("frame $i should yield no hits", 0, out.size)
        }
    }

    @Test
    fun `slow drift is below threshold`() {
        // +0.001/frame displacement — well under 0.03.
        var y = 0.3f
        val all = mutableListOf<HitCandidate>()
        repeat(10) { i ->
            y += 0.001f
            all += detector.update(listOf(TestFixtures.handAtFingertip(0.5f, y)), ts(i))
        }
        assertTrue("slow drift should not trigger", all.isEmpty())
    }

    @Test
    fun `fast downward strike emits a candidate`() {
        // +0.1 displacement in one interval → well over 0.03.
        detector.update(listOf(TestFixtures.handAtFingertip(0.5f, 0.3f)), ts(0))
        val out = detector.update(listOf(TestFixtures.handAtFingertip(0.5f, 0.4f)), ts(1))
        assertEquals("expected a hit", 1, out.size)
    }

    @Test
    fun `upward movement also counts as a strike (any direction)`() {
        // Y decreases (finger rises) fast — resultant displacement is non-zero.
        detector.update(listOf(TestFixtures.handAtFingertip(0.5f, 0.5f)), ts(0))
        val out = detector.update(listOf(TestFixtures.handAtFingertip(0.5f, 0.3f)), ts(1))
        assertEquals("fast upward motion should trigger (resultant)", 1, out.size)
    }

    @Test
    fun `lateral strike emits a candidate (side-view camera angle)`() {
        // Pure X displacement — the common case when the phone is held at an
        // angle and the finger strikes sideways toward the object.
        detector.update(listOf(TestFixtures.handAtFingertip(0.3f, 0.5f)), ts(0))
        val out = detector.update(listOf(TestFixtures.handAtFingertip(0.5f, 0.5f)), ts(1))
        assertEquals("lateral strike should trigger", 1, out.size)
    }

    @Test
    fun `strike is detected even at low frame rate (long dt)`() {
        // Simulate 11 FPS: 90ms between frames. A real tap produces a large
        // position delta regardless of sample rate. Displacement-based
        // detection must catch this; speed-based would dilute it.
        detector.update(listOf(TestFixtures.handAtFingertip(0.3f, 0.5f)), 0L)
        val out = detector.update(listOf(TestFixtures.handAtFingertip(0.5f, 0.5f)), 90L)
        assertEquals("low-FPS strike should trigger", 1, out.size)
    }

    @Test
    fun `peak displacement in window triggers even if neighbouring frames are quiet`() {
        // A tap whose peak delta is 0.065 but is preceded by a tiny 0.005 delta.
        // Average-based detection would dilute to 0.035 (barely over); peak-based
        // takes 0.065 — this is the common real-world tap pattern where the
        // window catches one high-motion frame surrounded by quiet ones.
        detector.update(listOf(TestFixtures.handAtFingertip(0.500f, 0.50f)), ts(0))
        detector.update(listOf(TestFixtures.handAtFingertip(0.505f, 0.50f)), ts(1)) // 0.005 delta (quiet)
        val out = detector.update(listOf(TestFixtures.handAtFingertip(0.570f, 0.50f)), ts(2)) // 0.065 delta (tap)
        assertEquals("peak tap should trigger", 1, out.size)
    }

    @Test
    fun `cooldown suppresses rapid repeated strikes`() {
        detector.update(listOf(TestFixtures.handAtFingertip(0.5f, 0.30f)), ts(0))
        val first = detector.update(listOf(TestFixtures.handAtFingertip(0.5f, 0.45f)), ts(1))
        assertEquals("first strike fires", 1, first.size)

        // Another fast drop within the 120ms cooldown.
        detector.update(listOf(TestFixtures.handAtFingertip(0.5f, 0.45f)), ts(6))
        val second = detector.update(listOf(TestFixtures.handAtFingertip(0.5f, 0.60f)), ts(7))
        assertTrue("second strike suppressed by cooldown", second.isEmpty())

        // After cooldown elapses, a strike fires again.
        val fresh = HitDetector(velocityThreshold = 0.03f, cooldownMs = 120, windowSize = 3)
        fresh.update(listOf(TestFixtures.handAtFingertip(0.5f, 0.10f)), 0L)
        val third = fresh.update(listOf(TestFixtures.handAtFingertip(0.5f, 0.40f)), 10L)
        assertEquals("strike after cooldown fires", 1, third.size)
    }

    @Test
    fun `empty hands clears trackers`() {
        detector.update(listOf(TestFixtures.handAtFingertip(0.5f, 0.3f)), ts(0))
        detector.update(listOf(TestFixtures.handAtFingertip(0.5f, 0.4f)), ts(1))
        detector.update(emptyList<Hand>(), ts(2))
        detector.update(listOf(TestFixtures.handAtFingertip(0.5f, 0.3f)), ts(3))
        val out = detector.update(listOf(TestFixtures.handAtFingertip(0.5f, 0.45f)), ts(4))
        assertEquals("re-appearing hand can strike", 1, out.size)
    }

    @Test
    fun `two hands tracked independently`() {
        val h1 = TestFixtures.handAtFingertip(0.2f, 0.3f, "Left")
        val h2 = TestFixtures.handAtFingertip(0.8f, 0.3f, "Right")
        detector.update(listOf(h1, h2), ts(0))
        val h1b = TestFixtures.handAtFingertip(0.2f, 0.5f, "Left")
        val h2b = TestFixtures.handAtFingertip(0.8f, 0.5f, "Right")
        val out = detector.update(listOf(h1b, h2b), ts(1))
        assertEquals("both hands strike", 2, out.size)
    }

    private fun ts(frame: Int): Long = frame * 10L
}
