package com.synsenetwork.vanadium.tick

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

class CellGridTest {
    private val noop = Runnable {}

    @Test
    fun chunksInSameCellShareACell() {
        val grid = CellGrid(8)
        assertSame(grid.cellFor(0, 0), grid.cellFor(7, 7))
        assertNotSame(grid.cellFor(0, 0), grid.cellFor(8, 0))
    }

    @Test
    fun enqueueRecordsEachActiveCellOnce() {
        val grid = CellGrid(8)
        grid.enqueue(0, 0, noop) // cell (0,0)
        grid.enqueue(7, 7, noop) // same cell
        grid.enqueue(8, 0, noop) // cell (1,0)
        assertEquals(2, grid.activeCells.size)
        assertEquals(2, grid.activeCells.first().taskCount)
    }

    @Test
    fun cellForAloneDoesNotMarkActive() {
        val grid = CellGrid(8)
        grid.cellFor(0, 0)
        assertTrue(grid.isEmpty)
        assertEquals(0, grid.activeCells.size)
    }

    @Test
    fun endTickClearsActiveButKeepsCells() {
        val grid = CellGrid(8)
        grid.enqueue(0, 0, noop)
        assertFalse(grid.isEmpty)
        grid.endTick()
        assertTrue(grid.isEmpty)
        assertEquals(1, grid.size)
    }

    @Test
    fun cellsAreReusedAcrossTicks() {
        val grid = CellGrid(8)
        grid.beginTick()
        grid.enqueue(0, 0, noop)
        val first = grid.activeCells.first()
        grid.endTick()
        grid.beginTick()
        grid.enqueue(0, 0, noop)
        assertSame(first, grid.activeCells.first())
        grid.endTick()
    }

    @Test
    fun idleCellsAreEvicted() {
        val grid = CellGrid(8)
        grid.beginTick()
        grid.enqueue(0, 0, noop)
        grid.endTick()
        assertEquals(1, grid.size)
        repeat(CellGrid.IDLE_EVICT_TICKS + 2) {
            grid.beginTick()
            grid.endTick()
        }
        assertEquals(0, grid.size)
    }

    @Test
    fun negativeChunksMapToCorrectCells() {
        val grid = CellGrid(8)
        assertSame(grid.cellFor(-1, -1), grid.cellFor(-8, -8))
        assertNotSame(grid.cellFor(-1, -1), grid.cellFor(0, 0))
        assertNotSame(grid.cellFor(-8, -8), grid.cellFor(-9, -9))
        assertEquals(-1, grid.cellFor(-1, -1).cellX)
        assertEquals(-2, grid.cellFor(-9, -9).cellZ)
        assertEquals(0, grid.cellFor(0, -1).cellX)
        assertEquals(-1, grid.cellFor(0, -1).cellZ)
    }
}
