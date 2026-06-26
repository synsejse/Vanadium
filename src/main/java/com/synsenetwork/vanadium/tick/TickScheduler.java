package com.synsenetwork.vanadium.tick;

import java.util.ArrayList;
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
    private final SchedulerStats stats; // nullable — null in standalone benchmarks
    private int cellSize;
    private final Map<Stage, CellGrid> grids = new EnumMap<>(Stage.class);

    public TickScheduler(WorkerPool pool, int cellSize) {
        this(pool, cellSize, null);
    }

    public TickScheduler(WorkerPool pool, int cellSize, SchedulerStats stats) {
        this.pool = pool;
        this.cellSize = cellSize;
        this.stats = stats;
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

    /** Starts a stage's tick: ages idle cells and clears leftover work. */
    public void begin(Stage stage) {
        grids.get(stage).beginTick();
    }

    /** Queues a tick task into the cell that owns (chunkX, chunkZ). */
    public void enqueue(Stage stage, int chunkX, int chunkZ, Runnable task) {
        grids.get(stage).enqueue(chunkX, chunkZ, task);
    }

    /** Runs the stage's queued work as four colored passes, then resets it. */
    public void run(Stage stage) {
        CellGrid grid = grids.get(stage);
        if (stats != null && stats.isEnabled()) {
            runMeasured(stage, grid);
        } else {
            for (int color = 0; color < COLORS; color++) {
                pool.runWave(grid.cellsWithColor(color));
            }
        }
        grid.endTick();
    }

    /** Same four-wave dispatch, but timed: per-stage wall time, cell/task counts, per-cell work time. */
    private void runMeasured(Stage stage, CellGrid grid) {
        long start = System.nanoTime();
        int cellCount = 0;
        int taskCount = 0;
        for (int color = 0; color < COLORS; color++) {
            List<Cell> cells = grid.cellsWithColor(color);
            cellCount += cells.size();
            List<Runnable> timed = new ArrayList<>(cells.size());
            for (Cell cell : cells) {
                taskCount += cell.taskCount();
                timed.add(() -> {
                    long cellStart = System.nanoTime();
                    cell.run();
                    stats.addWork(System.nanoTime() - cellStart);
                });
            }
            pool.runWave(timed);
        }
        stats.recordStage(stage, System.nanoTime() - start, cellCount, taskCount);
    }
}
