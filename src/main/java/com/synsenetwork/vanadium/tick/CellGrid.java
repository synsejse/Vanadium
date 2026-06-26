package com.synsenetwork.vanadium.tick;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;

import java.util.ArrayList;
import java.util.List;

/**
 * The cells for one stage of one world. Cells and their task lists are reused across ticks; each tick
 * the cells that receive work are recorded in four reusable color buckets, so a wave allocates nothing.
 * Cells idle for {@link #IDLE_EVICT_TICKS} ticks are evicted. Single-threaded by contract — enqueue and
 * the tick boundaries run on the server thread; workers read cells only during a wave.
 */
public final class CellGrid {
    static final int IDLE_EVICT_TICKS = 100;
    private static final int COLORS = 4;

    private final int cellSize;
    private final Long2ObjectMap<Cell> cells = new Long2ObjectOpenHashMap<>();
    private final List<Cell>[] buckets;
    private long tick;

    @SuppressWarnings("unchecked")
    public CellGrid(int cellSize) {
        this.cellSize = cellSize;
        this.buckets = new List[COLORS];
        for (int i = 0; i < COLORS; i++) {
            buckets[i] = new ArrayList<>();
        }
    }

    /** The cell owning the chunk at (chunkX, chunkZ), created on first use. */
    public Cell cellFor(int chunkX, int chunkZ) {
        int cellX = Math.floorDiv(chunkX, cellSize);
        int cellZ = Math.floorDiv(chunkZ, cellSize);
        long key = pack(cellX, cellZ);
        Cell cell = cells.get(key);
        if (cell == null) {
            cell = new Cell(new CellPos(cellX, cellZ));
            cells.put(key, cell);
        }
        return cell;
    }

    /** Queues a task into the cell owning (chunkX, chunkZ) and records the cell active this tick. */
    public void enqueue(int chunkX, int chunkZ, Runnable task) {
        Cell cell = cellFor(chunkX, chunkZ);
        if (!cell.hasTasks()) {
            buckets[cell.color()].add(cell);
        }
        cell.markActive(tick);
        cell.add(task);
    }

    /** The cells of {@code color} that have work this tick. */
    public List<Cell> cellsWithColor(int color) {
        return buckets[color];
    }

    /** Starts a new tick: ages and evicts idle cells, then clears per-tick state. */
    public void beginTick() {
        tick++;
        cells.values().removeIf(cell -> tick - cell.lastActiveTick() > IDLE_EVICT_TICKS);
        resetActive();
    }

    /** Clears per-tick state after a stage's waves; keeps the cell objects for reuse. */
    public void endTick() {
        resetActive();
    }

    /** True when no cell has work this tick. */
    public boolean isEmpty() {
        for (List<Cell> bucket : buckets) {
            if (!bucket.isEmpty()) {
                return false;
            }
        }
        return true;
    }

    /** Number of retained (live) cells. Visible for tests. */
    int size() {
        return cells.size();
    }

    private void resetActive() {
        for (List<Cell> bucket : buckets) {
            for (Cell cell : bucket) {
                cell.clearTasks();
            }
            bucket.clear();
        }
    }

    private static long pack(int x, int z) {
        return ((long) x << 32) | (z & 0xFFFFFFFFL);
    }
}
