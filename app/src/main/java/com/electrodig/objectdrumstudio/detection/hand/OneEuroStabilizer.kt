package com.electrodig.objectdrumstudio.detection.hand

/**
 * 1-Euro (One-Euro) low-pass filter for a single scalar coordinate.
 *
 * Adaptive: when the signal moves slowly the filter is heavily smoothed (kills
 * jitter), but as speed rises the cutoff increases so fast taps stay responsive.
 * Ideal for finger-triggered input where you want both a steady hover and a
 * crisp strike (PRD §9.4 HandStabilizer / F5.5).
 *
 * Reference: Casiez, Roussel, Vogel (CHI 2012).
 */
class OneEuroFilter(
    private val minCutoff: Float = 1.5f,    // lower = smoother at rest
    private val beta: Float = 0.05f,        // higher = more responsiveness to speed
    private val dCutoff: Float = 1.0f
) {
    private var xFiltered: Float? = null
    private var dxFiltered = 0f
    private var lastTsMs: Long = -1L

    fun filter(value: Float, tsMs: Long): Float {
        val prev = xFiltered
        if (prev == null || lastTsMs < 0) {
            xFiltered = value
            lastTsMs = tsMs
            return value
        }
        val dt = ((tsMs - lastTsMs) / 1000f).coerceAtLeast(1e-5f)
        lastTsMs = tsMs

        // Estimate derivative (speed) and smooth it.
        val dx = (value - prev) / dt
        val alphaD = smoothingAlpha(dCutoff, dt)
        dxFiltered = alphaD * dx + (1 - alphaD) * dxFiltered

        // Cutoff frequency rises with speed.
        val cutoff = minCutoff + beta * kotlin.math.abs(dxFiltered)
        val alpha = smoothingAlpha(cutoff, dt)
        val out = alpha * value + (1 - alpha) * prev
        xFiltered = out
        return out
    }

    private fun smoothingAlpha(cutoff: Float, dt: Float): Float {
        val tau = 1f / (2f * Math.PI.toFloat() * cutoff)
        return 1f / (1f + tau / dt)
    }

    fun reset() {
        xFiltered = null
        dxFiltered = 0f
        lastTsMs = -1L
    }
}
