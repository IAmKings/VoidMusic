package com.electrodig.voidmusic.detection.hand

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class OneEuroHandStabilizerTest {

    private fun handAt(x: Float, imageWidth: Int = 640, imageHeight: Int = 480): Hand {
        val landmarks = List(21) { NormalizedLandmark(x, 0.5f, 0f) }
        return Hand(
            landmarks = landmarks,
            handedness = "Right",
            imageWidth = imageWidth,
            imageHeight = imageHeight
        )
    }

    @Test
    fun `smoothed fingertip tracks a moving target instead of freezing`() {
        // Regression guard for the synthetic-timestamp bug: the old code fed the
        // One-Euro filter a per-frame counter (dt = 0.001), which made the
        // filter's adaptive cutoff fall out of its calibrated regime and the
        // smoothed position stall. Here we drive the stabilizer with a real
        // 33ms-per-frame clock and a landmark that sweeps across normalised
        // space; the smoothed output must follow close behind, not freeze.
        var clockMs = 0L
        val stabilizer = OneEuroHandStabilizer(clock = { clockMs })

        val stepMs = 33L
        val frames = 60
        var lastOut = 0f
        var tracked = false
        for (i in 0 until frames) {
            val input = 0.2f + i * 0.01f
            val smoothed = stabilizer.smooth(listOf(handAt(input)))
            val out = smoothed.first().fingertip.x
            // After the ramp settles past the filter's rise time, the output
            // must be within a small tolerance of the input — the frozen-output
            // bug keeps |out - input| large forever.
            if (i > 40) {
                assertTrue("output should track input: out=$out input=$input", abs(out - input) < 0.05f)
                tracked = true
            }
            assertTrue("output must move between frames at i=$i", i == 0 || abs(out - lastOut) > 1e-6f || out == input)
            lastOut = out
            clockMs += stepMs
        }
        assertTrue("verifying samples were reached", tracked)
    }
}