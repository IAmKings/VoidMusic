package com.electrodig.objectdrumstudio.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import com.electrodig.objectdrumstudio.detection.grid.GridScanner
import com.electrodig.objectdrumstudio.detection.grid.SequenceState
import com.electrodig.objectdrumstudio.ui.theme.color

/**
 * Renders the 4×16 step grid over the calibrated paper region (PRD F3.2 / F3.6).
 *
 * Cells come from [gridScanner.cellCenters] (perspective-mapped). Each lit cell
 * is filled with its row's drum-voice colour; the current play-head column gets
 * a bright scan bar so the loop position is obvious. The latest fingertip is
 * drawn so the user can see which cell they are about to toggle.
 */
@Composable
fun StepSequencerOverlay(
    gridScanner: GridScanner,
    sequence: SequenceState,
    fingertip: GridScanner.GridPoint?,
    modifier: Modifier = Modifier
) {
    if (!gridScanner.isCalibrated()) return
    val cells = gridScanner.cellCenters()

    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        // Cell footprint derived from spacing; cap so borders don't overlap.
        val cellW = w / sequence.steps * 0.78f
        val cellH = h / sequence.rows * 0.78f
        val radius = minOf(cellW, cellH) * 0.18f

        // Play-head scan bar: a translucent vertical band at the current column.
        if (sequence.isPlaying) {
            val s = sequence.currentStep
            // Estimate the column x from the first row's cell centre.
            val col0 = cells[0][s]
            val bandW = w / sequence.steps * 0.6f
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
                val lit = sequence.isOn(row, step)
                val playing = sequence.isPlaying && step == sequence.currentStep

                // Cell box.
                drawRoundRect(
                    color = if (lit) tint.copy(alpha = 0.85f) else Color.White.copy(alpha = 0.10f),
                    topLeft = Offset(cx - cellW / 2, cy - cellH / 2),
                    size = Size(cellW, cellH),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(radius, radius)
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
