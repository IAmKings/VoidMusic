package com.electrodig.voidmusic.camera

import com.electrodig.voidmusic.detection.color.DrumZone
import com.electrodig.voidmusic.detection.hand.Hand

/**
 * Converts analysis-image coordinates into the coordinate system used by a
 * [androidx.camera.view.PreviewView] configured with `FILL_CENTER`.
 *
 * The router has already applied CameraX's display rotation.  The remaining
 * difference is the centre crop that PreviewView applies to fill its bounds.
 * Keeping it here makes drawing, hit-testing and calibration use one contract.
 */
data class PreviewCoordinateMapper(
    val sourceWidth: Int,
    val sourceHeight: Int,
    val viewportWidth: Int,
    val viewportHeight: Int
) {
    init {
        require(sourceWidth > 0 && sourceHeight > 0)
        require(viewportWidth > 0 && viewportHeight > 0)
    }

    private val scale = maxOf(
        viewportWidth.toFloat() / sourceWidth,
        viewportHeight.toFloat() / sourceHeight
    )
    private val cropX = (sourceWidth * scale - viewportWidth) / 2f
    private val cropY = (sourceHeight * scale - viewportHeight) / 2f

    data class Point(val x: Float, val y: Float)

    fun map(x: Float, y: Float): Point = Point(
        x = (x * sourceWidth * scale - cropX) / viewportWidth,
        y = (y * sourceHeight * scale - cropY) / viewportHeight
    )

    /** Reverse [map] for a user touch reported in PreviewView coordinates. */
    fun unmap(x: Float, y: Float): Point = Point(
        x = (x * viewportWidth + cropX) / (sourceWidth * scale),
        y = (y * viewportHeight + cropY) / (sourceHeight * scale)
    )

    fun map(zone: DrumZone): DrumZone {
        val center = map(zone.normalizedCenter.x, zone.normalizedCenter.y)
        val topLeft = map(zone.normalizedBox.left, zone.normalizedBox.top)
        val bottomRight = map(zone.normalizedBox.right, zone.normalizedBox.bottom)
        return zone.copy(
            normalizedCenter = DrumZone.Point(center.x, center.y),
            normalizedBox = DrumZone.Rect(topLeft.x, topLeft.y, bottomRight.x, bottomRight.y)
        )
    }

    fun map(hand: Hand): Hand = hand.copy(
        landmarks = hand.landmarks.map { landmark ->
            val point = map(landmark.x, landmark.y)
            landmark.copy(x = point.x, y = point.y)
        },
        imageWidth = viewportWidth,
        imageHeight = viewportHeight
    )

    companion object {
        fun forFillCenter(
            sourceWidth: Int,
            sourceHeight: Int,
            viewportWidth: Int,
            viewportHeight: Int
        ): PreviewCoordinateMapper? =
            if (sourceWidth <= 0 || sourceHeight <= 0 || viewportWidth <= 0 || viewportHeight <= 0) {
                null
            } else {
                PreviewCoordinateMapper(sourceWidth, sourceHeight, viewportWidth, viewportHeight)
            }
    }
}
