package com.electrodig.voidmusic.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class FrameRouterTest {

    /**
     * `rotateBitmapForDisplay` delegates pixel rotation to Android's
     * `Matrix.postRotate` + `Bitmap.createBitmap`, which are framework classes
     * unavailable in pure JVM unit tests (no Robolectric on the classpath, and
     * Bitmap is final with a package-private constructor).
     *
     * The unit-testable surface is the degree normalisation that drives the
     * short-circuit ("no rotation → return same bitmap, zero allocation").
     * `normalisedRotationDegrees` is extracted for exactly this purpose. The
     * real pixel-level rotation is verified on-device under AC1/AC2.
     */

    @Test
    fun `zero degrees normalises to zero`() {
        assertEquals(0, normalisedRotationDegrees(0))
    }

    @Test
    fun `360 degrees normalises to zero`() {
        assertEquals(0, normalisedRotationDegrees(360))
    }

    @Test
    fun `720 degrees normalises to zero`() {
        assertEquals(0, normalisedRotationDegrees(720))
    }

    @Test
    fun `negative 360 degrees normalises to zero`() {
        assertEquals(0, normalisedRotationDegrees(-360))
    }

    @Test
    fun `90 degrees normalises to 90`() {
        assertEquals(90, normalisedRotationDegrees(90))
    }

    @Test
    fun `180 degrees normalises to 180`() {
        assertEquals(180, normalisedRotationDegrees(180))
    }

    @Test
    fun `270 degrees normalises to 270`() {
        assertEquals(270, normalisedRotationDegrees(270))
    }

    @Test
    fun `450 degrees normalises to 90`() {
        assertEquals(90, normalisedRotationDegrees(450))
    }

    @Test
    fun `negative 90 degrees normalises to 270`() {
        assertEquals(270, normalisedRotationDegrees(-90))
    }

    @Test
    fun `negative 270 degrees normalises to 90`() {
        assertEquals(90, normalisedRotationDegrees(-270))
    }

    @Test
    fun `negative 180 degrees normalises to 180`() {
        assertEquals(180, normalisedRotationDegrees(-180))
    }

    @Test
    fun `uncapped analysis accepts every frame`() {
        val gate = FrameCadenceGate(frameCap = 0)
        assertEquals(true, gate.shouldSubmit(10L))
        assertEquals(true, gate.shouldSubmit(11L))
    }

    @Test
    fun `first capped frame is always accepted`() {
        assertEquals(true, FrameCadenceGate(frameCap = 20).shouldSubmit(10L))
    }

    @Test
    fun `30 FPS input retains a 20 FPS long term cap`() {
        val gate = FrameCadenceGate(frameCap = 20)
        val submitted = (0 until 30).count { frame ->
            gate.shouldSubmit(frame * 1_000_000_000L / 30L)
        }

        assertEquals(20, submitted)
    }

    @Test
    fun `60 FPS input retains a 20 FPS long term cap`() {
        val gate = FrameCadenceGate(frameCap = 20)
        val submitted = (0 until 60).count { frame ->
            gate.shouldSubmit(frame * 1_000_000_000L / 60L)
        }

        assertEquals(20, submitted)
    }

    @Test
    fun `capped gate resumes immediately after a source pause`() {
        val gate = FrameCadenceGate(frameCap = 20)
        assertEquals(true, gate.shouldSubmit(0L))
        assertEquals(false, gate.shouldSubmit(10_000_000L))
        assertEquals(true, gate.shouldSubmit(5_000_000_000L))
    }

    @Test
    fun `consumer failure is isolated and frame closes exactly once`() {
        val frame = Any()
        val visited = mutableListOf<Int>()
        val failures = mutableListOf<Pair<Int, Throwable>>()
        var closeCount = 0
        val failure = IllegalStateException("broken consumer")

        dispatchFrame(
            frame = frame,
            consumers = listOf(0, 1, 2),
            consume = { consumer, receivedFrame ->
                assertSame(frame, receivedFrame)
                if (consumer == 1) throw failure
                visited += consumer
            },
            onConsumerFailure = { index, error -> failures += index to error },
            closeFrame = { closeCount++ }
        )

        assertEquals(listOf(0, 2), visited)
        assertEquals(listOf(1 to failure), failures)
        assertEquals(1, closeCount)
    }

    @Test
    fun `frame closes even when every consumer fails`() {
        var closeCount = 0

        dispatchFrame(
            frame = "frame",
            consumers = listOf("hand", "colour"),
            consume = { _, _ -> error("failure") },
            closeFrame = { closeCount++ }
        )

        assertEquals(1, closeCount)
    }

    @Test
    fun `frame closes when there are no consumers`() {
        var closedFrame: String? = null

        dispatchFrame(
            frame = "frame",
            consumers = emptyList<Unit>(),
            consume = { _, _ -> Unit },
            closeFrame = { closedFrame = it }
        )

        assertEquals("frame", closedFrame)
    }
}
