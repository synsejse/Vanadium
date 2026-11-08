package com.synsenetwork.vanadium.tick;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CellGridTest {
    private static final Runnable NOOP = () -> { };

    @Test void chunksInSameCellShareACell() {
        CellGrid grid = new CellGrid(8);
        assertSame(grid.cellFor(0, 0), grid.cellFor(7, 7));
        assertNotSame(grid.cellFor(0, 0), grid.cellFor(8, 0));
    }

    @Test void enqueueRecordsEachActiveCellOnce() {
        CellGrid grid = new CellGrid(8);
        grid.enqueue(0, 0, NOOP);   // cell (0,0)
        grid.enqueue(7, 7, NOOP);   // same cell
        grid.enqueue(8, 0, NOOP);   // cell (1,0)
        assertEquals(2, grid.activeCells().size());
        assertEquals(2, grid.activeCells().getFirst().taskCount());
    }

    @Test void cellForAloneDoesNotMarkActive() {
        CellGrid grid = new CellGrid(8);
        grid.cellFor(0, 0); // no task enqueued
        assertTrue(grid.isEmpty());
        assertEquals(0, grid.activeCells().size());
    }

    @Test void endTickClearsActiveButKeepsCells() {
        CellGrid grid = new CellGrid(8);
        grid.enqueue(0, 0, NOOP);
        assertFalse(grid.isEmpty());
        grid.endTick();
        assertTrue(grid.isEmpty());
        assertEquals(1, grid.size()); // cell retained for reuse
    }

    @Test void cellsAreReusedAcrossTicks() {
        CellGrid grid = new CellGrid(8);
        grid.beginTick();
        grid.enqueue(0, 0, NOOP);
        Cell first = grid.activeCells().getFirst();
        grid.endTick();
        grid.beginTick();
        grid.enqueue(0, 0, NOOP);
        assertSame(first, grid.activeCells().getFirst()); // same Cell object reused
        grid.endTick();
    }

    @Test void idleCellsAreEvicted() {
        CellGrid grid = new CellGrid(8);
        grid.beginTick();
        grid.enqueue(0, 0, NOOP);
        grid.endTick();
        assertEquals(1, grid.size());
        for (int t = 0; t <= CellGrid.IDLE_EVICT_TICKS + 1; t++) {
            grid.beginTick();   // no enqueue -> cell goes idle
            grid.endTick();
        }
        assertEquals(0, grid.size()); // evicted after IDLE_EVICT_TICKS idle ticks
    }

    @Test void negativeChunksMapToCorrectCells() {
        CellGrid grid = new CellGrid(8);
        assertSame(grid.cellFor(-1, -1), grid.cellFor(-8, -8)); // cell (-1,-1)
        assertNotSame(grid.cellFor(-1, -1), grid.cellFor(0, 0));
        assertNotSame(grid.cellFor(-8, -8), grid.cellFor(-9, -9));
        assertEquals(-1, grid.cellFor(-1, -1).cellX());
        assertEquals(-2, grid.cellFor(-9, -9).cellZ());
        assertEquals(0, grid.cellFor(0, -1).cellX());
        assertEquals(-1, grid.cellFor(0, -1).cellZ());
    }
}
