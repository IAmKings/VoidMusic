package com.electrodig.voidmusic.detection.grid

import java.util.concurrent.atomic.AtomicReference

/**
 * Publishes an immutable 4×16 projection for both rendering and hit lookup.
 * Projection work happens only when calibration corners change.
 */
class GridScanner internal constructor(
    private val rows: Int = 4,
    private val steps: Int = 16,
    private val projectionFactory: (
        corners: List<GridPoint>,
        rows: Int,
        steps: Int
    ) -> GridProjection? = GridProjection::createOrNull
) {
    data class GridPoint(val x: Float, val y: Float)
    data class Cell(val row: Int, val step: Int)

    private val projectionRef = AtomicReference<GridProjection?>(null)

    fun isCalibrated(): Boolean = projectionRef.get() != null

    /** Returns false for a degenerate quadrilateral and preserves the last valid projection. */
    @Synchronized
    fun setCalibration(corners: List<GridPoint>): Boolean {
        require(corners.size == 4) { "Need exactly 4 corners" }
        val snapshot = corners.toList()
        if (projectionRef.get()?.corners == snapshot) return true
        val projection = projectionFactory(snapshot, rows, steps) ?: return false
        projectionRef.set(projection)
        return true
    }

    fun clearCalibration() {
        projectionRef.set(null)
    }

    fun calibration(): List<GridPoint>? = projectionRef.get()?.corners

    fun projection(): GridProjection? = projectionRef.get()

    fun locateCell(point: GridPoint): Cell? = projectionRef.get()?.locateCell(point)
}
