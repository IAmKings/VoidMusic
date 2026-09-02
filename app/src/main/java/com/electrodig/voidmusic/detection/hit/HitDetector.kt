package com.electrodig.voidmusic.detection.hit

import com.electrodig.voidmusic.detection.hand.Hand
import com.electrodig.voidmusic.detection.hand.NormalizedLandmark
/**
 * Detects "tap" gestures from fingertip trajectories (PRD §9.4).
 *
 * A tap is a downward fingertip motion above a real-time speed threshold.
 * Coordinates are display-oriented, so increasing Y means moving downward.
 * Stateful tracks follow the nearest recent fingertip instead of trusting the
 * MediaPipe list order, which can change as two hands cross.
 */
class HitDetector(
    /** Minimum downward speed in normalised display units per second. */
    private val velocityThreshold: Float = 0.6f,
    /** Minimum ms between two candidates from the same fingertip (debounce). */
    private val cooldownMs: Long = 250,
    /** Retain a missing fingertip briefly so result ordering cannot reset cooldown. */
    private val trackerTtlMs: Long = 500,
    /** A tracker cannot jump farther than this between adjacent frames. */
    private val maxMatchDistance: Float = 0.25f
) {
    private data class Tracker(
        val id: Int,
        var lastSample: Sample? = null,
        var lastCandidateMs: Long = Long.MIN_VALUE / 2
    )

    private data class Sample(val x: Float, val y: Float, val ts: Long)

    private val trackers = mutableMapOf<Int, Tracker>()
    private var nextTrackerId = 1

    fun update(hands: List<Hand>, timestampMs: Long): List<HitCandidate> {
        trackers.entries.removeAll { (_, tracker) ->
            val last = tracker.lastSample ?: return@removeAll true
            timestampMs - last.ts > trackerTtlMs
        }
        if (hands.isEmpty()) return emptyList()

        val out = ArrayList<HitCandidate>(hands.size)
        val claimed = mutableSetOf<Int>()
        hands.forEach { hand ->
            val tip = hand.fingertip
            val tracker = trackers.values
                .asSequence()
                .filter { it.id !in claimed }
                .mapNotNull { candidate ->
                    val last = candidate.lastSample ?: return@mapNotNull null
                    val dx = last.x - tip.x
                    val dy = last.y - tip.y
                    val distanceSquared = dx * dx + dy * dy
                    if (distanceSquared <= maxMatchDistance * maxMatchDistance) candidate to distanceSquared else null
                }
                .minByOrNull { it.second }
                ?.first
                ?: Tracker(nextTrackerId++).also { trackers[it.id] = it }
            claimed += tracker.id

            val previous = tracker.lastSample
            val now = Sample(tip.x, tip.y, timestampMs)
            tracker.lastSample = now
            if (previous == null) return@forEach

            val dtMs = timestampMs - previous.ts
            if (dtMs <= 0L) return@forEach
            val downwardSpeed = (tip.y - previous.y) * 1_000f / dtMs
            val cooled = timestampMs - tracker.lastCandidateMs >= cooldownMs
            if (downwardSpeed >= velocityThreshold && cooled) {
                out += HitCandidate(
                    point = HitCandidate.Point(tip.x, tip.y),
                    velocity = downwardSpeed,
                    timestampMs = timestampMs
                )
                tracker.lastCandidateMs = timestampMs
            }
        }
        return out
    }
}
