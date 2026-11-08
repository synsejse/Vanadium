package com.synsenetwork.vanadium.tick;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.world.level.ChunkPos;

/**
 * Two-phase stage collection for large populations. The server thread first only records items
 * (no dereference of the item itself). Workers then map contiguous slices to a packed chunk key and
 * a task, writing into index-aligned arrays. Finally the server thread enqueues those results in
 * the original order, so per-cell insertion order is unchanged, while the item reads that miss the
 * server thread's cache (entity and chunk fields last written by workers) happen in parallel.
 *
 * <p>Server-thread only, apart from the mapping jobs, which write disjoint array slots between
 * the recording and enqueue steps. Not reentrant.
 */
public final class BatchEnqueue<T> {
    /** Key for items that must run on the server thread, in order, before the stage's wave. */
    public static final long SERIAL = Long.MIN_VALUE;
    /** Key for items that need no work this stage. */
    public static final long SKIP = Long.MIN_VALUE + 1;
    private static final int MIN_PARALLEL_ITEMS = 512;
    private static final int ITEMS_PER_JOB = 256;

    public interface Mapper<T> {
        /** A packed chunk position, {@link #SERIAL} or {@link #SKIP}. May run on a worker. */
        long key(T item);

        /** The cell task for an item with a chunk key. May run on a worker. */
        Runnable task(T item);
    }

    private final Mapper<T> mapper;
    private Object[] items = new Object[256];
    private long[] keys = new long[256];
    private Runnable[] tasks = new Runnable[256];
    private int count;
    private final List<Slice> slices = new ArrayList<>();

    public BatchEnqueue(Mapper<T> mapper) {
        this.mapper = mapper;
    }

    public void add(T item) {
        if (count == items.length) {
            int capacity = items.length * 2;
            items = Arrays.copyOf(items, capacity);
            keys = Arrays.copyOf(keys, capacity);
            tasks = Arrays.copyOf(tasks, capacity);
        }
        items[count++] = item;
    }

    /**
     * Maps every recorded item, runs SERIAL items through {@code serial} in recording order, and
     * enqueues the rest into {@code stage}. Clears the batch, including on failure.
     */
    @SuppressWarnings("unchecked")
    public void dispatch(TickScheduler scheduler, Stage stage, Consumer<T> serial) {
        int n = count;
        try {
            if (n >= MIN_PARALLEL_ITEMS) {
                slices.clear();
                for (int start = 0; start < n; start += ITEMS_PER_JOB) {
                    slices.add(new Slice(start, Math.min(n, start + ITEMS_PER_JOB)));
                }
                scheduler.prepare(slices);
            } else {
                new Slice(0, n).run();
            }
            for (int i = 0; i < n; i++) {
                long key = keys[i];
                if (key == SKIP) continue;
                if (key == SERIAL) {
                    serial.accept((T) items[i]);
                } else {
                    scheduler.enqueue(stage, ChunkPos.getX(key), ChunkPos.getZ(key), tasks[i], null);
                }
            }
        } finally {
            Arrays.fill(items, 0, n, null);
            Arrays.fill(tasks, 0, n, null);
            count = 0;
            slices.clear();
        }
    }

    private final class Slice implements Runnable {
        private final int start;
        private final int end;

        Slice(int start, int end) {
            this.start = start;
            this.end = end;
        }

        @SuppressWarnings("unchecked")
        @Override public void run() {
            for (int i = start; i < end; i++) {
                T item = (T) items[i];
                long key = mapper.key(item);
                keys[i] = key;
                tasks[i] = key == SERIAL || key == SKIP ? null : mapper.task(item);
            }
        }
    }
}
