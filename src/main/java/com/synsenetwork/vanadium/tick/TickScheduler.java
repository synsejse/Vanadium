package com.synsenetwork.vanadium.tick;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Runs each {@link Stage} of a world tick as four colored passes on a {@link WorkerPool}.
 * Worlds are processed one at a time; within a stage, cells of the same color run in parallel,
 * and different colors are separated by a barrier so adjacent cells never overlap in time.
 */
public final class TickScheduler {
    private static final int COLORS = 4;

    private final WorkerPool pool;
    private int cellSize;
    private final Map<Stage, CellGrid> grids = new EnumMap<>(Stage.class);

    public TickScheduler(WorkerPool pool, int cellSize) {
        this.pool = pool;
        this.cellSize = cellSize;
        rebuildGrids();
    }

    private void rebuildGrids() {
        for (Stage stage : Stage.values()) {
            grids.put(stage, new CellGrid(cellSize));
        }
    }

    /** Changes the cell size; takes effect on the next stage. No-op if unchanged. */
    public void setCellSize(int cellSize) {
        if (cellSize != this.cellSize) {
            this.cellSize = cellSize;
            rebuildGrids();
        }
    }

    /** Clears a stage's leftover work before the world starts queuing into it. */
    public void begin(Stage stage) {
        grids.get(stage).clear();
    }

    /** Queues a tick task into the cell that owns (chunkX, chunkZ). */
    public void enqueue(Stage stage, int chunkX, int chunkZ, Runnable task) {
        grids.get(stage).cellFor(chunkX, chunkZ).add(task);
    }

    /** Runs the stage's queued work as four colored passes, then clears it. */
    public void run(Stage stage) {
        CellGrid grid = grids.get(stage);
        for (int color = 0; color < COLORS; color++) {
            List<Cell> cells = grid.cellsWithColor(color);
            pool.runWave(cells.stream().map(cell -> (Runnable) cell::run).toList());
        }
        grid.clear();
    }
}
