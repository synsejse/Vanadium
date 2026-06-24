package com.synsenetwork.vanadium.chunk;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ChunkLockTableTest {

    @Test
    void sameChunkLockIsMutuallyExclusiveEvenWhileEvicting() throws InterruptedException {
        ChunkLockTable table = new ChunkLockTable();
        AtomicInteger inside = new AtomicInteger();
        AtomicBoolean overlap = new AtomicBoolean(false);
        AtomicInteger ops = new AtomicInteger();
        AtomicBoolean stop = new AtomicBoolean(false);

        // Hammer eviction concurrently to stress the reserve-vs-evict race.
        Thread evictor = new Thread(() -> {
            while (!stop.get()) {
                table.evictIdle();
            }
        });
        evictor.start();

        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch done = new CountDownLatch(threads);
        for (int t = 0; t < threads; t++) {
            pool.submit(() -> {
                try {
                    for (int i = 0; i < 5000; i++) {
                        ChunkLockTable.Held h = table.lock(42L, 0);
                        try {
                            if (inside.incrementAndGet() != 1) {
                                overlap.set(true);
                            }
                            ops.incrementAndGet();
                            inside.decrementAndGet();
                        } finally {
                            table.unlock(h);
                        }
                    }
                } finally {
                    done.countDown();
                }
            });
        }

        assertTrue(done.await(30, TimeUnit.SECONDS), "workers did not finish in time");
        stop.set(true);
        evictor.join(2000);
        pool.shutdownNow();

        assertFalse(overlap.get(), "two threads were inside the same chunk lock at once");
        assertEquals(threads * 5000, ops.get());
    }

    @Test
    void differentChunksDoNotBlockEachOther() throws InterruptedException {
        ChunkLockTable table = new ChunkLockTable();
        ChunkLockTable.Held a = table.lock(1L, 0);

        AtomicBoolean acquired = new AtomicBoolean(false);
        Thread other = new Thread(() -> {
            ChunkLockTable.Held b = table.lock(2L, 0);
            acquired.set(true);
            table.unlock(b);
        });
        other.start();
        other.join(2000);

        assertTrue(acquired.get(), "locking a different chunk blocked on an unrelated chunk's lock");
        table.unlock(a);
    }

    @Test
    void idleLocksAreEvicted() {
        ChunkLockTable table = new ChunkLockTable();
        ChunkLockTable.Held h = table.lock(7L, 0);
        table.unlock(h);
        assertEquals(1, table.size(), "lock is retained until eviction runs");
        table.evictIdle();
        assertEquals(0, table.size(), "idle lock was not evicted");
    }

    @Test
    void heldLocksAreNeverEvicted() {
        ChunkLockTable table = new ChunkLockTable();
        ChunkLockTable.Held h = table.lock(9L, 0);
        table.evictIdle();
        assertEquals(1, table.size(), "a held lock must not be evicted");
        table.unlock(h);
        table.evictIdle();
        assertEquals(0, table.size());
    }

    @Test
    void lockingASquareReservesAndReleasesEveryChunk() {
        ChunkLockTable table = new ChunkLockTable();
        ChunkLockTable.Held h = table.lock(0L, 1); // 3x3 square = 9 chunks
        assertEquals(9, table.size());
        table.unlock(h);
        table.evictIdle();
        assertEquals(0, table.size());
    }
}
