package com.electrodig.voidmusic.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VisionMetricsRecorderTest {

    @Test
    fun `emits result rate and p50 p95 values once per interval`() {
        var nowMs = 0L
        val recorder = VisionMetricsRecorder(
            snapshotIntervalMs = 1_000L,
            clockMs = { nowMs },
            sampleCapacity = 8
        )

        assertNull(recorder.recordHandResult(40))
        recorder.recordHandCallbackToConsume(2)
        recorder.recordHandCallbackToConsume(8)
        recorder.recordSegmentation(10)
        recorder.recordSegmentation(20)
        recorder.recordSegmentation(30)
        recorder.recordZoneCacheAge(5)
        recorder.recordZoneCacheAge(15)
        recorder.recordHitToAudioSubmit(7)
        recorder.recordHitToAudioSubmit(27)

        nowMs = 1_000L
        val metrics = recorder.recordHandResult(60)

        requireNotNull(metrics)
        assertEquals(2f, metrics.handResultFps, 0.001f)
        assertEquals(40L, metrics.captureToHandCallbackP50Ms)
        assertEquals(60L, metrics.captureToHandCallbackP95Ms)
        assertEquals(2L, metrics.handCallbackToConsumeP50Ms)
        assertEquals(8L, metrics.handCallbackToConsumeP95Ms)
        assertEquals(20L, metrics.segmentationP50Ms)
        assertEquals(30L, metrics.segmentationP95Ms)
        assertEquals(5L, metrics.zoneCacheAgeP50Ms)
        assertEquals(15L, metrics.zoneCacheAgeP95Ms)
        assertEquals(7L, metrics.hitToAudioSubmitP50Ms)
        assertEquals(27L, metrics.hitToAudioSubmitP95Ms)
    }

    @Test
    fun `keeps a bounded sample window and ignores negative durations`() {
        var nowMs = 0L
        val recorder = VisionMetricsRecorder(
            snapshotIntervalMs = 1_000L,
            clockMs = { nowMs },
            sampleCapacity = 3
        )

        recorder.recordHandResult(-5)
        recorder.recordSegmentation(-5)
        recorder.recordSegmentation(10)
        recorder.recordSegmentation(20)
        recorder.recordSegmentation(30)

        nowMs = 1_000L
        val metrics = recorder.recordHandResult(10)

        requireNotNull(metrics)
        assertEquals(20L, metrics.segmentationP50Ms)
        assertEquals(30L, metrics.segmentationP95Ms)
    }
}
