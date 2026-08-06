package com.electrodig.objectdrumstudio.detection.hand

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class OneEuroFilterTest {

    private val filter = OneEuroFilter(minCutoff = 1.5f, beta = 0.05f)

    @Test
    fun `first sample passes through unchanged`() {
        val out = filter.filter(0.42f, 0L)
        assertEquals(0.42f, out, 1e-5f)
    }

    @Test
    fun `steady noisy signal converges near its mean`() {
        // Feed a constant 0.5 with small alternating jitter.
        var prev = 0.5f
        var t = 0L
        for (i in 0 until 200) {
            val noisy = 0.5f + if (i % 2 == 0) 0.01f else -0.01f
            prev = filter.filter(noisy, t)
            t += 10
        }
        // After settling, output should hug the 0.5 mean within the jitter.
        assertTrue("settled near mean: $prev", abs(prev - 0.5f) < 0.03f)
    }

    @Test
    fun `smoothed output is less jittery than raw input`() {
        // Measure total variation of filtered vs raw over a noisy ramp.
        val f = OneEuroFilter(minCutoff = 1.0f, beta = 0.0f) // strong smoothing
        var rawVar = 0.0
        var filtVar = 0.0
        var prevRaw = 0.5f
        var prevFilt = 0.5f
        var t = 0L
        for (i in 0 until 100) {
            val raw = 0.5f + (if (i % 2 == 0) 0.05f else -0.05f) + i * 0.0005f
            val filt = f.filter(raw, t)
            rawVar += abs(raw - prevRaw)
            filtVar += abs(filt - prevFilt)
            prevRaw = raw; prevFilt = filt
            t += 10
        }
        assertTrue("filtered variation < raw ($filtVar vs $rawVar)", filtVar < rawVar)
    }

    @Test
    fun `large step is tracked with bounded lag`() {
        // Jump from 0 to 1; with beta>0 the filter should get close within ~30 samples.
        val f = OneEuroFilter(minCutoff = 1.5f, beta = 0.1f)
        f.filter(0f, 0L)
        var out = 0f
        var t = 10L
        for (i in 0 until 40) {
            out = f.filter(1f, t); t += 10
        }
        assertTrue("approaches target: $out", out > 0.85f)
    }

    @Test
    fun `reset clears internal state`() {
        filter.filter(0.9f, 0L)
        filter.reset()
        // After reset, the next sample should pass through like the first.
        val out = filter.filter(0.1f, 100L)
        assertEquals(0.1f, out, 1e-5f)
    }
}
