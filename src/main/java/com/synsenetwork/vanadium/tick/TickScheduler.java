package com.synsenetwork.vanadium.tick;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongConsumer;

/**
 * Runs each {@link Stage} of a world tick as one {@link NeighborhoodWave} on a {@link WorkerPool}:
 * a cell starts as soon as none of its eight neighbors is running, so adjacent cells never overlap
 * in time. Worlds are processed one at a time, and stages are separated by full barriers.
 */
public final class TickScheduler {
    /** Largest-first order shortens a wave's tail when cell sizes differ (LPT scheduling). */
    private static final Comparator<Cell> LARGEST_FIRST = (a, b) -> Integer.compare(b.taskCount(), a.taskCount());

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

    /** Runs the stage's queued work as one neighborhood-exclusion wave, then resets the stage. */
    public void run(Stage stage) {
        CellGrid grid = grids.get(stage);
        TickProfile recording = profile;
        long start = recording != null ? System.nanoTime() : 0;
        if (recording != null) {
            Long preparation = preparationStarts.remove(stage);
            if (preparation != null) recording.recordPhase(dimension, stage + " preparation", start - preparation);
        }
        try {
            List<Cell> cells = grid.activeCells();
            if (!cells.isEmpty()) runWave(stage, grid, cells, recording);
        } finally {
            if (recording != null) {
                long end = System.nanoTime();
                recording.recordStage(stage, end - start);
                recording.recordPhase(dimension, stage + " execution", end - start);
                preparationStarts.put(stage, end);
            }
            grid.endTick();
        }
    }

    private void runWave(Stage stage, CellGrid grid, List<Cell> cells, TickProfile recording) {
        if (cells.size() > 2) cells.sort(LARGEST_FIRST);
        if (recording != null) recording.recordWave(stage, cells);
        LongConsumer waitRecorder = recording != null ? nanos -> recording.recordWait(stage, nanos) : null;
        try (WaveDiagnostics.Watch watch = diagnostics != null ? diagnostics.watch(stage, dimension, cellSize) : null) {
            List<? extends Runnable> tasks = watch == null ? cells : watch.wrap(cells);
            long[] durations = recording != null ? new long[tasks.size()] : null;
            if (durations != null) tasks = measured(tasks, durations);
            try {
                if (cells.size() == 1) {
                    tasks.getFirst().run();
                    return;
                }
                NeighborhoodWave wave = new NeighborhoodWave(cells, tasks, grid);
                pool.runWave(wave.participants(Math.min(pool.workerCount() + 1, cells.size())), waitRecorder);
                if (wave.failure() != null) {
                    throw new RuntimeException("Vanadium worker task failed", wave.failure());
                }
            } finally {
                if (durations != null) recording.recordCells(stage, durations);
            }
        }
    }

    /** Opt-in profiling: each cell writes its duration into its own slot; the caller aggregates. */
    private static List<Runnable> measured(List<? extends Runnable> tasks, long[] durations) {
        List<Runnable> measured = new ArrayList<>(tasks.size());
        for (int i = 0; i < tasks.size(); i++) {
            int index = i;
            Runnable task = tasks.get(i);
            measured.add(() -> {
                long cellStart = System.nanoTime();
                try {
                    task.run();
                } finally {
                    durations[index] = System.nanoTime() - cellStart;
                }
            });
        }
        return measured;
    }
}
