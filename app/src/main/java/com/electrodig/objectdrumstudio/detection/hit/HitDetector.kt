package com.electrodig.objectdrumstudio.detection.hit

import com.electrodig.objectdrumstudio.detection.hand.Hand
import com.electrodig.objectdrumstudio.detection.hand.NormalizedLandmark
import kotlin.math.hypot

/**
 * Detects "tap" gestures from fingertip trajectories (PRD §9.4).
 *
 * A tap is a fast fingertip displacement in any direction. The detector uses
 * **displacement** (normalised units per sample interval) rather than speed
 * (units/sec) so it is robust to low frame rates: at 11 FPS the inter-frame
 * interval is ~90ms, which would dilute a real tap's speed below a fixed
 * units/sec threshold. Displacement is independent of dt — a quick strike
 * produces a large position delta regardless of how often we sample it.
 *
 * Stateful: keeps the last sample per hand to compute velocity.
 */
class HitDetector(
    /** Min resultant displacement (normalised [0,1] units) between two frames
     *  required to count as a tap. 0.03 ≈ 3% of frame width/height — above
     *  hand jitter (~0.02) but below a real tap peak (0.05+). */
    private val velocityThreshold: Float = 0.03f,
    /** Minimum ms between two candidates from the same fingertip (debounce). */
    private val cooldownMs: Long = 250,
    /** How many recent samples to scan for the peak tap displacement. */
    private val windowSize: Int = 3
) {
    private data class Tracker(
        val samples: ArrayDeque<Sample> = ArrayDeque(),
        var lastCandidateMs: Long = Long.MIN_VALUE / 2
    )

    private data class Sample(val x: Float, val y: Float, val ts: Long)

    private val trackers = mutableMapOf<Int, Tracker>()

    fun update(hands: List<Hand>, timestampMs: Long): List<HitCandidate> {
        if (hands.isEmpty()) {
            trackers.clear()
            return emptyList()
        }

        val out = ArrayList<HitCandidate>(hands.size)
        hands.forEachIndexed { i, hand ->
            val tip = hand.fingertip
            val tracker = trackers.getOrPut(i) { Tracker() }

            tracker.samples.addLast(Sample(tip.x, tip.y, timestampMs))
            while (tracker.samples.size > windowSize) tracker.samples.removeFirst()

            val displacement = strikeDisplacement(tracker.samples)
            val cooled = timestampMs - tracker.lastCandidateMs >= cooldownMs
            if (displacement >= velocityThreshold && cooled) {
                out += HitCandidate(
                    point = HitCandidate.Point(tip.x, tip.y),
                    velocity = 1.0f,
                    timestampMs = timestampMs
                )
                tracker.lastCandidateMs = timestampMs
            }
        }
        if (trackers.keys.any { it >= hands.size }) trackers.keys.retainAll { it < hands.size }
        return out
    }

    /**
     * Max resultant displacement (normalised [0,1] units) per interval across
     * the sample window. Uses the PEAK interval rather than the average: a tap
     * is a momentary strike whose signal lives in a single inter-frame delta,
     * so averaging with the neighbouring (quiescent) frames dilutes it below
     * threshold. The peak preserves the strike's true magnitude.
     */
    private fun strikeDisplacement(samples: ArrayDeque<Sample>): Float {
        if (samples.size < 2) return 0f
        val arr = samples.toList()
        var max = 0f
        for (k in 1 until arr.size) {
            val a = arr[k - 1]; val b = arr[k]
            val d = hypot(b.x - a.x, b.y - a.y)
            if (d > max) max = d
        }
        return max
    }
}
