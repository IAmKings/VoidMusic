package com.electrodig.objectdrumstudio.detection.grid

import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point as CvPoint

/**
 * Maps a 4×16 step-sequencer grid onto a calibrated quadrilateral on paper
 * (PRD §9.2 / F3.2). Once calibrated, [cellCenters] yields each cell's centre
 * in normalised viewfinder coordinates, and [locateCell] tells which cell a
 * fingertip landed in (used by the step sequencer in M4).
 *
 * Corners are expected in normalised [0,1] coordinates in this order:
 * top-left, top-right, bottom-right, bottom-left (clockwise from TL).
 */
class GridScanner(
    private val rows: Int = 4,
    private val steps: Int = 16
) {
    /** Calibrated corners in normalised [0,1] space, or null if not calibrated. */
    private var corners: List<GridPoint>? = null

    data class GridPoint(val x: Float, val y: Float)
    data class Cell(val row: Int, val step: Int)

    fun isCalibrated(): Boolean = corners != null

    fun setCalibration(corners: List<GridPoint>) {
        require(corners.size == 4) { "Need exactly 4 corners" }
        this.corners = corners
    }

    fun clearCalibration() {
        corners = null
    }

    /** Returns a defensive snapshot suitable for persistence/UI initialization. */
    fun calibration(): List<GridPoint>? = corners?.toList()

    /**
     * Returns the centre of every cell in normalised [0,1] coordinates, laid out
     * as [row][step]. Uses OpenCV perspective transform so the grid follows the
     * paper's perspective.
     */
    fun cellCenters(): Array<Array<GridPoint>> {
        val src = corners ?: return defaultCenters()
        val srcMat = MatOfPoint2f(
            CvPoint(src[0].x.toDouble(), src[0].y.toDouble()),
            CvPoint(src[1].x.toDouble(), src[1].y.toDouble()),
            CvPoint(src[2].x.toDouble(), src[2].y.toDouble()),
            CvPoint(src[3].x.toDouble(), src[3].y.toDouble())
        )
        // Target is the unit square; cell uv in [0,1] maps back into source space.
        val dstMat = MatOfPoint2f(
            CvPoint(0.0, 0.0), CvPoint(1.0, 0.0),
            CvPoint(1.0, 1.0), CvPoint(0.0, 1.0)
        )
        val transform = org.opencv.imgproc.Imgproc.getPerspectiveTransform(dstMat, srcMat)

        val out = Array(rows) { Array(steps) { GridPoint(0.5f, 0.5f) } }
        for (r in 0 until rows) {
            val v = (r + 0.5f) / rows
            for (s in 0 until steps) {
                val u = (s + 0.5f) / steps
                val mapped = mapPoint(transform, u.toDouble(), v.toDouble())
                out[r][s] = GridPoint(mapped[0].toFloat(), mapped[1].toFloat())
            }
        }
        transform.release()
        srcMat.release()
        dstMat.release()
        return out
    }

    /** Which cell a normalised point falls into, or null if outside the grid. */
    fun locateCell(point: GridPoint): Cell? {
        val src = corners ?: return null
        val srcMat = MatOfPoint2f(
            CvPoint(src[0].x.toDouble(), src[0].y.toDouble()),
            CvPoint(src[1].x.toDouble(), src[1].y.toDouble()),
            CvPoint(src[2].x.toDouble(), src[2].y.toDouble()),
            CvPoint(src[3].x.toDouble(), src[3].y.toDouble())
        )
        val dstMat = MatOfPoint2f(
            CvPoint(0.0, 0.0), CvPoint(1.0, 0.0),
            CvPoint(1.0, 1.0), CvPoint(0.0, 1.0)
        )
        val transform = org.opencv.imgproc.Imgproc.getPerspectiveTransform(srcMat, dstMat)
        val uv = mapPoint(transform, point.x.toDouble(), point.y.toDouble())
        transform.release(); srcMat.release(); dstMat.release()

        val u = uv[0]; val v = uv[1]
        if (u !in 0.0..1.0 || v !in 0.0..1.0) return null
        val row = (v * rows).toInt().coerceIn(0, rows - 1)
        val step = (u * steps).toInt().coerceIn(0, steps - 1)
        return Cell(row, step)
    }

    private fun mapPoint(transform: Mat, x: Double, y: Double): DoubleArray {
        val input = Mat(1, 1, CvType.CV_64FC2)
        input.put(0, 0, x, y)
        val output = Mat()
        Core.perspectiveTransform(input, output, transform)
        val res = DoubleArray(2)
        output.get(0, 0, res)
        input.release(); output.release()
        return res
    }

    /** Fallback centred grid (pre-calibration) so the overlay still renders. */
    private fun defaultCenters(): Array<Array<GridPoint>> {
        val margin = 0.1f
        val usable = 1f - 2 * margin
        return Array(rows) { r ->
            Array(steps) { s ->
                GridPoint(
                    margin + usable * (s + 0.5f) / steps,
                    margin + usable * (r + 0.5f) / rows
                )
            }
        }
    }
}
