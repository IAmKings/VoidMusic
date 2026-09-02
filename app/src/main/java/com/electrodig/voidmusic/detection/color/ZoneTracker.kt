package com.electrodig.voidmusic.detection.color

import kotlin.math.hypot

/**
 * Gives transient contour detections stable IDs while an object remains in the
 * camera view.  A missing object is retained only for [ttlMs], so a later,
 * unrelated object cannot inherit an old pad cooldown.
 */
class ZoneTracker(
    private val ttlMs: Long = 500,
    private val minIou: Float = 0.2f,
    private val maxCenterDistance: Float = 0.14f
) {
    private data class Track(val id: Int, var zone: DrumZone, var lastSeenMs: Long)

    private val tracks = mutableMapOf<Int, Track>()
    private var nextId = 1

    fun update(detections: List<DrumZone>, timestampMs: Long): List<DrumZone> {
        tracks.entries.removeAll { (_, track) -> timestampMs - track.lastSeenMs > ttlMs }
        if (detections.isEmpty()) return emptyList()

        val matchedTracks = mutableSetOf<Int>()
        return detections.map { detection ->
            val match = tracks.values
                .asSequence()
                .filter { it.id !in matchedTracks && sameKind(it.zone, detection) }
                .map { track -> track to matchScore(track.zone, detection) }
                .filter { (_, score) -> score != null }
                .minByOrNull { (_, score) -> score!!.first }
                ?.first

            val track = match ?: Track(nextId++, detection, timestampMs).also { tracks[it.id] = it }
            matchedTracks += track.id
            val stable = detection.copy(id = track.id)
            track.zone = stable
            track.lastSeenMs = timestampMs
            stable
        }
    }

    private fun sameKind(left: DrumZone, right: DrumZone): Boolean =
        left.presetName == right.presetName && left.mappedPad == right.mappedPad

    /** Pair is `(lower-is-better score, overlap)`, or null when too distant. */
    private fun matchScore(previous: DrumZone, current: DrumZone): Pair<Float, Float>? {
        val iou = iou(previous.normalizedBox, current.normalizedBox)
        val distance = hypot(
            previous.normalizedCenter.x - current.normalizedCenter.x,
            previous.normalizedCenter.y - current.normalizedCenter.y
        )
        if (iou < minIou && distance > maxCenterDistance) return null
        return (1f - iou + distance) to iou
    }

    private fun iou(a: DrumZone.Rect, b: DrumZone.Rect): Float {
        val left = maxOf(a.left, b.left)
        val top = maxOf(a.top, b.top)
        val right = minOf(a.right, b.right)
        val bottom = minOf(a.bottom, b.bottom)
        val intersection = (right - left).coerceAtLeast(0f) * (bottom - top).coerceAtLeast(0f)
        val areaA = (a.right - a.left).coerceAtLeast(0f) * (a.bottom - a.top).coerceAtLeast(0f)
        val areaB = (b.right - b.left).coerceAtLeast(0f) * (b.bottom - b.top).coerceAtLeast(0f)
        return if (areaA + areaB <= intersection) 0f else intersection / (areaA + areaB - intersection)
    }
}
