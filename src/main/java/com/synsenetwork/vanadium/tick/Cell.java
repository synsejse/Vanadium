package com.synsenetwork.vanadium.tick;

import java.util.ArrayList;
import java.util.List;

/**
 * One grid square's worth of queued tick tasks. Runs them serially on the calling thread. Reused
 * across ticks: {@link #clearTasks()} empties the task list (retaining capacity) without discarding
 * the cell, and {@link #lastActiveTick()} drives idle eviction in {@link CellGrid}.
 */
public final class Cell implements Runnable {
    private final CellPos pos;
    private final List<Runnable> tasks = new ArrayList<>();
    private long lastActiveTick;

    public Cell(CellPos pos) {
        this.pos = pos;
    }

    public CellPos pos() {
        return pos;
    }

    public int color() {
        return pos.color();
    }

    public void add(Runnable task) {
        tasks.add(task);
    }

    public boolean hasTasks() {
        return !tasks.isEmpty();
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
}
