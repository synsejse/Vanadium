package com.synsenetwork.vanadium.chunk

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ChunkLockTableTest {
    @Test
    fun sameChunkLockIsMutuallyExclusiveEvenWhileEvicting() {
        val table = ChunkLockTable()
        val inside = AtomicInteger()
        val overlap = AtomicBoolean(false)
        val ops = AtomicInteger()
        val stop = AtomicBoolean(false)
        // Hammer eviction concurrently to stress the reserve-vs-evict race.
        val evictor = thread { while (!stop.get()) table.evictIdle() }
        val threads = 8
        val pool = Executors.newFixedThreadPool(threads)
        val done = CountDownLatch(threads)
        repeat(threads) {
            pool.submit {
                try {
                    repeat(5000) {
                        val held = table.lock(42L)
                        try {
                            if (inside.incrementAndGet() != 1) overlap.set(true)
                            ops.incrementAndGet()
                            inside.decrementAndGet()
                        } finally {
                            table.unlock(held)
                        }
                    }
                } finally {
                    done.countDown()
                }
            }
        }
        assertTrue(done.await(30, TimeUnit.SECONDS), "workers did not finish in time")
        stop.set(true)
        evictor.join(2000)
        pool.shutdownNow()
        assertFalse(overlap.get(), "two threads were inside the same chunk lock at once")
        assertEquals(threads * 5000, ops.get())
    }

    @Test
    fun differentChunksDoNotBlockEachOther() {
        val table = ChunkLockTable()
        val held = table.lock(1L)
        val acquired = AtomicBoolean(false)
        thread {
            val other = table.lock(2L)
            acquired.set(true)
            table.unlock(other)
        }.join(2000)
        assertTrue(acquired.get(), "locking a different chunk blocked on an unrelated chunk's lock")
        table.unlock(held)
    }

    @Test
    fun idleLocksAreEvictedButHeldLocksAreNot() {
        val table = ChunkLockTable()
        table.unlock(table.lock(7L))
        assertEquals(1, table.size, "lock is retained until eviction runs")
        val held = table.lock(9L)
        table.evictIdle()
        assertEquals(1, table.size, "only the idle lock is evicted")
        table.unlock(held)
        table.evictIdle()
        assertEquals(0, table.size)
    }
}
