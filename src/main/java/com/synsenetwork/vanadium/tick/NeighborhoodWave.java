package com.synsenetwork.vanadium.tick;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicIntegerArray;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Runs every active cell of a stage in one wave. A cell may start once none of its eight neighbors
 * is running, so adjacent cells never overlap, and a free cell never waits for unrelated work.
 *
 * <p>Claim protocol (all state accesses are volatile and therefore sequentially consistent):
 * a participant moves a cell from PENDING to TRYING, then inspects its active neighbors. A RUNNING
 * neighbor makes it back off. For a TRYING neighbor the higher-index cell backs off and the
 * lower-index cell waits for that neighbor to resolve. Two adjacent claimants each write their own
 * state before reading the other's, so at least one observes the other: both can never pass.
 * Waits only point to higher indices, so they cannot form a cycle.
 *
 * <p>Cells are tried in list order; callers sort them largest-first. Within a cell, tasks still run
 * serially in insertion order. Adjacent cells run in an order that varies between ticks.
 */
final class NeighborhoodWave {
    private static final int PENDING = 0;
    private static final int TRYING = 1;
    private static final int RUNNING = 2;
    private static final int DONE = 3;
    /** One state per 64-byte line: claims on one cell do not invalidate a neighbor's line. */
    private static final int STRIDE = 16;
    private static final int[][] OFFSETS = {{-1, -1}, {0, -1}, {1, -1}, {-1, 0}, {1, 0}, {-1, 1}, {0, 1}, {1, 1}};

    private final List<? extends Runnable> work;
    private final int count;
    /** Active neighbor indices, eight slots per cell, -1 for none. */
    private final int[] neighbors;
    private final AtomicIntegerArray states;
    private final AtomicInteger remaining;
    private final AtomicInteger firstPending = new AtomicInteger();
    private final AtomicReference<Throwable> failure = new AtomicReference<>();

    /**
     * @param cells active cells of the stage, in claim priority order
     * @param work the runnable for each cell (the cell itself, or a diagnostic/profiling wrapper), index-aligned
     * @param grid the stage's grid, used to resolve neighbors
     */
    NeighborhoodWave(List<Cell> cells, List<? extends Runnable> work, CellGrid grid) {
        this.work = work;
        this.count = cells.size();
        this.neighbors = new int[count * 8];
        this.states = new AtomicIntegerArray(count * STRIDE);
        this.remaining = new AtomicInteger(count);
        for (int i = 0; i < count; i++) cells.get(i).waveIndex = i;
        for (int i = 0; i < count; i++) {
            Cell cell = cells.get(i);
            for (int k = 0; k < 8; k++) {
                Cell neighbor = grid.cellAt(cell.cellX() + OFFSETS[k][0], cell.cellZ() + OFFSETS[k][1]);
                neighbors[i * 8 + k] = neighbor != null && neighbor.hasTasks() ? neighbor.waveIndex : -1;
            }
        }
    }

    /** One loop per participant; each returns once every cell has finished. */
    List<Runnable> participants(int participants) {
        List<Runnable> loops = new ArrayList<>(participants);
        for (int i = 0; i < participants; i++) loops.add(this::participate);
        return loops;
    }

    /** The first cell failure, rethrown by the caller after the wave. */
    Throwable failure() {
        return failure.get();
    }

    private void participate() {
        while (remaining.get() > 0) {
            boolean claimed = false;
            int start = firstPending.get();
            for (int i = start; i < count; i++) {
                int state = states.get(i * STRIDE);
                if (state == DONE) {
                    if (i == start) {
                        // Advance the shared low-water mark over the finished prefix.
                        firstPending.compareAndSet(i, i + 1);
                        start = i + 1;
                    }
                    continue;
                }
                if (state != PENDING || !tryClaim(i)) continue;
                try {
                    work.get(i).run();
                } catch (Throwable t) {
                    failure.compareAndSet(null, t);
                } finally {
                    states.set(i * STRIDE, DONE);
                    remaining.decrementAndGet();
                }
                claimed = true;
                break;
            }
            if (!claimed) Thread.onSpinWait();
        }
    }

    private boolean tryClaim(int cell) {
        if (!states.compareAndSet(cell * STRIDE, PENDING, TRYING)) return false;
        for (int k = 0; k < 8; k++) {
            int neighbor = neighbors[cell * 8 + k];
            if (neighbor < 0) continue;
            while (true) {
                int state = states.get(neighbor * STRIDE);
                if (state == RUNNING || state == TRYING && neighbor < cell) {
                    states.set(cell * STRIDE, PENDING);
                    return false;
                }
                if (state != TRYING) break;
                Thread.onSpinWait(); // A higher-index claimant resolves without waiting on us.
            }
        }
        states.set(cell * STRIDE, RUNNING);
        return true;
    }
}
