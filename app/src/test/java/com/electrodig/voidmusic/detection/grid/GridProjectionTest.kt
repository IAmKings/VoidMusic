package com.electrodig.voidmusic.detection.grid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GridProjectionTest {
    @Test
    fun `production factory creates all 64 centers without native runtime`() {
        val projection = GridProjection.createOrNull(
            corners = rectangleCorners(),
            rows = 4,
            steps = 16
        )

        requireNotNull(projection)
        assertEquals(4, projection.cellCenters.size)
        assertTrue(projection.cellCenters.all { it.size == 16 })
        assertEquals(64, projection.cellCenters.sumOf { it.size })
        assertEquals(GridScanner.Cell(0, 0), projection.locateCell(projection.cellCenters[0][0]))
        assertEquals(GridScanner.Cell(3, 15), projection.locateCell(projection.cellCenters[3][15]))
    }

    @Test
    fun `production factory maps perspective quadrilateral`() {
        val corners = listOf(
            GridScanner.GridPoint(0.20f, 0.15f),
            GridScanner.GridPoint(0.82f, 0.22f),
            GridScanner.GridPoint(0.92f, 0.84f),
            GridScanner.GridPoint(0.08f, 0.76f)
        )

        val projection = requireNotNull(GridProjection.createOrNull(corners, rows = 4, steps = 16))

        projection.cellCenters.forEachIndexed { row, cells ->
            cells.forEachIndexed { step, center ->
                assertEquals(GridScanner.Cell(row, step), projection.locateCell(center))
            }
        }
    }

    @Test
    fun `production factory rejects crossed and collapsed corners`() {
        val rectangle = rectangleCorners()
        val crossed = listOf(rectangle[0], rectangle[2], rectangle[1], rectangle[3])
        val collapsed = List(4) { GridScanner.GridPoint(0.5f, 0.5f) }

        assertNull(GridProjection.createOrNull(crossed, rows = 4, steps = 16))
        assertNull(GridProjection.createOrNull(collapsed, rows = 4, steps = 16))
        assertNull(GridProjection.createOrNull(rectangle.reversed(), rows = 4, steps = 16))
        assertEquals(
            GridProjectionResult.Failure(GridProjectionError.INVALID_QUADRILATERAL),
            GridProjection.create(crossed, rows = 4, steps = 16)
        )
    }

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
