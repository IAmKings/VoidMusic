package com.electrodig.objectdrumstudio.camera

import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.SystemClock
import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy

/**
 * A single CameraX [ImageAnalysis.Analyzer] that fans each frame out to
 * multiple consumers. This lets the hand tracker and colour segmenter share one
 * ImageAnalysis use case (CameraX binds only one analyzer per use case).
 *
 * The frame is converted to a Bitmap once and handed to every consumer; the
 * router itself closes the [ImageProxy] in a `finally` block so a consumer
 * that throws cannot leak the proxy and stall the camera pipeline
 * (STRATEGY_KEEP_ONLY_LATEST).
 *
 * @param onFpsUpdate optional callback invoked every ~1 second with the
 *                    smoothed analysis pipeline FPS.
 */
class FrameRouter(
    private val bitmapConsumers: List<(android.graphics.Bitmap, ImageProxy) -> Unit>,
    private val imageProxyConsumer: (ImageProxy) -> Unit,
    private val onFpsUpdate: ((Float) -> Unit)? = null
) : ImageAnalysis.Analyzer {

    private var frameCount = 0
    private var lastFpsLogMs = SystemClock.elapsedRealtime()

    override fun analyze(image: ImageProxy) {
        val raw = runCatching { image.toBitmap() }.getOrElse {
            Log.w(TAG, "Frame → bitmap failed", it)
            image.close()
            return
        }
        // Rotate the sensor-space bitmap into display orientation before fanning
        // it out. CameraX's ImageProxy is in the sensor's native orientation
        // (typically landscape); imageInfo.rotationDegrees is how much the
        // consumer must rotate to match the target display. Applying it here
        // once means every downstream consumer (hand tracker, colour segmenter,
        // hit detector, overlays) receives a bitmap already in screen space, so
        // their normalised [0,1] coordinates line up with the viewfinder without
        // each one re-deriving the rotation.
        val bitmap = rotateBitmapForDisplay(raw, image.imageInfo.rotationDegrees)
        try {
            // Isolate each consumer so one throwing (e.g. an OpenCV op in the
            // colour/hit path) cannot abort the loop and leak the ImageProxy —
            // a leaked proxy under STRATEGY_KEEP_ONLY_LATEST stalls the camera
            // and freezes the entire detection pipeline (incl. hand tracking).
            bitmapConsumers.forEachIndexed { i, consumer ->
                runCatching { consumer(bitmap, image) }
                    .onFailure { Log.w(TAG, "consumer[$i] threw on frame, skipping", it) }
            }
        } finally {
            imageProxyConsumer(image)
        }

        // FPS tracking.
        frameCount++
        val now = SystemClock.elapsedRealtime()
        val elapsed = now - lastFpsLogMs
        if (elapsed >= 1000L) {
            val fps = frameCount * 1000f / elapsed
            onFpsUpdate?.invoke(fps)
            frameCount = 0
            lastFpsLogMs = now
        }
    }

    private companion object { const val TAG = "FrameRouter" }
}

/**
 * Normalise [degrees] into the canonical clockwise rotation in [0,360), where
 * 0 means "no rotation needed". Extracted as a pure function so the
 * short-circuit boundary is unit-testable without Android framework classes
 * (Bitmap/Matrix are not available in pure JVM tests).
 */
internal fun normalisedRotationDegrees(degrees: Int): Int = ((degrees % 360) + 360) % 360

/**
 * Rotate [bitmap] by [degrees] (clockwise) to match the display orientation.
 *
 * CameraX delivers frames in the sensor's native orientation; the consumer is
 * told how many degrees to rotate via `ImageProxy.imageInfo.rotationDegrees`.
 * Returns the original bitmap unchanged when no rotation is needed (0/360/...)
 * so a target already matching the sensor pays zero allocation cost.
 */
internal fun rotateBitmapForDisplay(bitmap: Bitmap, degrees: Int): Bitmap {
    val normalized = normalisedRotationDegrees(degrees)
    if (normalized == 0) return bitmap
    val matrix = Matrix().apply { postRotate(normalized.toFloat()) }
    return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
}
