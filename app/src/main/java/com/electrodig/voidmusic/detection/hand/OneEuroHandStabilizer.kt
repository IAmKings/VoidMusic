package com.electrodig.voidmusic.detection.hand

import android.os.SystemClock

/**
 * Smooths hand landmarks using a [OneEuroFilter] per coordinate, per hand.
 *
 * Hands are keyed by their stable list index within a frame (the detection
 * layer already returns them in a consistent order for a single hand). When the
 * hand count changes the filter bank is rebuilt to avoid cross-hand bleed.
 *
 * Replaces [IdentityHandStabilizer] from M1 once hit detection is wired (M3),
 * because jitter would otherwise cause false taps.
 */
class OneEuroHandStabilizer(
    private val minCutoff: Float = 1.5f,
    private val beta: Float = 0.05f,
    private val clock: () -> Long = { SystemClock.elapsedRealtime() }
) : HandStabilizer {

    private data class CoordFilters(
        val x: OneEuroFilter,
        val y: OneEuroFilter,
        val z: OneEuroFilter
    )

    // [handIndex][landmarkIndex] -> filters
    private var bank: List<List<CoordFilters>> = emptyList()

    override fun smooth(hands: List<Hand>): List<Hand> {
        if (hands.isEmpty()) {
            bank = emptyList()
            return hands
        }
        val ts = clock()

        if (bank.size != hands.size) {
            bank = List(hands.size) {
                List(NUM_LANDMARKS) {
                    CoordFilters(
                        OneEuroFilter(minCutoff, beta),
                        OneEuroFilter(minCutoff, beta),
                        OneEuroFilter(minCutoff, beta)
                    )
                }
            }
        }

        return hands.mapIndexed { h, hand ->
            val smoothed = hand.landmarks.mapIndexed { l, lm ->
                val f = bank[h][l]
                NormalizedLandmark(
                    f.x.filter(lm.x, ts),
                    f.y.filter(lm.y, ts),
                    f.z.filter(lm.z, ts)
                )
            }
            hand.copy(landmarks = smoothed)
        }
    }

    companion object { private const val NUM_LANDMARKS = 21 }
}
