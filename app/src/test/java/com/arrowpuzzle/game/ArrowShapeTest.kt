package com.arrowpuzzle.game

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Arrow proportions in cell units, against the real game measured on the test phone at
 * 24.75 px cells: shaft 4 px, head 14 px wide and 13 px long, tip 5 px past the cell centre.
 */
class ArrowShapeTest {
    private val cell = 24.75f
    private val px = 1f  // measuring tolerance in pixels

    @Test
    fun shaftWidth() = assertEquals(4f, BoardRenderer.LW * cell, px)

    @Test
    fun headSize() {
        assertEquals("head width", 14f, 2 * BoardRenderer.HEAD_HW * cell, px)
        assertEquals("head length", 13f, (BoardRenderer.HEAD_TIP + BoardRenderer.HEAD_BACK) * cell, px)
        assertEquals("tip past the cell centre", 5f, BoardRenderer.HEAD_TIP * cell, px)
    }

    @Test
    fun headStaysClearOfNeighbours() {
        // the tip stops well before the next cell's line, and two heads side by side never touch
        assertTrue(BoardRenderer.HEAD_TIP + BoardRenderer.HEAD_ROUND / 2 < 1f - BoardRenderer.LW / 2)
        assertTrue(2 * BoardRenderer.HEAD_HW + BoardRenderer.HEAD_ROUND < 1f)
    }

    @Test
    fun bendsAreSquare() = assertEquals(0f, BoardRenderer.CORNER, 0f)

    @Test
    fun headIsWiderThanTheShaft() = assertTrue(2 * BoardRenderer.HEAD_HW > 3 * BoardRenderer.LW)
}
