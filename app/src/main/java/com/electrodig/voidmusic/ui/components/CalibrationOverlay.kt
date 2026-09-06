package com.electrodig.voidmusic.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.electrodig.voidmusic.detection.grid.GridProjection
import com.electrodig.voidmusic.detection.grid.GridProjectionResult
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
    onConfirm: (corners: List<GridScanner.GridPoint>) -> Boolean,
    onCancel: () -> Unit,
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
    val projectionResult = remember(corners) {
        GridProjection.create(corners, rows = 4, steps = 16)
    }
    val projection = (projectionResult as? GridProjectionResult.Success)?.projection

    Box(modifier = modifier.fillMaxSize()) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .semantics {
                    contentDescription = if (projection == null) {
                        "校准区域无效"
                    } else {
                        "校准网格预览，共 64 格"
                    }
                }
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { offset ->
                            val w = size.width.toFloat(); val h = size.height.toFloat()
                            // Snap the nearest corner to the touch point on grab.
                            draggedIndex = corners.indices.minByOrNull { index ->
                                val corner = corners[index]
                                val cx = corner.x * w; val cy = corner.y * h
                                (offset - Offset(cx, cy)).getDistanceSquared()
                            }
                        },
                        onDrag = { change, drag ->
                            change.consume()
                            val index = draggedIndex ?: return@detectDragGestures
                            val w = size.width.toFloat(); val h = size.height.toFloat()
                            corners = corners.toMutableList().also { list ->
                                val current = list[index]
                                val nx = (current.x + drag.x / w).coerceIn(0f, 1f)
                                val ny = (current.y + drag.y / h).coerceIn(0f, 1f)
                                list[index] = GridScanner.GridPoint(nx, ny)
                            }
                        },
                        onDragEnd = { draggedIndex = null },
                        onDragCancel = { draggedIndex = null }
                    )
                }
        ) {
            val w = size.width; val h = size.height

            // Outer frame polygon.
            val frame = Path().apply {
                corners.forEachIndexed { index, corner ->
                    val point = Offset(corner.x * w, corner.y * h)
                    if (index == 0) moveTo(point.x, point.y) else lineTo(point.x, point.y)
                }
                close()
            }
            drawPath(frame, color = Color.White.copy(alpha = 0.7f), style = Stroke(width = 4f))

            // Projection changes during composition, never from the draw pass.
            val cells = projection?.cellCenters.orEmpty()
            cells.forEachIndexed { rowIndex, row ->
                row.forEachIndexed { stepIndex, center ->
                    val cx = center.x * w; val cy = center.y * h
                    val centerPx = Offset(cx, cy)
                    val horizontalNeighbour = row[
                        if (stepIndex < row.lastIndex) stepIndex + 1 else stepIndex - 1
                    ]
                    val verticalNeighbour = cells[
                        if (rowIndex < cells.lastIndex) rowIndex + 1 else rowIndex - 1
                    ][stepIndex]
                    val cellWPx = (Offset(
                        horizontalNeighbour.x * w,
                        horizontalNeighbour.y * h
                    ) - centerPx).getDistance() * 0.78f
                    val cellHPx = (Offset(
                        verticalNeighbour.x * w,
                        verticalNeighbour.y * h
                    ) - centerPx).getDistance() * 0.78f
                    drawRect(
                        color = Color.Black.copy(alpha = 0.22f),
                        topLeft = Offset(cx - cellWPx / 2, cy - cellHPx / 2),
                        size = Size(cellWPx, cellHPx)
                    )
                    drawRect(
                        color = Color.White.copy(alpha = 0.55f),
                        topLeft = Offset(cx - cellWPx / 2, cy - cellHPx / 2),
                        size = Size(cellWPx, cellHPx),
                        style = Stroke(width = 2f)
                    )
                }
            }

            // Corner handles.
            corners.forEach { corner ->
                val point = Offset(corner.x * w, corner.y * h)
                drawCircle(Color.Black.copy(alpha = 0.4f), handleRadiusPx + 4f, point)
                drawCircle(Color.White, handleRadiusPx, point)
            }
        }

        Surface(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 112.dp),
            color = Color.Black.copy(alpha = 0.72f),
            shape = RoundedCornerShape(18.dp)
        ) {
            Text(
                text = if (projection == null) {
                    "四个角不能交叉或重叠，请重新调整"
                } else {
                    "拖动四个角，使网格覆盖演奏区域"
                },
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                color = Color.White,
                style = MaterialTheme.typography.labelLarge
            )
        }

        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(20.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedButton(onClick = onCancel) {
                Text("取消")
            }
            Button(
                onClick = { if (projection != null) onConfirm(projection.corners) },
                enabled = projection != null
            ) {
                Text("确认并保存")
            }
        }
    }
}

// (draggedIndex is held as local Compose state above — no module-level mutable.)
