package com.electrodig.voidmusic.camera

import org.junit.Assert.assertEquals
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
        assertEquals(true, shouldAnalyzeFrame(10L, 0L, frameCap = 0))
    }

    @Test
    fun `first capped frame is always accepted`() {
        assertEquals(true, shouldAnalyzeFrame(10L, Long.MIN_VALUE, frameCap = 20))
    }

    @Test
    fun `capped analysis rejects frames before its interval`() {
        val intervalNs = 1_000_000_000L / 20L
        assertEquals(false, shouldAnalyzeFrame(intervalNs - 1L, 0L, frameCap = 20))
        assertEquals(true, shouldAnalyzeFrame(intervalNs, 0L, frameCap = 20))
    }
}
