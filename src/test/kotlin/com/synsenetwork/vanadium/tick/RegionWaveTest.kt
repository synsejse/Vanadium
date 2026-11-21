package com.synsenetwork.vanadium.tick

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicIntegerArray
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class RegionWaveTest {
    @Test
    fun independentRegionRunsWhileAConflictingRegionIsHeld() {
        for (claiming in listOf(false, true)) {
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val active = AtomicInteger()
            val completed = AtomicInteger()
            val conflicting = Runnable {
                assertEquals(0, active.getAndIncrement(), "conflicting regions overlapped")
                try {
                    entered.countDown()
                    assertTrue(release.await(5, TimeUnit.SECONDS), "independent work did not release the gate")
                    completed.incrementAndGet()
                } finally {
                    active.decrementAndGet()
                }
            }
            val independent = Runnable {
                try {
                    assertTrue(entered.await(5, TimeUnit.SECONDS), "conflicting work did not start")
                    completed.incrementAndGet()
                } finally {
                    release.countDown()
                }
            }
            WorkerPool(2).use { pool ->
                val work = listOf(conflicting, conflicting, independent)
                val start = intArrayOf(0, 1, 2, 2)
                val neighbors = intArrayOf(1, 0)
                if (claiming) {
                    val wave = ClaimWave(work, start, neighbors)
                    pool.runWave(wave.participants(3))
                    assertNull(wave.failure)
                } else {
                    val wave = DataflowWave(work, start, neighbors, intArrayOf(0, 1, 0))
                    pool.runWave(wave.participants(3))
                    assertNull(wave.failure)
                }
            }
            assertEquals(3, completed.get())
        }
    }

    @Test
    fun failureStillReleasesConflictsAndCompletesTheWave() {
        for (claiming in listOf(false, true)) {
            val boom = IllegalStateException("region failed")
            val completed = AtomicInteger()
            val work = listOf(Runnable { throw boom }, Runnable { completed.incrementAndGet() }, Runnable { completed.incrementAndGet() })
            val start = intArrayOf(0, 1, 3, 4)
            val neighbors = intArrayOf(1, 0, 2, 1)
            WorkerPool(2).use { pool ->
                if (claiming) {
                    val wave = ClaimWave(work, start, neighbors)
                    pool.runWave(wave.participants(3))
                    assertSame(boom, wave.failure)
                } else {
                    val wave = DataflowWave(work, start, neighbors, intArrayOf(0, 1, 0))
                    pool.runWave(wave.participants(3))
                    assertSame(boom, wave.failure)
                }
            }
            assertEquals(2, completed.get(), "failure left another region unfinished")
        }
    }

    @Test
    fun oversubscribedConflictChainParksWakesAndPreservesExclusion() {
        // 12 regions in a dense clique/chain with 12 participants forces repeated parking, selective wakes,
        // and early NO_PENDING exits (ClaimWave) while verifying exact conflict exclusion on both policies.
        for (claiming in listOf(false, true)) {
            val n = 12
            val active = AtomicIntegerArray(n)
            val ran = AtomicIntegerArray(n)
            // Build a 2-clique + path graph: regions 0..5 mutually conflict, 6..11 form a chain with 5-6 linked.
            val adj = Array(n) { ArrayList<Int>() }
            for (i in 0 until 6) for (j in 0 until 6) if (i != j) adj[i].add(j)
            for (i in 5 until n - 1) {
                adj[i].add(i + 1)
                adj[i + 1].add(i)
            }
            val start = IntArray(n + 1)
            val flat = ArrayList<Int>()
            for (i in 0 until n) {
                start[i] = flat.size
                flat.addAll(adj[i])
            }
            start[n] = flat.size
            val neighbors = flat.toIntArray()
            val colours = IntArray(n) { if (it < 6) it else (it and 1) + 6 }

            val work = List(n) { i ->
                Runnable {
                    assertEquals(0, active.getAndIncrement(i), "region $i ran twice concurrently")
                    for (nb in adj[i]) {
                        assertEquals(0, active.get(nb), "conflicting regions $i and $nb overlapped")
                    }
                    // Brief sleep forces idle participants past SPIN_ITERS into LockSupport.park.
                    Thread.sleep(1)
                    ran.incrementAndGet(i)
                    active.decrementAndGet(i)
                }
            }

            WorkerPool(11).use { pool ->
                repeat(5) {
                    for (i in 0 until n) ran.set(i, 0)
                    val wave: RegionWave = if (claiming) ClaimWave(work, start, neighbors)
                    else DataflowWave(work, start, neighbors, colours)
                    pool.runWave(wave.participants(12))
                    assertNull(wave.failure)
                    for (i in 0 until n) assertEquals(1, ran.get(i), "region $i ran ${ran.get(i)} times")
                }
            }
        }
    }

    @Test
    fun interruptedCallerStillCompletesWaveAndPreservesInterruptFlag() {
        for (claiming in listOf(false, true)) {
            val completed = AtomicInteger()
            val work = List(4) {
                Runnable {
                    val end = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(2)
                    while (System.nanoTime() < end) Thread.onSpinWait()
                    completed.incrementAndGet()
                }
            }
            val start = intArrayOf(0, 1, 3, 5, 6)
            val neighbors = intArrayOf(1, 0, 2, 1, 3, 2)
            val colours = intArrayOf(0, 1, 0, 1)
            WorkerPool(3).use { pool ->
                Thread.currentThread().interrupt()
                try {
                    val wave: RegionWave = if (claiming) ClaimWave(work, start, neighbors)
                    else DataflowWave(work, start, neighbors, colours)
                    pool.runWave(wave.participants(4))
                    assertNull(wave.failure)
                    assertTrue(Thread.currentThread().isInterrupted, "caller interrupt flag was lost")
                } finally {
                    Thread.interrupted()
                }
            }
            assertEquals(4, completed.get())
            assertFalse(Thread.currentThread().isInterrupted)
        }
    }
}
