package com.mymts.ui.wall

import org.junit.Assert.assertEquals
import org.junit.Test

/** Pins the grid-agnostic cell dimensions (columns × rows) for the configurable
 *  grid counts — the measured-area cell layout divides the safe area by these. */
class VideoGridLayoutTest {

    @Test fun `columns x rows for the configurable grid counts`() {
        // count -> (columns, rows): 1×1, 2×1, 2×2, 3×2, 3×3
        data class Dim(val count: Int, val cols: Int, val rows: Int)
        listOf(
            Dim(1, 1, 1),
            Dim(2, 2, 1),
            Dim(4, 2, 2),
            Dim(6, 3, 2),
            Dim(9, 3, 3),
        ).forEach { d ->
            val cols = gridColumnsFor(d.count)
            assertEquals("cols for ${d.count}", d.cols, cols)
            assertEquals("rows for ${d.count}", d.rows, gridRowsFor(d.count, cols))
        }
    }

    @Test fun `gridRowsFor guards degenerate inputs`() {
        assertEquals(1, gridRowsFor(0, 2))
        assertEquals(1, gridRowsFor(4, 0))
        assertEquals(1, gridRowsFor(-3, 2))
    }
}
