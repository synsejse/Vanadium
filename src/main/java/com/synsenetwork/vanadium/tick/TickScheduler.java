package com.synsenetwork.vanadium.tick;

import java.util.EnumMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.LongConsumer;

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
    private TickProfile profile;
    private WaveDiagnostics diagnostics;
    private String dimension = "unknown";
    private final Map<Stage, Long> preparationStarts = new EnumMap<>(Stage.class);
    private long worldStart;

    public TickScheduler(WorkerPool pool, int cellSize) {
        this.pool = pool;
        this.cellSize = cellSize;
        rebuildGrids();
    }

    public int workerCount() {
        return pool.workerCount();
    }

    public int cellSize() {
        return cellSize;
    }

    /** Independent preparation jobs only: inputs stay stable and jobs must not enqueue into cell grids. */
    public void prepare(List<? extends Runnable> jobs) {
        pool.runWave(jobs, null);
    }

    /** Server-thread only; profiling is opt-in and does not change dispatch. */
    public void setProfile(TickProfile profile) {
        this.profile = profile;
        preparationStarts.clear();
    }

    public void setDiagnostics(WaveDiagnostics diagnostics) {
        this.diagnostics = diagnostics;
    }

    public void setDimension(String dimension) {
        this.dimension = dimension;
        if (profile != null) worldStart = System.nanoTime();
    }

    public void finishWorld() {
        if (profile != null) profile.recordPhase(dimension, "world total", System.nanoTime() - worldStart);
    }

    public boolean detailedDiagnostics() {
        return diagnostics != null && diagnostics.detailed();
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
        if (profile != null) preparationStarts.put(stage, System.nanoTime());
        grids.get(stage).beginTick();
    }

    /** Queues a tick task into its cell. Pass null for the label unless detailed diagnostics are enabled. */
    public void enqueue(Stage stage, int chunkX, int chunkZ, Runnable task, String label) {
        grids.get(stage).enqueue(chunkX, chunkZ, label == null ? task : new WaveDiagnostics.NamedTask(label, task));
    }

    /** Runs the stage's queued work as four colored passes, then resets it. */
    public void run(Stage stage) {
        CellGrid grid = grids.get(stage);
        TickProfile recording = profile;
        long start = recording != null ? System.nanoTime() : 0;
        if (recording != null) {
            Long preparation = preparationStarts.remove(stage);
            if (preparation != null) recording.recordPhase(dimension, stage + " preparation", start - preparation);
        }
        LongConsumer waitRecorder = recording != null ? nanos -> recording.recordWait(stage, nanos) : null;
        try {
            for (int color = 0; color < COLORS; color++) {
                List<Cell> cells = grid.cellsWithColor(color);
                if (recording != null) recording.recordWave(stage, cells);
                try (WaveDiagnostics.Watch watch = diagnostics != null && !cells.isEmpty()
                        ? diagnostics.watch(stage, dimension, color, cellSize) : null) {
                    List<? extends Runnable> tasks = watch == null ? cells : watch.wrap(cells);
                    if (recording == null) {
                        pool.runWave(tasks, waitRecorder);
                    } else {
                        long[] durations = new long[tasks.size()];
                        List<Runnable> measured = new ArrayList<>(tasks.size());
                        for (int i = 0; i < tasks.size(); i++) {
                            int index = i;
                            Runnable task = tasks.get(i);
                            measured.add(() -> {
                                long cellStart = System.nanoTime();
                                try { task.run(); }
                                finally { durations[index] = System.nanoTime() - cellStart; }
                            });
                        }
                        try { pool.runWave(measured, waitRecorder); }
                        finally { recording.recordCells(stage, durations); }
                    }
                }
            }
        } finally {
            if (recording != null) {
                long end = System.nanoTime();
                recording.recordStage(stage, end - start);
                recording.recordPhase(dimension, stage + " execution", end - start);
                preparationStarts.put(stage, end);
            }
        }
        grid.endTick();
    }
}
