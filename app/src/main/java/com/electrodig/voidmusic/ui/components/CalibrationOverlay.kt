package com.electrodig.voidmusic.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.electrodig.voidmusic.detection.grid.GridScanner

/**
 * Interactive 4-point perspective calibration (PRD F1.4 / §9.2). The user drags
 * four corner handles to frame a sheet of paper; the 4×16 grid preview is drawn
 * through the resulting perspective transform so they can see how cells land.
 *
 * [onConfirm] is called with the four corners in normalised [0,1] coordinates
 * (TL, TR, BR, BL) once the user accepts.
 */
@Composable
fun CalibrationOverlay(
    gridScanner: GridScanner,
    onConfirm: (corners: List<GridScanner.GridPoint>) -> Unit,
    initialCorners: List<GridScanner.GridPoint>? = null,
    modifier: Modifier = Modifier
) {
    // Default corners: a centred rectangle the user can adjust.
    var corners by remember(initialCorners) {
        mutableStateOf(
            initialCorners?.takeIf { it.size == 4 } ?: listOf(
                GridScanner.GridPoint(0.15f, 0.20f),  // TL
                GridScanner.GridPoint(0.85f, 0.20f),  // TR
                GridScanner.GridPoint(0.85f, 0.80f),  // BR
                GridScanner.GridPoint(0.15f, 0.80f)   // BL
            )
        )
    }

    val density = LocalDensity.current
    val handleRadiusPx = with(density) { 14.dp.toPx() }
    var draggedIndex by remember { mutableStateOf<Int?>(null) }

    Canvas(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { offset ->
                        val w = size.width.toFloat(); val h = size.height.toFloat()
                        // Snap the nearest corner to the touch point on grab.
                        val nearest = corners.indices.minByOrNull { i ->
                            val c = corners[i]
                            val cx = c.x * w; val cy = c.y * h
                            (offset - Offset(cx, cy)).getDistanceSquared()
                        }
                        draggedIndex = nearest
                    },
                    onDrag = { change, drag ->
                        change.consume()
                        val i = draggedIndex ?: return@detectDragGestures
                        val w = size.width.toFloat(); val h = size.height.toFloat()
                        corners = corners.toMutableList().also { list ->
                            val cur = list[i]
                            val nx = (cur.x + drag.x / w).coerceIn(0f, 1f)
                            val ny = (cur.y + drag.y / h).coerceIn(0f, 1f)
                            list[i] = GridScanner.GridPoint(nx, ny)
                        }
                    },
                    onDragEnd = {
                        gridScanner.setCalibration(corners)
                        onConfirm(corners)
                    }
                )
            }
    ) {
        val w = size.width; val h = size.height

        // Outer frame polygon.
        val frame = Path().apply {
            corners.forEachIndexed { i, c ->
                val p = Offset(c.x * w, c.y * h)
                if (i == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y)
            }
            close()
        }
        drawPath(frame, color = Color.White.copy(alpha = 0.5f), style = Stroke(width = 4f))

        // Grid preview through the scanner's perspective.
        gridScanner.setCalibration(corners)
        val cells = gridScanner.cellCenters()
        val cellWPx = w / 16 * 0.8f
        val cellHPx = h / 4 * 0.8f
        cells.forEachIndexed { r, row ->
            row.forEachIndexed { s, center ->
                val cx = center.x * w; val cy = center.y * h
                drawRect(
                    color = Color.White.copy(alpha = 0.15f),
                    topLeft = Offset(cx - cellWPx / 2, cy - cellHPx / 2),
                    size = Size(cellWPx, cellHPx)
                )
            }
        }

        // Corner handles.
        corners.forEach { c ->
            val p = Offset(c.x * w, c.y * h)
            drawCircle(Color.Black.copy(alpha = 0.4f), handleRadiusPx + 4f, p)
            drawCircle(Color.White, handleRadiusPx, p)
        }
    }
}

// (draggedIndex is held as local Compose state above — no module-level mutable.)
