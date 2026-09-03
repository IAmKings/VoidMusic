package com.electrodig.voidmusic.camera

import android.os.SystemClock
import kotlin.math.ceil

/**
 * Low-frequency observability data for the real-time vision path. Values stay
 * at -1 until their corresponding stage has produced a sample.
 *
 * [hitToAudioSubmitP50Ms] measures CameraX source-frame time to the return of
 * [DrumEngine.trigger]'s submission call. Speaker-output latency additionally
 * depends on the device audio path and requires external acoustic measurement.
 */
data class VisionMetrics(
    val handResultFps: Float = -1f,
    val segmentationP50Ms: Long = -1L,
    val segmentationP95Ms: Long = -1L,
    val zoneCacheAgeP50Ms: Long = -1L,
    val zoneCacheAgeP95Ms: Long = -1L,
    val hitToAudioSubmitP50Ms: Long = -1L,
    val hitToAudioSubmitP95Ms: Long = -1L
)

/**
 * Bounded, synchronized metric collector shared by the camera executor and
 * MediaPipe's result callback thread. It only emits a UI snapshot once per
 * [snapshotIntervalMs], keeping the per-frame path allocation-free.
 */
class VisionMetricsRecorder(
    private val snapshotIntervalMs: Long = SNAPSHOT_INTERVAL_MS,
    private val clockMs: () -> Long = SystemClock::elapsedRealtime,
    sampleCapacity: Int = SAMPLE_CAPACITY
) {
    private val segmentationMs = SampleWindow(sampleCapacity)
    private val zoneCacheAgeMs = SampleWindow(sampleCapacity)
    private val hitToAudioSubmitMs = SampleWindow(sampleCapacity)

    private var lastSnapshotMs: Long? = null
    private var handResultCount = 0
    private var latest = VisionMetrics()

    init {
        require(snapshotIntervalMs > 0) { "snapshotIntervalMs must be positive" }
    }

    @Synchronized
    fun recordHandResult(): VisionMetrics? {
        handResultCount++
        return snapshotIfDue(clockMs())
    }

    @Synchronized
    fun recordSegmentation(durationMs: Long): VisionMetrics? {
        segmentationMs.add(durationMs)
        return snapshotIfDue(clockMs())
    }

    @Synchronized
    fun recordZoneCacheAge(ageMs: Long): VisionMetrics? {
        zoneCacheAgeMs.add(ageMs)
        return snapshotIfDue(clockMs())
    }

    @Synchronized
    fun recordHitToAudioSubmit(latencyMs: Long): VisionMetrics? {
        hitToAudioSubmitMs.add(latencyMs)
        return snapshotIfDue(clockMs())
    }

    private fun snapshotIfDue(nowMs: Long): VisionMetrics? {
        val previousSnapshotMs = lastSnapshotMs
        if (previousSnapshotMs == null) {
            lastSnapshotMs = nowMs
            return null
        }
        val elapsedMs = nowMs - previousSnapshotMs
        if (elapsedMs < snapshotIntervalMs) return null

        latest = VisionMetrics(
            handResultFps = handResultCount * 1_000f / elapsedMs,
            segmentationP50Ms = segmentationMs.percentile(0.50),
            segmentationP95Ms = segmentationMs.percentile(0.95),
            zoneCacheAgeP50Ms = zoneCacheAgeMs.percentile(0.50),
            zoneCacheAgeP95Ms = zoneCacheAgeMs.percentile(0.95),
            hitToAudioSubmitP50Ms = hitToAudioSubmitMs.percentile(0.50),
            hitToAudioSubmitP95Ms = hitToAudioSubmitMs.percentile(0.95)
        )
        handResultCount = 0
        lastSnapshotMs = nowMs
        return latest
    }

    private class SampleWindow(private val capacity: Int) {
        private val values = LongArray(capacity)
        private var nextIndex = 0
        private var count = 0

        init {
            require(capacity > 0) { "sampleCapacity must be positive" }
        }

        fun add(value: Long) {
            values[nextIndex] = value.coerceAtLeast(0L)
            nextIndex = (nextIndex + 1) % capacity
            if (count < capacity) count++
        }

        fun percentile(percentile: Double): Long {
            if (count == 0) return -1L
            val sorted = values.copyOf(count)
            sorted.sort()
            val index = (ceil(percentile * count).toInt() - 1).coerceIn(0, count - 1)
            return sorted[index]
        }
    }

    private companion object {
        const val SNAPSHOT_INTERVAL_MS = 1_000L
        const val SAMPLE_CAPACITY = 120
    }
}
