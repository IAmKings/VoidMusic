package com.electrodig.objectdrumstudio.camera

import android.content.Context
import android.util.Log
import android.util.Size
import android.view.Surface
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/**
 * Lifecycle-aware wrapper around CameraX that binds a [Preview] and an
 * [ImageAnalysis] use case. Preview feeds the viewfinder; ImageAnalysis pipes
 * each frame to an [analyzer] (the hand tracker in M1, plus color segmentation
 * in later milestones).
 *
 * Per PRD §4.2 the rear camera is required; we fall back to any available
 * camera if the back lens is missing.
 *
 * @param targetResolution resolution for the analysis pipeline (null = CameraX default).
 * @param analyzer called on a background thread for every frame; it owns closing
 *                 the [ImageProxy].
 */
class CameraModule(
    private val context: Context,
    private val targetResolution: Size? = null,
    private val analyzer: ImageAnalysis.Analyzer? = null
) {

    private val mainExecutor: Executor by lazy {
        ContextCompat.getMainExecutor(context)
    }

    /** Dedicated single-thread executor for image analysis (keeps UI smooth). */
    private val analysisExecutor: Executor by lazy {
        Executors.newSingleThreadExecutor()
    }

    private var provider: ProcessCameraProvider? = null
    /** Bound use cases, retained so targetRotation can be updated without rebinding. */
    private var preview: Preview? = null
    private var analysis: ImageAnalysis? = null

    /** Whether a back camera is available on this device. */
    fun hasBackCamera(): Boolean = try {
        val future = ProcessCameraProvider.getInstance(context)
        val p = future.get()
        p.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA)
    } catch (e: Throwable) {
        false
    }

    /**
     * Binds the preview + analysis use cases to [previewView] under
     * [lifecycleOwner]. Call after the CAMERA permission is granted.
     *
     * The use cases' `targetRotation` is seeded from the preview display's
     * current rotation so `ImageProxy.imageInfo.rotationDegrees` reflects the
     * actual device orientation from the first frame. Use [updateTargetRotation]
     * when the display rotates later — no rebind required.
     */
    fun startPreview(lifecycleOwner: LifecycleOwner, previewView: PreviewView) {
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener(
            {
                try {
                    val p = future.get()
                    provider = p
                    p.unbindAll()

                    val targetRotation = previewView.display?.rotation ?: Surface.ROTATION_0

                    val previewBuilder = Preview.Builder()
                        .setTargetRotation(targetRotation)
                    if (targetResolution != null) {
                        previewBuilder.setTargetResolution(targetResolution)
                    }

                    val previewUseCase = previewBuilder
                        .build()
                        .also { it.surfaceProvider = previewView.surfaceProvider }
                    preview = previewUseCase

                    val selector = selectCamera(p)

                    val useCases: MutableList<androidx.camera.core.UseCase> = mutableListOf(previewUseCase)
                    if (analyzer != null) {
                        val analysisBuilder = ImageAnalysis.Builder()
                            // Backpressure drops older frames so the analyser is never
                            // overwhelmed if inference falls behind real-time.
                            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                            .setOutputImageFormat(
                                ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888
                            )
                            .setTargetRotation(targetRotation)
                        if (targetResolution != null) {
                            analysisBuilder.setTargetResolution(targetResolution)
                        }
                        val analysisUseCase = analysisBuilder
                            .build()
                            .also { it.setAnalyzer(analysisExecutor, analyzer) }
                        analysis = analysisUseCase
                        useCases += analysisUseCase
                    }

                    p.bindToLifecycle(lifecycleOwner, selector, *useCases.toTypedArray())
                    Log.i(
                        TAG,
                        "Bound preview%s to %s camera%s @ rotation %d".format(
                            if (analyzer != null) " + analysis" else "",
                            if (selector == CameraSelector.DEFAULT_BACK_CAMERA) "back" else "front",
                            if (targetResolution != null) " @ ${targetResolution.width}x${targetResolution.height}" else "",
                            targetRotation
                        )
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to bind camera use cases", e)
                }
            },
            mainExecutor
        )
    }

    /**
     * Update the bound use cases' `targetRotation` to match a new display
     * orientation (e.g. after the device is rotated). CameraX applies this on
     * the next frame — no unbind/rebind needed, so the viewfinder stays live.
     */
    fun updateTargetRotation(rotation: Int) {
        preview?.targetRotation = rotation
        analysis?.targetRotation = rotation
    }

    /** Releases camera resources (PRD §4.4: background = release). */
    fun stop() {
        provider?.unbindAll()
        preview = null
        analysis = null
    }

    private fun selectCamera(p: ProcessCameraProvider): CameraSelector =
        if (p.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA)) {
            CameraSelector.DEFAULT_BACK_CAMERA
        } else {
            CameraSelector.DEFAULT_FRONT_CAMERA
        }

    companion object {
        private const val TAG = "CameraModule"
    }
}
