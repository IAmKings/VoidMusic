package com.electrodig.voidmusic.detection.hit

import com.electrodig.voidmusic.detection.color.DrumPad

/**
 * A raw "something moved down fast" candidate produced by [HitDetector] from a
 * fingertip's trajectory. Not yet bound to a drum zone — the [HitArbiter]
 * decides whether it becomes a [TriggerEvent].
 *
 * @param point    fingertip position in normalised [0,1] coordinates
 * @param velocity downward speed in normalised units per second (always >= 0)
 * @param timestampMs frame timestamp
 */
data class HitCandidate(
    val point: Point,
    val velocity: Float,
    val timestampMs: Long
) {
    data class Point(val x: Float, val y: Float)
}

/**
 * The final, arbitered trigger ready to feed the audio engine (PRD §8.1
 * TriggerEvent). A single physical tap yields at most one of these.
 */
data class TriggerEvent(
    val pad: DrumPad,
    val zoneId: Int,
    val velocity: Float,       // 0..1, scales playback gain
    val timestampMs: Long
)
