package com.electrodig.objectdrumstudio.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import com.electrodig.objectdrumstudio.detection.hand.HandConnections
import com.electrodig.objectdrumstudio.detection.hand.LandmarkIndex
import com.electrodig.objectdrumstudio.ui.theme.Amber
import com.electrodig.objectdrumstudio.ui.theme.Cyan
import com.electrodig.objectdrumstudio.ui.theme.Lime

/**
 * Draws detected hands over the viewfinder (PRD F5: hand-tracking overlay).
 *
 * Each hand's normalised [0,1] coordinates are scaled to the canvas. We draw:
 *  - bone edges as thin lines,
 *  - all 21 joints as small dots,
 *  - the **index fingertip (landmark 8)** as a large highlighted ring — this is
 *    the trigger point the detection layer will use for hit detection (M3).
 */
@Composable
fun HandOverlay(
    hands: List<com.electrodig.objectdrumstudio.detection.hand.Hand>,
    modifier: Modifier = Modifier
) {
    val boneColor = Color.White.copy(alpha = 0.55f)
    val jointColor = Color.White.copy(alpha = 0.8f)
    val fingertipColors = listOf(Amber, Cyan, Lime)

    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height

        hands.forEachIndexed { handIndex, hand ->
            val color = fingertipColors[handIndex % fingertipColors.size]
            val points = hand.landmarks.map { Offset(it.x * w, it.y * h) }

            // Bones
            for ((a, b) in HandConnections.EDGES) {
                val pa = points.getOrNull(a) ?: continue
                val pb = points.getOrNull(b) ?: continue
                drawLine(
                    color = boneColor,
                    start = pa,
                    end = pb,
                    strokeWidth = 3f,
                    cap = StrokeCap.Round
                )
            }

            // Joints
            points.forEachIndexed { i, p ->
                drawCircle(
                    color = jointColor,
                    radius = if (i == LandmarkIndex.INDEX_FINGERTIP) 0f else 6f,
                    center = p
                )
            }

            // Highlighted fingertip (trigger point)
            val tip = points.getOrNull(LandmarkIndex.INDEX_FINGERTIP) ?: return@forEachIndexed
            drawCircle(
                color = color.copy(alpha = 0.25f),
                radius = 32f,
                center = tip
            )
            drawCircle(
                color = color,
                radius = 14f,
                center = tip
            )
            drawCircle(
                color = Color.White,
                radius = 14f,
                center = tip,
                style = Stroke(width = 3f)
            )
        }
    }
}
