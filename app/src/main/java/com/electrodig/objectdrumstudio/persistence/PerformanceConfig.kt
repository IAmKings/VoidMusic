package com.electrodig.objectdrumstudio.persistence

import android.util.Size
import com.google.mediapipe.tasks.core.Delegate

/**
 * Runtime parameters derived from [PerformanceLevel] (PRD M6 R6.1 / R6.2).
 *
 * Wired into CameraModule, HandTracker, and ColorSegmenter to control
 * resolution, frame rate, inference settings, and processing quality.
 */
data class PerformanceConfig(
    val cameraResolution: Size,
    /** Analysis frames-per-second cap. 0 = no cap. */
    val analysisFrameCap: Int,
    val mediaPipeDelegate: Delegate,
    val maxHands: Int,
    /** Color segmentation downsample factor (1.0 = full, 0.5 = half, 0.25 = quarter). */
    val colorDownsample: Float
) {
    companion object {
        fun forLevel(level: PerformanceLevel): PerformanceConfig = when (level) {
            PerformanceLevel.HIGH -> PerformanceConfig(
                // Analysis resolution need only exceed the MediaPipe model input
                // (192×192 detector / 224×224 landmarker) with headroom for accuracy.
                // 640×480 is ~6× the model input — plenty, and avoids the per-frame
                // copy/rotate/wrap cost of 1080p that MediaPipe discards internally.
                // (Preview stays crisp via its own higher-resolution use case.)
                cameraResolution = Size(640, 480),
                analysisFrameCap = 30,
                mediaPipeDelegate = Delegate.GPU,
                maxHands = 2,
                colorDownsample = 1.0f
            )
            PerformanceLevel.MEDIUM -> PerformanceConfig(
                cameraResolution = Size(640, 480),
                analysisFrameCap = 20,
                mediaPipeDelegate = Delegate.GPU,
                maxHands = 2,
                colorDownsample = 0.5f
            )
            PerformanceLevel.LOW -> PerformanceConfig(
                cameraResolution = Size(480, 360),
                analysisFrameCap = 15,
                mediaPipeDelegate = Delegate.CPU,
                maxHands = 1,
                colorDownsample = 0.25f
            )
        }
    }
}
