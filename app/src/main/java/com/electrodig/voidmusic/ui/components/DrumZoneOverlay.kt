package com.electrodig.voidmusic.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import com.electrodig.voidmusic.detection.color.DrumZone
import com.electrodig.voidmusic.ui.theme.color

/**
 * Draws detected drum zones over the viewfinder (PRD F4.5: 实时预览叠加).
 *
 * Each zone is rendered as a filled, tinted bounding box (colour keyed to its
 * mapped drum voice) with its centre marker and preset label anchor. Normalised
 * coordinates keep the overlay resolution-independent.
 */
@Composable
fun DrumZoneOverlay(
    zones: List<DrumZone>,
    flashedZoneIds: Set<Int> = emptySet(),
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height

        zones.forEach { zone ->
            val tint = zone.mappedPad.color()
            val flashed = zone.id in flashedZoneIds
            val box = zone.normalizedBox
            val left = box.left * w
            val top = box.top * h
            val boxW = (box.right - box.left) * w
            val boxH = (box.bottom - box.top) * h

            // Translucent fill + crisp outline; brighter when freshly triggered.
            val fillAlpha = if (flashed) 0.55f else 0.18f
            val strokeW = if (flashed) 6f else 3f
            drawRect(
                color = tint.copy(alpha = fillAlpha),
                topLeft = Offset(left, top),
                size = Size(boxW, boxH)
            )
            drawRect(
                color = tint,
                topLeft = Offset(left, top),
                size = Size(boxW, boxH),
                style = Stroke(width = strokeW)
            )

            // Centre marker.
            val center = Offset(zone.normalizedCenter.x * w, zone.normalizedCenter.y * h)
            drawCircle(color = tint, radius = 8f, center = center)
            drawCircle(color = Color.White, radius = 8f, center = center, style = Stroke(width = 2f))
        }
    }
}
