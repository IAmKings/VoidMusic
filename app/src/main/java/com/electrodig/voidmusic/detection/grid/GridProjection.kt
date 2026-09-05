package com.electrodig.voidmusic.detection.grid

import kotlin.math.abs
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point as CvPoint
import org.opencv.imgproc.Imgproc

/** Immutable perspective projection derived from one four-corner calibration. */
class GridProjection private constructor(
    val corners: List<GridScanner.GridPoint>,
    val cellCenters: List<List<GridScanner.GridPoint>>,
    private val viewToUnit: Homography,
    private val rows: Int,
    private val steps: Int
) {
    fun locateCell(point: GridScanner.GridPoint): GridScanner.Cell? {
        val uv = viewToUnit.map(point.x.toDouble(), point.y.toDouble()) ?: return null
        val u = uv.first
        val v = uv.second
        if (u < -EDGE_EPSILON || u > 1.0 + EDGE_EPSILON ||
            v < -EDGE_EPSILON || v > 1.0 + EDGE_EPSILON
        ) {
            return null
        }
        val step = (u.coerceIn(0.0, 1.0) * steps).toInt().coerceIn(0, steps - 1)
        val row = (v.coerceIn(0.0, 1.0) * rows).toInt().coerceIn(0, rows - 1)
        return GridScanner.Cell(row, step)
    }

    companion object {
        private const val EDGE_EPSILON = 1e-6

        /** OpenCV is used once to derive coefficients; per-frame reads are pure math. */
        fun createOrNull(
            corners: List<GridScanner.GridPoint>,
            rows: Int,
            steps: Int
        ): GridProjection? = runCatching {
            require(corners.size == 4) { "Need exactly 4 corners" }
            require(rows > 0 && steps > 0) { "Grid dimensions must be positive" }

            val source = corners.toPointMat()
            val unit = MatOfPoint2f(
                CvPoint(0.0, 0.0),
                CvPoint(1.0, 0.0),
                CvPoint(1.0, 1.0),
                CvPoint(0.0, 1.0)
            )
            var unitToView: Mat? = null
            var viewToUnit: Mat? = null
            try {
                unitToView = Imgproc.getPerspectiveTransform(unit, source)
                viewToUnit = Imgproc.getPerspectiveTransform(source, unit)
                fromHomographies(
                    corners = corners,
                    rows = rows,
                    steps = steps,
                    unitToView = unitToView.coefficients(),
                    viewToUnit = viewToUnit.coefficients()
                )
            } finally {
                unitToView?.release()
                viewToUnit?.release()
                source.release()
                unit.release()
            }
        }.getOrNull()

        internal fun fromHomographies(
            corners: List<GridScanner.GridPoint>,
            rows: Int,
            steps: Int,
            unitToView: DoubleArray,
            viewToUnit: DoubleArray
        ): GridProjection {
            require(corners.size == 4) { "Need exactly 4 corners" }
            require(rows > 0 && steps > 0) { "Grid dimensions must be positive" }
            val forward = Homography(unitToView.copyOf())
            val inverse = Homography(viewToUnit.copyOf())
            val centers = List(rows) { row ->
                val v = (row + 0.5) / rows
                List(steps) { step ->
                    val u = (step + 0.5) / steps
                    val mapped = requireNotNull(forward.map(u, v)) {
                        "Calibration produced an invalid cell center"
                    }
                    GridScanner.GridPoint(mapped.first.toFloat(), mapped.second.toFloat())
                }
            }
            return GridProjection(corners.toList(), centers, inverse, rows, steps)
        }

        private fun List<GridScanner.GridPoint>.toPointMat(): MatOfPoint2f = MatOfPoint2f(
            *map { CvPoint(it.x.toDouble(), it.y.toDouble()) }.toTypedArray()
        )

        private fun Mat.coefficients(): DoubleArray {
            require(rows() == 3 && cols() == 3 && !empty()) { "Invalid homography" }
            val values = DoubleArray(9)
            require(get(0, 0, values) == values.size) { "Incomplete homography" }
            require(values.all(Double::isFinite)) { "Non-finite homography" }
            return values
        }
    }

    private class Homography(private val values: DoubleArray) {
        init {
            require(values.size == 9) { "Homography must contain 9 coefficients" }
            require(values.all(Double::isFinite)) { "Non-finite homography" }
        }

        fun map(x: Double, y: Double): Pair<Double, Double>? {
            val denominator = values[6] * x + values[7] * y + values[8]
            if (!denominator.isFinite() || abs(denominator) < 1e-12) return null
            val mappedX = (values[0] * x + values[1] * y + values[2]) / denominator
            val mappedY = (values[3] * x + values[4] * y + values[5]) / denominator
            if (!mappedX.isFinite() || !mappedY.isFinite()) return null
            return mappedX to mappedY
        }
    }
}
