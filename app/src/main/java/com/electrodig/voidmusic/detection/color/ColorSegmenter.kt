package com.electrodig.voidmusic.detection.color

import android.graphics.Bitmap
import android.graphics.Matrix
import org.opencv.core.Core
import org.opencv.core.CvType
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
 *   1. RGBA bitmap → BGR Mat → HSV
 *   2. [Imgproc.inRange] with the preset's [HsvRange] (handles hue wrap)
 *   3. optional morphological open+close to remove speckle
 *   4. [Imgproc.findContours] to get connected blobs
 *   5. filter by area / aspect ratio → emit a [DrumZone] per surviving blob
 *
 * Designed to be called from the CameraX ImageAnalysis thread; allocates and
 * releases OpenCV Mats per frame (acceptable for M2; pooling is a later opt).
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
    private var closed = false

    /**
     * Segment [bitmap] using [config], returning one [DrumZone] per accepted blob
     * across all presets. [frameId] seeds zone ids so they stay stable within a
     * single frame (stable ids across frames are the PadTracker's job in M3).
     */
    @Synchronized
    fun segment(bitmap: Bitmap, config: DetectionConfig, frameId: Long = 0): List<DrumZone> {
        check(!closed) { "ColorSegmenter is closed" }
        val bmp = if (downsample < 1.0f) {
            val w = (bitmap.width * downsample).toInt().coerceAtLeast(1)
            val h = (bitmap.height * downsample).toInt().coerceAtLeast(1)
            Bitmap.createScaledBitmap(bitmap, w, h, true)
        } else {
            bitmap
        }
        try {
            val width = bmp.width
            val height = bmp.height
            if (width == 0 || height == 0) return emptyList()

            val frameArea = width.toFloat() * height
            val minArea = frameArea * config.minAreaFraction
            val maxArea = frameArea * config.maxAreaFraction
            val bgr = bitmapToBgrMat(bmp)
            val hsv = Mat()
            try {
                Imgproc.cvtColor(bgr, hsv, Imgproc.COLOR_BGR2HSV)
                val zones = ArrayList<DrumZone>(8)
                var zoneId = (frameId * 100).toInt()
                val kernel = if (config.morphEnabled) morphologyKernel() else null

                for (preset in config.presets) {
                    val mask = threshold(hsv, preset.range)
                    try {
                        if (kernel != null) {
                            Imgproc.morphologyEx(mask, mask, Imgproc.MORPH_OPEN, kernel)
                            Imgproc.morphologyEx(mask, mask, Imgproc.MORPH_CLOSE, kernel)
                        }

                        val contours = ArrayList<MatOfPoint>()
                        val hierarchy = Mat()
                        try {
                            Imgproc.findContours(
                                mask, contours, hierarchy,
                                Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE
                            )
                            for (contour in contours) {
                                val area = Imgproc.contourArea(contour)
                                if (area < minArea || area > maxArea) continue
                                val cvRect = Imgproc.boundingRect(contour)
                                val aspect = cvRect.width.toFloat() / cvRect.height.toFloat().coerceAtLeast(1f)
                                if (aspect !in config.aspectRatio) continue

                                val moment = Imgproc.moments(contour)
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
                            hierarchy.release()
                            contours.forEach(MatOfPoint::release)
                        }
                    } finally {
                        mask.release()
                    }
                }
                return zones
            } finally {
                hsv.release()
                bgr.release()
            }
        } finally {
            if (bmp !== bitmap) bmp.recycle()
        }
    }

    private fun morphologyKernel(): Mat = morphologyKernel
        ?: Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(5.0, 5.0))
            .also { morphologyKernel = it }

    @Synchronized
    override fun close() {
        if (closed) return
        morphologyKernel?.release()
        morphologyKernel = null
        closed = true
    }

    /** Apply [range] to an HSV Mat, accounting for hue wrap-around at the 0/180 boundary. */
    private fun threshold(hsv: Mat, range: HsvRange): Mat {
        val base = Mat()
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
            val wrap = Mat()
            Core.inRange(hsv, lower, upper, wrap)
            Core.bitwise_or(base, wrap, base)
            wrap.release()
        } else {
            // Normal single segment.
            setScalar(lower, range.hMin, range.sMin, range.vMin)
            setScalar(upper, range.hMax, range.sMax, range.vMax)
            Core.inRange(hsv, lower, upper, base)
        }
        return base
    }

    private fun setScalar(target: Scalar, h: Int, s: Int, v: Int) {
        target.`val`[0] = h.toDouble()
        target.`val`[1] = s.toDouble()
        target.`val`[2] = v.toDouble()
    }

    /** Convert an Android Bitmap (RGBA) into an OpenCV BGR 3-channel Mat. */
    private fun bitmapToBgrMat(bitmap: Bitmap): Mat {
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        // Convert ARGB int[] → RGBA byte[] for OpenCV CV_8UC4.
        val bytes = ByteArray(w * h * 4)
        for (i in pixels.indices) {
            val p = pixels[i]
            bytes[i * 4]     = ((p shr 16) and 0xff).toByte() // R
            bytes[i * 4 + 1] = ((p shr 8) and 0xff).toByte()  // G
            bytes[i * 4 + 2] = (p and 0xff).toByte()          // B
            bytes[i * 4 + 3] = ((p shr 24) and 0xff).toByte() // A
        }
        val rgba = Mat(h, w, CvType.CV_8UC4)
        try {
            rgba.put(0, 0, bytes)
            val bgr = Mat()
            try {
                Imgproc.cvtColor(rgba, bgr, Imgproc.COLOR_RGBA2BGR)
                return bgr
            } catch (e: Throwable) {
                bgr.release()
                throw e
            }
        } finally {
            rgba.release()
        }
    }
}
