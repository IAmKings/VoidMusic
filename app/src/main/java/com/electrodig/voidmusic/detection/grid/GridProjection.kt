package com.electrodig.voidmusic.detection.grid

import kotlin.math.abs

enum class GridProjectionError {
    INVALID_CORNER_COUNT,
    INVALID_GRID_SIZE,
    NON_FINITE_CORNER,
    CORNER_OUT_OF_BOUNDS,
    INVALID_QUADRILATERAL,
    NON_INVERTIBLE,
    INVALID_CELL_CENTER
}

sealed interface GridProjectionResult {
    data class Success(val projection: GridProjection) : GridProjectionResult
    data class Failure(val error: GridProjectionError) : GridProjectionResult
}

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

        /** Builds the projection without depending on OpenCV/native-loader timing. */
        fun create(
            corners: List<GridScanner.GridPoint>,
            rows: Int,
            steps: Int
        ): GridProjectionResult {
            if (corners.size != 4) {
                return GridProjectionResult.Failure(GridProjectionError.INVALID_CORNER_COUNT)
            }
            if (rows <= 0 || steps <= 0) {
                return GridProjectionResult.Failure(GridProjectionError.INVALID_GRID_SIZE)
            }
            if (corners.any { !it.x.isFinite() || !it.y.isFinite() }) {
                return GridProjectionResult.Failure(GridProjectionError.NON_FINITE_CORNER)
            }
            if (corners.any { it.x !in 0f..1f || it.y !in 0f..1f }) {
                return GridProjectionResult.Failure(GridProjectionError.CORNER_OUT_OF_BOUNDS)
            }
            if (!corners.isStrictlyConvex()) {
                return GridProjectionResult.Failure(GridProjectionError.INVALID_QUADRILATERAL)
            }

            val unitToView = unitSquareTo(corners)
                ?: return GridProjectionResult.Failure(GridProjectionError.NON_INVERTIBLE)
            val viewToUnit = invert(unitToView)
                ?: return GridProjectionResult.Failure(GridProjectionError.NON_INVERTIBLE)
            val projection = try {
                fromHomographies(corners, rows, steps, unitToView, viewToUnit)
            } catch (_: IllegalArgumentException) {
                return GridProjectionResult.Failure(GridProjectionError.INVALID_CELL_CENTER)
            }
            return GridProjectionResult.Success(projection)
        }

        fun createOrNull(
            corners: List<GridScanner.GridPoint>,
            rows: Int,
            steps: Int
        ): GridProjection? = when (val result = create(corners, rows, steps)) {
            is GridProjectionResult.Success -> result.projection
            is GridProjectionResult.Failure -> null
        }

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

        private fun List<GridScanner.GridPoint>.isStrictlyConvex(): Boolean {
            var direction = 0
            for (index in indices) {
                val a = this[index]
                val b = this[(index + 1) % size]
                val c = this[(index + 2) % size]
                val cross = (b.x - a.x).toDouble() * (c.y - b.y) -
                    (b.y - a.y).toDouble() * (c.x - b.x)
                if (abs(cross) < GEOMETRY_EPSILON) return false
                val currentDirection = if (cross > 0.0) 1 else -1
                if (direction != 0 && direction != currentDirection) return false
                direction = currentDirection
            }
            return direction > 0
        }

        /** Returns the 3×3 transform from the unit square to TL/TR/BR/BL corners. */
        private fun unitSquareTo(corners: List<GridScanner.GridPoint>): DoubleArray? {
            val x0 = corners[0].x.toDouble()
            val y0 = corners[0].y.toDouble()
            val x1 = corners[1].x.toDouble()
            val y1 = corners[1].y.toDouble()
            val x2 = corners[2].x.toDouble()
            val y2 = corners[2].y.toDouble()
            val x3 = corners[3].x.toDouble()
            val y3 = corners[3].y.toDouble()
            val dx1 = x1 - x2
            val dx2 = x3 - x2
            val dx3 = x0 - x1 + x2 - x3
            val dy1 = y1 - y2
            val dy2 = y3 - y2
            val dy3 = y0 - y1 + y2 - y3

            val g: Double
            val h: Double
            if (abs(dx3) < GEOMETRY_EPSILON && abs(dy3) < GEOMETRY_EPSILON) {
                g = 0.0
                h = 0.0
            } else {
                val denominator = dx1 * dy2 - dx2 * dy1
                if (!denominator.isFinite() || abs(denominator) < GEOMETRY_EPSILON) return null
                g = (dx3 * dy2 - dx2 * dy3) / denominator
                h = (dx1 * dy3 - dx3 * dy1) / denominator
            }

            return doubleArrayOf(
                x1 - x0 + g * x1, x3 - x0 + h * x3, x0,
                y1 - y0 + g * y1, y3 - y0 + h * y3, y0,
                g, h, 1.0
            ).takeIf { values -> values.all(Double::isFinite) }
        }

        private fun invert(values: DoubleArray): DoubleArray? {
            val a = values[0]; val b = values[1]; val c = values[2]
            val d = values[3]; val e = values[4]; val f = values[5]
            val g = values[6]; val h = values[7]; val i = values[8]
            val determinant = a * (e * i - f * h) -
                b * (d * i - f * g) +
                c * (d * h - e * g)
            if (!determinant.isFinite() || abs(determinant) < GEOMETRY_EPSILON) return null
            return doubleArrayOf(
                e * i - f * h, c * h - b * i, b * f - c * e,
                f * g - d * i, a * i - c * g, c * d - a * f,
                d * h - e * g, b * g - a * h, a * e - b * d
            ).map { it / determinant }.toDoubleArray()
        }

        private const val GEOMETRY_EPSILON = 1e-9
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
