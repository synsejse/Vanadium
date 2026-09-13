package com.synsenetwork.vanadium.tick;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * One grid square's worth of queued tick tasks. Runs them serially on the calling thread. Reused
 * across ticks: {@link #clearTasks()} empties the task list (retaining capacity) without discarding
 * the cell, and {@link #lastActiveTick()} drives idle eviction in {@link CellGrid}.
 */
public final class Cell implements Runnable {
    private final int color;
    private final int cellX;
    private final int cellZ;
    private final List<Runnable> tasks = new ArrayList<>();
    private long lastActiveTick;

    /** Coordinates are in cell units. Cache their parity for the four-color grid. */
    public Cell(int cellX, int cellZ) {
        this.cellX = cellX;
        this.cellZ = cellZ;
        this.color = Math.floorMod(cellX, 2) + 2 * Math.floorMod(cellZ, 2);
    }

    public int color() {
        return color;
    }

    public int cellX() { return cellX; }

    public int cellZ() { return cellZ; }

    public void add(Runnable task) {
        tasks.add(task);
    }

    public boolean hasTasks() {
        return !tasks.isEmpty();
    }

    public int taskCount() {
        return tasks.size();
    }

    public void clearTasks() {
        tasks.clear();
    }

    public long lastActiveTick() {
        return lastActiveTick;
    }

    public void markActive(long tick) {
        this.lastActiveTick = tick;
    }

    @Override
    public void run() {
        for (Runnable task : tasks) {
            task.run();
        }
    }

    /** Detailed diagnostics only; the normal tick loop does not invoke a per-task observer. */
    void run(Consumer<Runnable> beforeTask) {
        for (Runnable task : tasks) {
            beforeTask.accept(task);
            task.run();
        }
    }
}
