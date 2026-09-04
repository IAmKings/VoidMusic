package com.electrodig.voidmusic.detection.hit

import com.electrodig.voidmusic.TestFixtures
import com.electrodig.voidmusic.detection.color.DrumPad
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HitArbiterTest {

    private val arbiter = HitArbiter(retriggerCooldownMs = 100)
    private val zone = TestFixtures.zoneAt(id = 1, cx = 0.5f, cy = 0.5f, pad = DrumPad.KICK)
    private val zones = listOf(zone)

    private fun candidateAt(x: Float, y: Float, velocity: Float = 5f, ts: Long) =
        HitCandidate(HitCandidate.Point(x, y), velocity, ts)

    @Test
    fun `candidate on zone produces a trigger with mapped pad`() {
        val out = arbiter.arbitrate(listOf(candidateAt(0.5f, 0.5f, ts = 0)), zones)
        assertEquals(1, out.size)
        assertEquals(DrumPad.KICK, out[0].pad)
        assertEquals(1, out[0].zoneId)
    }

    @Test
    fun `candidate off all zones produces no trigger`() {
        val out = arbiter.arbitrate(listOf(candidateAt(0.1f, 0.1f, ts = 0)), zones)
        assertTrue(out.isEmpty())
    }

    @Test
    fun `empty candidates or zones produces nothing`() {
        assertTrue(arbiter.arbitrate(emptyList(), zones).isEmpty())
        assertTrue(arbiter.arbitrate(listOf(candidateAt(0.5f, 0.5f, ts = 0)), emptyList()).isEmpty())
    }

    @Test
    fun `retrigger cooldown suppresses the same zone within window`() {
        val first = arbiter.arbitrate(listOf(candidateAt(0.5f, 0.5f, ts = 0)), zones)
        assertEquals("first fires", 1, first.size)
        val second = arbiter.arbitrate(listOf(candidateAt(0.5f, 0.5f, ts = 50)), zones)
        assertTrue("second within 100ms suppressed", second.isEmpty())
        val third = arbiter.arbitrate(listOf(candidateAt(0.5f, 0.5f, ts = 120)), zones)
        assertEquals("third after cooldown fires", 1, third.size)
    }

    @Test
    fun `default cooldown supports a 160 bpm sixteenth note`() {
        val musical = HitArbiter()
        assertEquals(1, musical.arbitrate(listOf(candidateAt(0.5f, 0.5f, ts = 0)), zones).size)
        assertTrue(musical.arbitrate(listOf(candidateAt(0.5f, 0.5f, ts = 60)), zones).isEmpty())
        assertEquals(1, musical.arbitrate(listOf(candidateAt(0.5f, 0.5f, ts = 94)), zones).size)
    }

    @Test
    fun `two candidates on two different zones both trigger`() {
        val z1 = TestFixtures.zoneAt(id = 1, cx = 0.3f, cy = 0.3f, pad = DrumPad.KICK)
        val z2 = TestFixtures.zoneAt(id = 2, cx = 0.7f, cy = 0.7f, pad = DrumPad.SNARE)
        val out = arbiter.arbitrate(
            listOf(candidateAt(0.3f, 0.3f, ts = 0), candidateAt(0.7f, 0.7f, ts = 0)),
            listOf(z1, z2)
        )
        assertEquals(2, out.size)
        val pads = out.map { it.pad }.toSet()
        assertTrue(pads.contains(DrumPad.KICK) && pads.contains(DrumPad.SNARE))
    }

    @Test
    fun `velocity maps into a bounded zero to one gain`() {
        // High speed → near 1; low speed → smaller but clamped to a floor.
        val fast = arbiter.arbitrate(listOf(candidateAt(0.5f, 0.5f, velocity = 20f, ts = 0)), zones)
        val slow = arbiter.arbitrate(
            listOf(candidateAt(0.5f, 0.5f, velocity = 2f, ts = 200)), zones
        )
        assertTrue("fast >= slow", fast[0].velocity >= slow[0].velocity)
        assertTrue("within range", fast[0].velocity in 0.25f..1.0f)
        assertTrue("within range", slow[0].velocity in 0.25f..1.0f)
    }
}
