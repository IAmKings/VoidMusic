package com.electrodig.voidmusic.detection.hit

import com.electrodig.voidmusic.detection.color.DrumZone
import com.electrodig.voidmusic.detection.hand.TimestampedHands

/** Immutable, normalized hit-test state published by the camera pipeline. */
data class HitSnapshot(
    val zones: List<DrumZone> = emptyList(),
    val zoneTimestampMs: Long = Long.MIN_VALUE
)

/**
 * Serial low-latency Tap-mode path that is safe to call directly from a
 * MediaPipe result callback. It deliberately does not depend on Compose or
 * preview coordinate mapping.
 */
class TapHitProcessor(
    private val detector: HitDetector,
    private val arbiter: HitArbiter,
    private val maxHandResultAgeMs: Long = HARD_MAX_HAND_RESULT_AGE_MS,
    private val maxZoneAgeMs: Long = MAX_ZONE_AGE_MS
) {
    private var estimatedHandResultAgeMs: Float? = null

    @Synchronized
    fun process(
        frame: TimestampedHands,
        consumedAtMs: Long,
        snapshot: HitSnapshot
    ): List<TriggerEvent> {
        // Direct callback consumption makes callback→consume nearly zero. The
        // meaningful freshness boundary is camera source→consume, including
        // inference latency; old landmarks must not produce a late note.
        val handResultAgeMs = consumedAtMs - frame.timestampMs
        if (!acceptHandResultAge(handResultAgeMs)) return emptyList()
        // A MediaPipe result may arrive after subsequent camera frames have
        // refreshed the cache. Validate the cache at consumption time, rather
        // than rejecting it simply because its source frame is newer.
        val zoneAgeMs = consumedAtMs - snapshot.zoneTimestampMs
        if (zoneAgeMs !in 0..maxZoneAgeMs) return emptyList()
        return arbiter.arbitrate(detector.update(frame.hands, frame.timestampMs), snapshot.zones)
    }

    /**
     * MediaPipe LIVE_STREAM drops superseded input, so a sustained inference
     * time above the ideal target is degraded latency, not an unbounded result
     * backlog. Track that device-specific baseline and allow a small jitter
     * margin, while retaining a hard ceiling against genuinely obsolete taps.
     */
    private fun acceptHandResultAge(ageMs: Long): Boolean {
        if (ageMs !in 0..maxHandResultAgeMs) return false

        val previous = estimatedHandResultAgeMs
        val estimate = if (previous == null) {
            ageMs.toFloat()
        } else {
            previous + AGE_ESTIMATE_ALPHA * (ageMs - previous)
        }
        estimatedHandResultAgeMs = estimate

        val minimumBudgetMs = minOf(TARGET_HAND_RESULT_AGE_MS, maxHandResultAgeMs)
        val adaptiveBudgetMs = (estimate + HAND_RESULT_JITTER_MARGIN_MS)
            .toLong()
            .coerceIn(minimumBudgetMs, maxHandResultAgeMs)
        return ageMs <= adaptiveBudgetMs
    }

    private companion object {
        const val TARGET_HAND_RESULT_AGE_MS = 140L
        const val HARD_MAX_HAND_RESULT_AGE_MS = 260L
        const val HAND_RESULT_JITTER_MARGIN_MS = 40L
        const val AGE_ESTIMATE_ALPHA = 0.25f
        const val MAX_ZONE_AGE_MS = 260L
    }
}
