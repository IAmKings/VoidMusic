package com.electrodig.voidmusic.performance

import com.electrodig.voidmusic.detection.color.DrumZone
import com.electrodig.voidmusic.detection.hit.MAX_HIT_ZONE_AGE_MS
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Adaptive segmentation cadence bounded by the TAP hit cache freshness budget.
 *
 * New or changing scenes use a short interval. Once tracked zones remain stable,
 * the interval expands to reduce OpenCV work, while retaining enough margin for
 * frame scheduling jitter and analysis work before TapHitProcessor's 260 ms
 * cache ceiling.
 */
internal class SegmentationCadence(
    analysisFrameCap: Int,
    private val stableRunsBeforeIdle: Int = DEFAULT_STABLE_RUNS,
    maxZoneAgeMs: Long = MAX_HIT_ZONE_AGE_MS,
    schedulingMarginMs: Long = SCHEDULING_MARGIN_MS
) {
    private data class ZoneSample(
        val id: Int,
        val presetName: String,
        val centerX: Float,
        val centerY: Float,
        val area: Float
    )

    private val effectiveFps = analysisFrameCap.takeIf { it > 0 } ?: DEFAULT_UNCAPPED_FPS
    internal val activeIntervalFrames = if (effectiveFps <= LOW_FPS_BOUNDARY) 1 else 2
    internal val idleIntervalFrames = (
        (maxZoneAgeMs - schedulingMarginMs).coerceAtLeast(1L) * effectiveFps / 1_000L
    ).toInt().coerceAtLeast(activeIntervalFrames)

    private var framesSinceSegmentation = Int.MAX_VALUE
    private var stableRuns = 0
    private var previousZones: List<ZoneSample>? = null
    private var invalidated = true

    @Synchronized
    fun shouldSegment(): Boolean {
        if (invalidated) {
            invalidated = false
            framesSinceSegmentation = 0
            return true
        }

        framesSinceSegmentation++
        val interval = if (stableRuns >= stableRunsBeforeIdle) {
            idleIntervalFrames
        } else {
            activeIntervalFrames
        }
        if (framesSinceSegmentation < interval) return false
        framesSinceSegmentation = 0
        return true
    }

    @Synchronized
    fun record(zones: List<DrumZone>) {
        val current = zones
            .map { zone ->
                ZoneSample(
                    id = zone.id,
                    presetName = zone.presetName,
                    centerX = zone.normalizedCenter.x,
                    centerY = zone.normalizedCenter.y,
                    area = zone.area
                )
            }
            .sortedBy(ZoneSample::id)
        stableRuns = if (previousZones?.isStableWith(current) == true) stableRuns + 1 else 0
        previousZones = current
    }

    /** Forces the very next frame to segment after mode or HSV configuration changes. */
    @Synchronized
    fun invalidate() {
        invalidated = true
        stableRuns = 0
    }

    @Synchronized
    fun reset() {
        framesSinceSegmentation = Int.MAX_VALUE
        stableRuns = 0
        previousZones = null
        invalidated = true
    }

    private fun List<ZoneSample>.isStableWith(other: List<ZoneSample>): Boolean {
        if (size != other.size) return false
        return indices.all { index ->
            val before = this[index]
            val after = other[index]
            before.id == after.id &&
                before.presetName == after.presetName &&
                hypot(before.centerX - after.centerX, before.centerY - after.centerY) <= MAX_CENTER_DRIFT &&
                abs(before.area - after.area) <= maxOf(before.area, after.area, 1f) * MAX_AREA_DRIFT_FRACTION
        }
    }

    private companion object {
        const val DEFAULT_STABLE_RUNS = 4
        const val DEFAULT_UNCAPPED_FPS = 30
        const val LOW_FPS_BOUNDARY = 18
        const val SCHEDULING_MARGIN_MS = 60L
        const val MAX_CENTER_DRIFT = 0.025f
        const val MAX_AREA_DRIFT_FRACTION = 0.20f
    }
}
