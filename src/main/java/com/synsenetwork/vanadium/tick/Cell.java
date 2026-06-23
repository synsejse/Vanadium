package com.synsenetwork.vanadium.tick;

import java.util.ArrayList;
import java.util.List;

/** One grid square's worth of queued tick tasks. Runs them serially on the calling thread. */
public final class Cell {
    private final CellPos pos;
    private final List<Runnable> tasks = new ArrayList<>();

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

    public void run() {
        for (Runnable task : tasks) {
            task.run();
        }
    }
}
