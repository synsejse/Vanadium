package com.synsenetwork.vanadium.tick;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class CellGridTest {
    private static final Runnable NOOP = () -> { };

    @Test void chunksInSameCellShareACell() {
        CellGrid grid = new CellGrid(8);
        assertSame(grid.cellFor(0, 0), grid.cellFor(7, 7));
        assertNotSame(grid.cellFor(0, 0), grid.cellFor(8, 0));
    }

    @Test void enqueuePartitionsActiveCellsByColor() {
        CellGrid grid = new CellGrid(8);
        grid.enqueue(0, 0, NOOP);   // cell (0,0) color 0
        grid.enqueue(8, 0, NOOP);   // cell (1,0) color 1
        assertEquals(1, grid.cellsWithColor(0).size());
        assertEquals(1, grid.cellsWithColor(1).size());
        assertEquals(0, grid.cellsWithColor(2).size());
    }

    @Test void cellForAloneDoesNotMarkActive() {
        CellGrid grid = new CellGrid(8);
        grid.cellFor(0, 0); // no task enqueued
        assertTrue(grid.isEmpty());
        assertEquals(0, grid.cellsWithColor(0).size());
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
        Cell first = grid.cellsWithColor(0).get(0);
        grid.endTick();
        grid.beginTick();
        grid.enqueue(0, 0, NOOP);
        assertSame(first, grid.cellsWithColor(0).get(0)); // same Cell object reused
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
    }
}
