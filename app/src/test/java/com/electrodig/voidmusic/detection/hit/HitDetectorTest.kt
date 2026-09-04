package com.electrodig.voidmusic.detection.hit

import com.electrodig.voidmusic.TestFixtures
import com.electrodig.voidmusic.detection.hand.Hand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HitDetectorTest {
    private val detector = HitDetector(velocityThreshold = 0.6f, cooldownMs = 120)

    @Test
    fun `no movement and slow downward drift produce no candidate`() {
        repeat(5) { frame ->
            assertTrue(detector.update(listOf(hand(0.5f, 0.5f)), ts(frame)).isEmpty())
        }
        val slow = HitDetector(velocityThreshold = 0.6f)
        slow.update(listOf(hand(0.5f, 0.30f)), 0)
        assertTrue(slow.update(listOf(hand(0.5f, 0.32f)), 100).isEmpty())
    }

    @Test
    fun `downward strike emits actual speed`() {
        detector.update(listOf(hand(0.5f, 0.30f)), 0)
        val hit = detector.update(listOf(hand(0.5f, 0.42f)), 40).single()

        assertEquals(3f, hit.velocity, 0.001f)
    }

    @Test
    fun `faster strikes produce higher velocity`() {
        val slow = HitDetector(velocityThreshold = 0.1f)
        slow.update(listOf(hand(0.5f, 0.30f)), 0)
        val slowVelocity = slow.update(listOf(hand(0.5f, 0.36f)), 100).single().velocity

        val fast = HitDetector(velocityThreshold = 0.1f)
        fast.update(listOf(hand(0.5f, 0.30f)), 0)
        val fastVelocity = fast.update(listOf(hand(0.5f, 0.42f)), 40).single().velocity

        assertTrue(fastVelocity > slowVelocity)
    }

    @Test
    fun `upward and lateral motion do not trigger`() {
        detector.update(listOf(hand(0.5f, 0.5f)), 0)
        assertTrue(detector.update(listOf(hand(0.5f, 0.3f)), 40).isEmpty())

        val lateral = HitDetector(velocityThreshold = 0.6f)
        lateral.update(listOf(hand(0.3f, 0.5f)), 0)
        assertTrue(lateral.update(listOf(hand(0.5f, 0.5f)), 40).isEmpty())
    }

    @Test
    fun `cooldown follows a finger when hand ordering changes`() {
        detector.update(listOf(hand(0.2f, 0.3f, "Left"), hand(0.8f, 0.3f, "Right")), 0)
        assertEquals(
            2,
            detector.update(listOf(hand(0.2f, 0.4f, "Left"), hand(0.8f, 0.4f, "Right")), 40).size
        )

        // Same fingers, now reverse MediaPipe's list order and strike again
        // inside cooldown: no duplicate candidates may escape.
        assertTrue(
            detector.update(listOf(hand(0.8f, 0.5f, "Right"), hand(0.2f, 0.5f, "Left")), 80).isEmpty()
        )
    }

    @Test
    fun `missing hand expires before a reappearance`() {
        detector.update(listOf(hand(0.5f, 0.3f)), 0)
        detector.update(emptyList<Hand>(), 600)
        detector.update(listOf(hand(0.5f, 0.3f)), 610)
        assertEquals(1, detector.update(listOf(hand(0.5f, 0.4f)), 650).size)
    }

    @Test
    fun `continuous downward motion only produces one candidate`() {
        val detector = HitDetector(velocityThreshold = 0.6f, cooldownMs = 110)
        detector.update(listOf(hand(0.5f, 0.20f)), 0)
        assertEquals(1, detector.update(listOf(hand(0.5f, 0.32f)), 40).size)
        assertTrue(detector.update(listOf(hand(0.5f, 0.44f)), 80).isEmpty())
        assertTrue(detector.update(listOf(hand(0.5f, 0.56f)), 120).isEmpty())
    }

    @Test
    fun `lift and a fast downstroke can retrigger after rearm delay`() {
        val detector = HitDetector(velocityThreshold = 0.6f, cooldownMs = 110)
        detector.update(listOf(hand(0.5f, 0.20f)), 0)
        assertEquals(1, detector.update(listOf(hand(0.5f, 0.32f)), 40).size)
        assertTrue(detector.update(listOf(hand(0.5f, 0.24f)), 150).isEmpty())
        assertEquals(1, detector.update(listOf(hand(0.5f, 0.36f)), 190).size)
    }

    @Test
    fun `early lift is remembered for a 160 bpm sixteenth note`() {
        val detector = HitDetector()
        detector.update(listOf(hand(0.5f, 0.20f)), 0)
        assertEquals(1, detector.update(listOf(hand(0.5f, 0.32f)), 40).size)

        // Lift arrives before the rearm delay, then the next downstroke lands
        // 94 ms after the first hit (a sixteenth note at roughly 160 BPM).
        assertTrue(detector.update(listOf(hand(0.5f, 0.24f)), 70).isEmpty())
        assertEquals(1, detector.update(listOf(hand(0.5f, 0.36f)), 134).size)
    }

    @Test
    fun `same handedness keeps its tracker across a fast lateral transition`() {
        val detector = HitDetector(velocityThreshold = 0.5f, cooldownMs = 60)
        detector.update(listOf(hand(0.20f, 0.20f, "Right")), 0)
        assertEquals(1, detector.update(listOf(hand(0.20f, 0.32f, "Right")), 40).size)
        detector.update(listOf(hand(0.46f, 0.24f, "Right")), 70)

        assertEquals(1, detector.update(listOf(hand(0.72f, 0.36f, "Right")), 134).size)
    }

    private fun hand(x: Float, y: Float, handedness: String = "Right") =
        TestFixtures.handAtFingertip(x, y, handedness)

    private fun ts(frame: Int): Long = frame * 40L
}
