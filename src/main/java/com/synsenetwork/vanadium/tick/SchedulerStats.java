package com.synsenetwork.vanadium.tick;

import java.util.Arrays;
import java.util.concurrent.atomic.LongAdder;

/**
 * Per-window scheduler telemetry for the debug HUD. Stage wall times and counts are written on the
 * server thread (from {@link TickScheduler}); per-cell work time and the chunk-cache counters are
 * written from worker / {@code getChunk} threads, so those use {@link LongAdder}. {@link #snapshotAndReset()}
 * collects the window's totals and clears them. All collection is gated by {@link #isEnabled()}, so it
 * costs nothing while no one is subscribed to the debug renderer.
 *
 * <p>Stage values are indexed by {@link Stage#ordinal()}: 0 = CHUNK, 1 = ENTITY, 2 = BLOCK_ENTITY.
 */
public final class SchedulerStats {
    private static final int STAGES = 3;

    private volatile boolean enabled;
    private volatile int workers;

    // Server-thread accumulators (TickScheduler.run, one world-stage at a time).
    private final long[] stageNanos = new long[STAGES];
    private final int[] tasksRun = new int[STAGES];
    private int cellsRun;
    private int ticks;

    // Worker / getChunk-thread accumulators.
    private final LongAdder workNanos = new LongAdder();
    private final LongAdder cacheHits = new LongAdder();
    private final LongAdder cacheMisses = new LongAdder();
    private final LongAdder cacheBounces = new LongAdder();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public void setWorkers(int workers) {
        this.workers = workers;
    }

    /** One server tick elapsed in the current window (drives per-tick averaging). Server thread. */
    public void markTick() {
        ticks++;
    }

    /** Records one stage's wall time and the cells/tasks it ran. Server thread. */
    public void recordStage(Stage stage, long wallNanos, int cells, int tasks) {
        int i = stage.ordinal();
        stageNanos[i] += wallNanos;
        tasksRun[i] += tasks;
        cellsRun += cells;
    }

    /** Adds one cell's execution time (used to derive parallel imbalance). Worker thread. */
    public void addWork(long nanos) {
        workNanos.add(nanos);
    }

    public void cacheHit() {
        cacheHits.increment();
    }

    public void cacheMiss() {
        cacheMisses.increment();
    }

    public void cacheBounce() {
        cacheBounces.increment();
    }

    /** Collects the window's totals and resets for the next window. Server thread only. */
    public Snapshot snapshotAndReset() {
        Snapshot snapshot = new Snapshot(ticks, workers,
                stageNanos[0], stageNanos[1], stageNanos[2],
                workNanos.sumThenReset(),
                cellsRun, tasksRun[0], tasksRun[1], tasksRun[2],
                cacheHits.sumThenReset(), cacheMisses.sumThenReset(), cacheBounces.sumThenReset());
        Arrays.fill(stageNanos, 0L);
        Arrays.fill(tasksRun, 0);
        cellsRun = 0;
        ticks = 0;
        return snapshot;
    }

    /** Raw window totals (summed over {@code ticks}); the dispatcher turns these into the wire form. */
    public record Snapshot(int ticks, int workers,
                           long chunkNanos, long entityNanos, long blockEntityNanos,
                           long workNanos,
                           int cellsRun, int chunksTicked, int entitiesTicked, int blockEntitiesTicked,
                           long cacheHits, long cacheMisses, long cacheBounces) {
    }
}
