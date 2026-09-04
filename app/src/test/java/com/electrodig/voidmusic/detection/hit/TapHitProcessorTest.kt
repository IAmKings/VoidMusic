package com.electrodig.voidmusic.detection.hit

import com.electrodig.voidmusic.TestFixtures
import com.electrodig.voidmusic.detection.color.DrumPad
import com.electrodig.voidmusic.detection.hand.TimestampedHands
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TapHitProcessorTest {
    private val zone = TestFixtures.zoneAt(id = 1, cx = 0.5f, cy = 0.5f, pad = DrumPad.KICK)
    private val leftZone = TestFixtures.zoneAt(
        id = 2, cx = 0.3f, cy = 0.36f, halfW = 0.08f, halfH = 0.08f, pad = DrumPad.SNARE
    )
    private val rightZone = TestFixtures.zoneAt(
        id = 3, cx = 0.7f, cy = 0.36f, halfW = 0.08f, halfH = 0.08f, pad = DrumPad.HIHAT
    )

    @Test
    fun `fresh result and zone produce a trigger`() {
        val processor = processor()
        processor.process(frame(0, 0.3f, 1), 1, HitSnapshot(listOf(zone), 0))

        assertEquals(1, processor.process(frame(40, 0.5f, 40), 40, HitSnapshot(listOf(zone), 0)).size)
    }

    @Test
    fun `newer cached zone remains valid when delayed inference result arrives`() {
        val processor = processor()
        processor.process(frame(0, 0.3f, 1), 1, HitSnapshot(listOf(zone), 0))

        // MediaPipe callbacks are asynchronous: the latest cached camera frame
        // can legitimately be newer than the source image of this result.
        assertEquals(1, processor.process(frame(40, 0.5f, 60), 60, HitSnapshot(listOf(zone), 55)).size)
    }

    @Test
    fun `stale hand source result is ignored before gesture state changes`() {
        val processor = processor()
        assertTrue(processor.process(frame(0, 0.3f, 0), 0, HitSnapshot(listOf(zone), 0)).isEmpty())
        assertTrue(processor.process(frame(40, 0.5f, 180), 181, HitSnapshot(listOf(zone), 181)).isEmpty())
    }

    @Test
    fun `stale zone snapshot is ignored`() {
        val processor = processor()
        assertTrue(processor.process(frame(261, 0.5f, 261), 261, HitSnapshot(listOf(zone), 0)).isEmpty())
    }

    @Test
    fun `static zone remains hittable across three frames at sixteen fps`() {
        val processor = processor()
        processor.process(frame(0, 0.3f, 0), 0, HitSnapshot(listOf(zone), 0))

        assertEquals(1, processor.process(frame(190, 0.5f, 190), 190, HitSnapshot(listOf(zone), 0)).size)
    }

    @Test
    fun `early lift can alternate between colors at 160 bpm`() {
        val processor = TapHitProcessor(HitDetector(), HitArbiter())
        val zones = listOf(leftZone, rightZone)
        processor.process(frame(0, 0.30f, 0, x = 0.3f), 0, HitSnapshot(zones, 0))
        val first = processor.process(frame(40, 0.36f, 40, x = 0.3f), 40, HitSnapshot(zones, 0))
        processor.process(frame(70, 0.24f, 70, x = 0.5f), 70, HitSnapshot(zones, 0))
        val second = processor.process(frame(134, 0.36f, 134, x = 0.7f), 134, HitSnapshot(zones, 0))

        assertEquals(listOf(DrumPad.SNARE), first.map { it.pad })
        assertEquals(listOf(DrumPad.HIHAT), second.map { it.pad })
    }

    @Test
    fun `sustained inference latency does not permanently mute live tapping`() {
        val processor = processor()
        val snapshot = { nowMs: Long -> HitSnapshot(listOf(zone), nowMs - 20) }

        processor.process(frame(1_000, 0.30f, 1_186), 1_186, snapshot(1_186))
        val first = processor.process(frame(1_040, 0.50f, 1_226), 1_226, snapshot(1_226))
        processor.process(frame(1_070, 0.24f, 1_270), 1_270, snapshot(1_270))
        val second = processor.process(frame(1_134, 0.50f, 1_342), 1_342, snapshot(1_342))

        assertEquals(1, first.size)
        assertEquals(1, second.size)
    }

    @Test
    fun `freshness budget recovers when sustained inference latency rises`() {
        val processor = processor()
        val snapshot = { nowMs: Long -> HitSnapshot(listOf(zone), nowMs - 20) }

        processor.process(frame(0, 0.30f, 40), 40, snapshot(40))
        assertEquals(1, processor.process(frame(40, 0.50f, 80), 80, snapshot(80)).size)

        // A sudden delay increase is initially treated as a stale spike. Once it
        // persists, the bounded baseline adapts and the live path becomes playable again.
        for (timestampMs in listOf(70L, 100L, 130L, 160L, 190L)) {
            val consumedAtMs = timestampMs + 180
            processor.process(
                frame(timestampMs, 0.24f, consumedAtMs),
                consumedAtMs,
                snapshot(consumedAtMs)
            )
        }
        val secondHitAtMs = 230L
        val secondConsumedAtMs = secondHitAtMs + 180
        val second = processor.process(
            frame(secondHitAtMs, 0.50f, secondConsumedAtMs),
            secondConsumedAtMs,
            snapshot(secondConsumedAtMs)
        )

        assertEquals(1, second.size)
    }

    @Test
    fun `hand result beyond hard freshness ceiling is ignored`() {
        val processor = processor()
        processor.process(frame(0, 0.30f, 0), 0, HitSnapshot(listOf(zone), 0))

        assertTrue(
            processor.process(frame(40, 0.50f, 301), 301, HitSnapshot(listOf(zone), 281)).isEmpty()
        )
    }

    private fun processor() = TapHitProcessor(HitDetector(), HitArbiter())

    private fun frame(timestampMs: Long, y: Float, callbackMs: Long, x: Float = 0.5f) =
        TimestampedHands(timestampMs, listOf(TestFixtures.handAtFingertip(x, y)), callbackMs)
}
