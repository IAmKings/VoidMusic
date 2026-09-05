package com.electrodig.voidmusic.detection.color

import android.graphics.Bitmap
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.Point as CvPoint
import org.opencv.core.Rect as CvRect
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

/**
 * HSV colour segmentation + connected-component extraction (PRD §9.1).
 *
 * Pipeline per preset:
 *   1. RGBA bitmap → HSV
 *   2. [Imgproc.inRange] with the preset's [HsvRange] (handles hue wrap)
 *   3. optional morphological open+close to remove speckle
 *   4. [Imgproc.findContours] to get connected blobs
 *   5. filter by area / aspect ratio → emit a [DrumZone] per surviving blob
 *
 * Designed to be called from the CameraX ImageAnalysis thread. The RGBA, HSV,
 * and threshold Mats are retained and reused across frames to avoid Java pixel
 * array copies and native allocation churn on the camera hot path.
 *
 * @param downsample scale factor applied to input bitmap before processing
 *                   (1.0 = full resolution, 0.5 = half, 0.25 = quarter).
 */
class ColorSegmenter(
    private val downsample: Float = 1.0f
) : AutoCloseable {

    /** Reusable HSV scalar buffers (lower/upper bounds). */
    private val lower = Scalar(0.0, 0.0, 0.0, 0.0)
    private val upper = Scalar(0.0, 0.0, 0.0, 0.0)
    private var morphologyKernel: Mat? = null
    // MainScreen creates this segmenter before its LaunchedEffect loads OpenCV.
    // Native Mats must therefore be allocated on the first actual segmentation,
    // never in the Kotlin constructor.
    private var rgba: Mat? = null
    private var scaledRgba: Mat? = null
    private var hsv: Mat? = null
    private var hierarchy: Mat? = null
    private val contours = ArrayList<MatOfPoint>()
    private val thresholdWorkspaces = ArrayList<ThresholdWorkspace>()
    private var closed = false

    /**
     * Segment [bitmap] using [config], returning one [DrumZone] per accepted blob
     * across all presets. [frameId] seeds zone ids so they stay stable within a
     * single frame (stable ids across frames are the PadTracker's job in M3).
     */
    @Synchronized
    fun segment(bitmap: Bitmap, config: DetectionConfig, frameId: Long = 0): List<DrumZone> {
        check(!closed) { "ColorSegmenter is closed" }
        if (bitmap.width == 0 || bitmap.height == 0) return emptyList()

        val width = if (downsample < 1.0f) {
            (bitmap.width * downsample).toInt().coerceAtLeast(1)
        } else {
            bitmap.width
        }
        val height = if (downsample < 1.0f) {
            (bitmap.height * downsample).toInt().coerceAtLeast(1)
        } else {
            bitmap.height
        }

        val frameArea = width.toFloat() * height
        val minArea = frameArea * config.minAreaFraction
        val maxArea = frameArea * config.maxAreaFraction
        val rgbaMat = rgba ?: Mat().also { rgba = it }
        val hsvMat = hsv ?: Mat().also { hsv = it }
        Utils.bitmapToMat(bitmap, rgbaMat)
        val workingRgba = if (downsample < 1.0f) {
            val target = scaledRgba ?: Mat().also { scaledRgba = it }
            Imgproc.resize(
                rgbaMat,
                target,
                Size(width.toDouble(), height.toDouble()),
                0.0,
                0.0,
                Imgproc.INTER_LINEAR
            )
            target
        } else {
            rgbaMat
        }
        // OpenCV's RGB→HSV converter accepts 3- and 4-channel RGB(A) input;
        // alpha is ignored, so this remains a single direct RGBA bitmap→HSV step.
        Imgproc.cvtColor(workingRgba, hsvMat, Imgproc.COLOR_RGB2HSV)
        val zones = ArrayList<DrumZone>(8)
        var zoneId = (frameId * 100).toInt()
        val kernel = if (config.morphEnabled) morphologyKernel() else null

        ensureThresholdWorkspaces(config.presets)
        for ((index, preset) in config.presets.withIndex()) {
            val mask = threshold(hsvMat, preset.range, thresholdWorkspaces[index])
            if (kernel != null) {
                Imgproc.morphologyEx(mask, mask, Imgproc.MORPH_OPEN, kernel)
                Imgproc.morphologyEx(mask, mask, Imgproc.MORPH_CLOSE, kernel)
            }

            releaseContours()
            val hierarchyMat = hierarchy ?: Mat().also { hierarchy = it }
            try {
                Imgproc.findContours(
                    mask, contours, hierarchyMat,
                    Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE
                )
                for (contour in contours) {
                    val area = Imgproc.contourArea(contour)
                    if (area < minArea || area > maxArea) continue
                    val cvRect = Imgproc.boundingRect(contour)
                    val aspect = cvRect.width.toFloat() / cvRect.height.toFloat().coerceAtLeast(1f)
                    if (aspect !in config.aspectRatio) continue

                    val moment = Imgproc.moments(contour)
                    if (moment.m00 == 0.0) continue
                    val cx = (moment.m10 / moment.m00).toFloat()
                    val cy = (moment.m01 / moment.m00).toFloat()
                    zones += DrumZone(
                        id = zoneId++,
                        center = DrumZone.Point(cx, cy),
                        area = area.toFloat(),
                        width = cvRect.width,
                        height = cvRect.height,
                        presetName = preset.name,
                        mappedPad = preset.mappedPad,
                        normalizedCenter = DrumZone.Point(cx / width, cy / height),
                        normalizedBox = DrumZone.Rect(
                            cvRect.x.toFloat() / width,
                            cvRect.y.toFloat() / height,
                            (cvRect.x + cvRect.width).toFloat() / width,
                            (cvRect.y + cvRect.height).toFloat() / height
                        )
                    )
                }
            } finally {
                releaseContours()
            }
        }
        return zones
    }

    private fun morphologyKernel(): Mat = morphologyKernel
        ?: Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(5.0, 5.0))
            .also { morphologyKernel = it }

    @Synchronized
    override fun close() {
        if (closed) return
        morphologyKernel?.release()
        morphologyKernel = null
        thresholdWorkspaces.forEach(ThresholdWorkspace::release)
        thresholdWorkspaces.clear()
        releaseContours()
        hierarchy?.release()
        hierarchy = null
        rgba?.release()
        rgba = null
        scaledRgba?.release()
        scaledRgba = null
        hsv?.release()
        hsv = null
        closed = true
    }

    /** Apply [range] to an HSV Mat, accounting for hue wrap-around at the 0/180 boundary. */
    private fun threshold(hsv: Mat, range: HsvRange, workspace: ThresholdWorkspace): Mat {
        val base = workspace.base
        if (range.hMin > range.hMax) {
            // Hue wraps past 180 (e.g. red hMin=165..hMax=15 straddles the 0/180
            // boundary). Match TWO segments: hMin..180 and 0..hMax.
            // Segment 1: high end (e.g. 165..180).
            setScalar(lower, range.hMin, range.sMin, range.vMin)
            setScalar(upper, 180, range.sMax, range.vMax)
            Core.inRange(hsv, lower, upper, base)
            // Segment 2: low end (e.g. 0..15), OR-ed in.
            setScalar(lower, 0, range.sMin, range.vMin)
            setScalar(upper, range.hMax, range.sMax, range.vMax)
            val wrap = workspace.requireWrap()
            Core.inRange(hsv, lower, upper, wrap)
            Core.bitwise_or(base, wrap, base)
        } else {
            // Normal single segment.
            setScalar(lower, range.hMin, range.sMin, range.vMin)
            setScalar(upper, range.hMax, range.sMax, range.vMax)
            Core.inRange(hsv, lower, upper, base)
        }
        return base
    }

    private fun ensureThresholdWorkspaces(presets: List<HsvPreset>) {
        while (thresholdWorkspaces.size < presets.size) {
            thresholdWorkspaces += ThresholdWorkspace()
        }
        while (thresholdWorkspaces.size > presets.size) {
            thresholdWorkspaces.removeAt(thresholdWorkspaces.lastIndex).release()
        }
        presets.forEachIndexed { index, preset ->
            thresholdWorkspaces[index].setWrapRequired(preset.range.hMin > preset.range.hMax)
        }
    }

    private fun setScalar(target: Scalar, h: Int, s: Int, v: Int) {
        target.`val`[0] = h.toDouble()
        target.`val`[1] = s.toDouble()
        target.`val`[2] = v.toDouble()
    }

    private fun releaseContours() {
        contours.forEach(MatOfPoint::release)
        contours.clear()
    }

    private class ThresholdWorkspace {
        val base = Mat()
        private var wrap: Mat? = null

        fun setWrapRequired(required: Boolean) {
            if (required && wrap == null) wrap = Mat()
            if (!required) {
                wrap?.release()
                wrap = null
            }
        }

        fun requireWrap(): Mat = requireNotNull(wrap)

        fun release() {
            base.release()
            wrap?.release()
            wrap = null
        }
    }
}
