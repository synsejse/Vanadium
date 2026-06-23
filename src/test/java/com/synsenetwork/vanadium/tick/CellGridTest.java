package com.synsenetwork.vanadium.tick;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class CellGridTest {
    @Test void chunksInSameCellShareACell() {
        CellGrid grid = new CellGrid(8);
        Cell a = grid.cellFor(0, 0);
        Cell b = grid.cellFor(7, 7);
        Cell c = grid.cellFor(8, 0);
        assertSame(a, b);
        assertNotSame(a, c);
    }

    @Test void cellsWithColorPartitionsByColor() {
        CellGrid grid = new CellGrid(8);
        grid.cellFor(0, 0);   // cell (0,0), color 0
        grid.cellFor(8, 0);   // cell (1,0), color 1
        assertEquals(1, grid.cellsWithColor(0).size());
        assertEquals(1, grid.cellsWithColor(1).size());
        assertEquals(0, grid.cellsWithColor(2).size());
    }

    @Test void clearEmptiesTheGrid() {
        CellGrid grid = new CellGrid(8);
        grid.cellFor(0, 0);
        assertFalse(grid.isEmpty());
        grid.clear();
        assertTrue(grid.isEmpty());
    }
}
