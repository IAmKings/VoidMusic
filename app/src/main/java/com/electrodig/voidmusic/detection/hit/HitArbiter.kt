package com.electrodig.voidmusic.detection.hit

import com.electrodig.voidmusic.detection.color.DrumZone

/**
 * Resolves [HitCandidate]s into at-most-one [TriggerEvent] per zone per frame
 * (PRD §9.4 HitArbiter). Handles:
 *  - dropping candidates that don't land on any zone (via [padTracker]),
 *  - suppressing re-triggers of the same zone within [retriggerCooldownMs],
 *  - mapping velocity to a 0..1 playback gain.
 */
class HitArbiter(
    private val padTracker: PadTracker = PadTracker(),
    /** Min ms between two triggers on the SAME zone. */
    private val retriggerCooldownMs: Long = 90
) {
    // Last trigger timestamp per zone id.
    private val lastTrigger = mutableMapOf<Int, Long>()

    /**
     * @param candidates from [HitDetector] this frame
     * @param zones      currently visible drum zones
     * @return           resolved trigger events (0..n)
     */
    fun arbitrate(
        candidates: List<HitCandidate>,
        zones: List<DrumZone>
    ): List<TriggerEvent> {
        if (candidates.isEmpty() || zones.isEmpty()) return emptyList()

        val out = ArrayList<TriggerEvent>(candidates.size)
        for (c in candidates) {
            val zone = padTracker.locate(zones, c.point) ?: continue
            val now = c.timestampMs
            // A zone we've never triggered has no entry — treat it as fully cooled
            // so the first hit always lands (a default of 0 would suppress frame 0).
            val last = lastTrigger[zone.id]
            if (last != null && now - last < retriggerCooldownMs) continue

            lastTrigger[zone.id] = now
            out += TriggerEvent(
                pad = zone.mappedPad,
                zoneId = zone.id,
                velocity = mapVelocity(c.velocity),
                timestampMs = now
            )
        }
        return out
    }

    /** Map raw downward speed to a 0..1 gain with soft saturation. */
    private fun mapVelocity(speed: Float): Float {
        // ~2.0 units/sec → 0.5, ~5.0 → ~0.9, saturates near 1.0.
        return (speed / (speed + 1.5f)).coerceIn(0.25f, 1.0f)
    }
}
