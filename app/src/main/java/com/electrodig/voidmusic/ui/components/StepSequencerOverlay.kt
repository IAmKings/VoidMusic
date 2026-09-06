package com.electrodig.voidmusic.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.electrodig.voidmusic.detection.grid.GridProjection
import com.electrodig.voidmusic.detection.grid.GridScanner
import com.electrodig.voidmusic.detection.grid.SequenceState
import com.electrodig.voidmusic.ui.theme.color

/**
 * Renders the 4×16 step grid over the calibrated paper region (PRD F3.2 / F3.6).
 *
 * Cells come from an immutable perspective projection. Each lit cell
 * is filled with its row's drum-voice colour; the current play-head column gets
 * a bright scan bar so the loop position is obvious. The latest fingertip is
 * drawn so the user can see which cell they are about to toggle.
 */
@Composable
fun StepSequencerOverlay(
    projection: GridProjection?,
    sequence: SequenceState,
    fingertip: GridScanner.GridPoint?,
    modifier: Modifier = Modifier
) {
    val cells = projection?.cellCenters
    if (cells == null) {
        Box(modifier = modifier) {
            Surface(
                modifier = Modifier
                    .align(Alignment.Center)
                    .semantics { contentDescription = "步进网格未校准" },
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                shape = RoundedCornerShape(20.dp),
                tonalElevation = 3.dp
            ) {
                Text(
                    text = "请先完成四点校准",
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.titleSmall
                )
            }
        }
        return
    }

    Canvas(
        modifier = modifier.semantics {
            contentDescription = "4×16 步进网格，共 64 格"
        }
    ) {
        val w = size.width
        val h = size.height

        // Play-head scan bar: a translucent vertical band at the current column.
        if (sequence.isPlaying) {
            val s = sequence.currentStep
            // Estimate the column x from the first row's cell centre.
            val col0 = cells[0][s]
            val neighbour = cells[0][if (s < sequence.steps - 1) s + 1 else s - 1]
            val bandW = (Offset(neighbour.x * w, neighbour.y * h) -
                Offset(col0.x * w, col0.y * h)).getDistance() * 0.72f
            drawRect(
                color = Color.White.copy(alpha = 0.12f),
                topLeft = Offset(col0.x * w - bandW / 2, 0f),
                size = Size(bandW, h)
            )
        }

        for (row in 0 until sequence.rows) {
            val pad = SequenceState.ROW_PADS.getOrNull(row) ?: continue
            val tint = pad.color()
            for (step in 0 until sequence.steps) {
                val c = cells[row][step]
                val cx = c.x * w
                val cy = c.y * h
                val center = Offset(cx, cy)
                val horizontalNeighbour = cells[row][
                    if (step < sequence.steps - 1) step + 1 else step - 1
                ]
                val verticalNeighbour = cells[
                    if (row < sequence.rows - 1) row + 1 else row - 1
                ][step]
                val cellW = (Offset(
                    horizontalNeighbour.x * w,
                    horizontalNeighbour.y * h
                ) - center).getDistance() * 0.78f
                val cellH = (Offset(
                    verticalNeighbour.x * w,
                    verticalNeighbour.y * h
                ) - center).getDistance() * 0.78f
                val radius = minOf(cellW, cellH) * 0.18f
                val lit = sequence.isOn(row, step)
                val playing = sequence.isPlaying && step == sequence.currentStep

                // Cell box.
                drawRoundRect(
                    color = if (lit) tint.copy(alpha = 0.88f) else Color.Black.copy(alpha = 0.24f),
                    topLeft = Offset(cx - cellW / 2, cy - cellH / 2),
                    size = Size(cellW, cellH),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(radius, radius)
                )
                drawRoundRect(
                    color = Color.White.copy(alpha = if (lit) 0.82f else 0.48f),
                    topLeft = Offset(cx - cellW / 2, cy - cellH / 2),
                    size = Size(cellW, cellH),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(radius, radius),
                    style = Stroke(width = 2f)
                )
                // Outline on the play-head column.
                if (playing) {
                    drawRoundRect(
                        color = Color.White,
                        topLeft = Offset(cx - cellW / 2, cy - cellH / 2),
                        size = Size(cellW, cellH),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(radius, radius),
                        style = Stroke(width = 3f)
                    )
                }
            }
        }

        // Fingertip cursor (which cell would toggle next).
        if (fingertip != null) {
            val p = Offset(fingertip.x * w, fingertip.y * h)
            drawCircle(Color.White.copy(alpha = 0.3f), 28f, p)
            drawCircle(Color.White, 8f, p)
        }
    }
}
