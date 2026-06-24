package com.synsenetwork.vanadium.chunk;

import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

/**
 * A table of per-chunk reentrant locks keyed by packed chunk position. A caller can lock a square of
 * chunks (sorted into a global order so concurrent callers can never deadlock) while loading them.
 *
 * <p>Locks are reference-counted, and a daemon thread evicts any lock with no holders or waiters, so
 * the table stays bounded no matter how much of the world is explored. The reservation count is
 * incremented atomically with the lock's presence in the map, so the evictor can never drop a lock
 * that another thread is about to acquire; {@link #unlock} releases the held lock before dropping its
 * reservation, so a freed lock is never re-handed-out while still held.
 */
public final class ChunkLockTable {

    private static final long EVICT_INTERVAL_MS = TimeUnit.SECONDS.toMillis(30);

    private final Map<Long, CountedLock> locks = new ConcurrentHashMap<>();

    public ChunkLockTable() {
        Thread evictor = new Thread(this::evictLoop, "Vanadium-ChunkLock-Evictor");
        evictor.setDaemon(true);
        evictor.start();
    }

    /** An opaque handle to the locks acquired by one {@link #lock} call; pass it back to {@link #unlock}. */
    public static final class Held {
        private final CountedLock[] held;

        private Held(CountedLock[] held) {
            this.held = held;
        }
    }

    private static final class CountedLock {
        final ReentrantLock lock = new ReentrantLock();
        final AtomicInteger refs = new AtomicInteger();
    }

    /** Locks the {@code (2*radius+1)^2} square of chunks centred on {@code chunkPos}. */
    public Held lock(long chunkPos, int radius) {
        int side = 1 + radius * 2;
        long[] keys = new long[side * side];
        int n = 0;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                keys[n++] = chunkPos + packOffset(dx, dz);
            }
        }
        Arrays.sort(keys); // a single global order means concurrent callers cannot deadlock

        CountedLock[] held = new CountedLock[keys.length];
        for (int i = 0; i < keys.length; i++) {
            // Reserve (refs++) atomically with the lock's presence in the map, so the evictor cannot
            // remove it between us finding it and acquiring it.
            CountedLock cl = locks.compute(keys[i], (key, existing) -> {
                CountedLock c = (existing != null) ? existing : new CountedLock();
                c.refs.incrementAndGet();
                return c;
            });
            cl.lock.lock();
            held[i] = cl;
        }
        return new Held(held);
    }

    /** Releases the locks from a {@link #lock} call, in reverse acquisition order. */
    public void unlock(Held handle) {
        CountedLock[] held = handle.held;
        for (int i = held.length - 1; i >= 0; i--) {
            // Unlock before dropping the reservation: the lock must stay reserved (un-evictable) until
            // it is actually free, or a fresh acquirer could enter while we still hold it.
            held[i].lock.unlock();
            held[i].refs.decrementAndGet();
        }
    }

    /** Removes every lock that currently has no holders or waiters. Atomic per key against {@link #lock}. */
    void evictIdle() {
        for (Long key : locks.keySet()) {
            locks.computeIfPresent(key, (k, c) -> c.refs.get() == 0 ? null : c);
        }
    }

    /** Number of locks currently retained. Visible for tests. */
    int size() {
        return locks.size();
    }

    private void evictLoop() {
        while (true) {
            try {
                Thread.sleep(EVICT_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            evictIdle();
        }
    }

    /** Packs a chunk offset exactly as Minecraft's {@code ChunkPos.toLong}, to match historical keys. */
    private static long packOffset(int dx, int dz) {
        return (dx & 0xFFFFFFFFL) | ((dz & 0xFFFFFFFFL) << 32);
    }
}
