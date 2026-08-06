package com.electrodig.objectdrumstudio.detection.hit

import com.electrodig.objectdrumstudio.detection.color.DrumZone

/**
 * Maps a fingertip landing point to the nearest drum zone (PRD F5.6 PadTracker).
 *
 * Zones carry a normalised bounding box and centre; a candidate is considered
 * "on" a zone if its point falls inside the (slightly expanded) box, or within
 * [nearRadius] of the centre. Returns the closest match or null.
 */
class PadTracker(
    /** Extra margin added around each zone's box (fraction of width/height). */
    private val boxExpansion: Float = 0.12f,
    /** If the point isn't inside a box, accept being this close to a centre. */
    private val nearRadius: Float = 0.15f
) {
    fun locate(zones: List<DrumZone>, point: HitCandidate.Point): DrumZone? {
        if (zones.isEmpty()) return null

        // 1) Box containment (expanded).
        for (zone in zones) {
            val b = zone.normalizedBox
            if (point.x in (b.left - boxExpansion)..(b.right + boxExpansion) &&
                point.y in (b.top - boxExpansion)..(b.bottom + boxExpansion)
            ) {
                return zone
            }
        }
        // 2) Nearest centre within nearRadius.
        var best: DrumZone? = null
        var bestDist = Float.MAX_VALUE
        for (zone in zones) {
            val dx = zone.normalizedCenter.x - point.x
            val dy = zone.normalizedCenter.y - point.y
            val d = dx * dx + dy * dy
            if (d < bestDist) { bestDist = d; best = zone }
        }
        val radiusSq = nearRadius * nearRadius
        return if (bestDist <= radiusSq) best else null
    }
}
