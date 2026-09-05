package com.electrodig.voidmusic.detection.grid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GridProjectionTest {
    @Test
    fun `rectangular projection maps centers and cells without OpenCV calls`() {
        val projection = rectangularProjection(rows = 4, steps = 16)

        assertEquals(0.125f, projection.cellCenters[0][0].x, 1e-5f)
        assertEquals(0.275f, projection.cellCenters[0][0].y, 1e-5f)
        assertEquals(GridScanner.Cell(2, 8), projection.locateCell(GridScanner.GridPoint(0.5f, 0.5f)))
        assertEquals(GridScanner.Cell(3, 15), projection.locateCell(GridScanner.GridPoint(0.9f, 0.8f)))
        assertNull(projection.locateCell(GridScanner.GridPoint(0.05f, 0.5f)))
    }

    @Test
    fun `scanner caches unchanged corners and publishes changed projection`() {
        var builds = 0
        val scanner = GridScanner(rows = 4, steps = 16) { corners, rows, steps ->
            builds += 1
            rectangularProjection(corners, rows, steps)
        }
        val first = rectangleCorners()

        assertTrue(scanner.setCalibration(first))
        val original = scanner.projection()
        assertTrue(scanner.setCalibration(first.toList()))
        assertEquals(1, builds)
        assertTrue(original === scanner.projection())

        val changed = first.toMutableList().also { it[0] = GridScanner.GridPoint(0.12f, 0.2f) }
        assertTrue(scanner.setCalibration(changed))
        assertEquals(2, builds)
        assertFalse(original === scanner.projection())
    }

    @Test
    fun `invalid replacement preserves last valid projection`() {
        var reject = false
        val scanner = GridScanner(rows = 4, steps = 16) { corners, rows, steps ->
            if (reject) null else rectangularProjection(corners, rows, steps)
        }
        assertTrue(scanner.setCalibration(rectangleCorners()))
        val valid = scanner.projection()

        reject = true
        assertFalse(scanner.setCalibration(rectangleCorners().reversed()))

        assertTrue(valid === scanner.projection())
    }

    private fun rectangularProjection(
        corners: List<GridScanner.GridPoint> = rectangleCorners(),
        rows: Int,
        steps: Int
    ): GridProjection = GridProjection.fromHomographies(
        corners = corners,
        rows = rows,
        steps = steps,
        unitToView = doubleArrayOf(
            0.8, 0.0, 0.1,
            0.0, 0.6, 0.2,
            0.0, 0.0, 1.0
        ),
        viewToUnit = doubleArrayOf(
            1.25, 0.0, -0.125,
            0.0, 5.0 / 3.0, -1.0 / 3.0,
            0.0, 0.0, 1.0
        )
    )

    private fun rectangleCorners(): List<GridScanner.GridPoint> = listOf(
        GridScanner.GridPoint(0.1f, 0.2f),
        GridScanner.GridPoint(0.9f, 0.2f),
        GridScanner.GridPoint(0.9f, 0.8f),
        GridScanner.GridPoint(0.1f, 0.8f)
    )
}
