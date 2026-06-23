package com.synsenetwork.vanadium.tick;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The cells that have queued work for the current stage of the current world. Built
 * single-threaded during enqueue, then drained color-by-color. Not thread-safe by design —
 * enqueue happens only on the server thread.
 */
public final class CellGrid {
    private final int cellSize;
    private final Map<CellPos, Cell> cells = new HashMap<>();

    public CellGrid(int cellSize) {
        this.cellSize = cellSize;
    }

    /** The cell owning the chunk at (chunkX, chunkZ), created on first use. */
    public Cell cellFor(int chunkX, int chunkZ) {
        CellPos pos = CellPos.fromChunk(chunkX, chunkZ, cellSize);
        return cells.computeIfAbsent(pos, Cell::new);
    }

    /** Every cell whose color matches, in arbitrary order. */
    public List<Cell> cellsWithColor(int color) {
        List<Cell> out = new ArrayList<>();
        for (Cell cell : cells.values()) {
            if (cell.color() == color) {
                out.add(cell);
            }
        }
        return out;
    }

    public boolean isEmpty() {
        return cells.isEmpty();
    }

    public void clear() {
        cells.clear();
    }
}
