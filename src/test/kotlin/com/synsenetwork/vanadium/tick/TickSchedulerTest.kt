package com.synsenetwork.vanadium.tick

import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

class TickSchedulerTest {
    @Test
    fun runsEveryEnqueuedTaskExactlyOnce() {
        val pool = WorkerPool(4)
        val scheduler = TickScheduler(pool, 8)
        val counts = ConcurrentHashMap<String, Int>()
        scheduler.begin(Stage.CHUNK)
        for (x in 0 until 16) for (z in 0 until 16) {
            scheduler.enqueue(Stage.CHUNK, x, z, { counts.merge("$x,$z", 1, Int::plus) }, null)
        }
        scheduler.run(Stage.CHUNK)
        assertEquals(256, counts.size)
        assertTrue(counts.values.all { it == 1 })
        pool.close()
    }

    @Test
    fun runClearsStageSoNextTickStartsFresh() {
        val pool = WorkerPool(2)
        val scheduler = TickScheduler(pool, 8)
        var runs = 0
        scheduler.begin(Stage.CHUNK)
        scheduler.enqueue(Stage.CHUNK, 0, 0, { runs++ }, null)
        scheduler.run(Stage.CHUNK)
        scheduler.run(Stage.CHUNK) // nothing queued this time
        assertEquals(1, runs)
        pool.close()
    }

    @Test
    fun wavesNeverRunChunksWithinIsolationTogether() {
        val pool = WorkerPool(8)
        val scheduler = TickScheduler(pool, 1)
        val size = 12
        val running = Array(size) { Array(size) { AtomicInteger() } }
        val violations = AtomicInteger()
        val executed = AtomicInteger()
        fun tasksAt(x: Int, z: Int, round: Int) = 1 + (x * 7 + z * 3 + round) % 4
        repeat(40) { round ->
            scheduler.begin(Stage.BLOCK_ENTITY)
            for (x in 0 until size) for (z in 0 until size) repeat(tasksAt(x, z, round)) {
                scheduler.enqueue(Stage.BLOCK_ENTITY, x, z, {
                    running[x][z].incrementAndGet()
                    for (dx in -1..1) for (dz in -1..1) {
                        val nx = x + dx
                        val nz = z + dz
                        if ((dx != 0 || dz != 0) && nx in 0 until size && nz in 0 until size && running[nx][nz].get() > 0) {
                            violations.incrementAndGet()
                        }
                    }
                    Thread.onSpinWait()
                    executed.incrementAndGet()
                    running[x][z].decrementAndGet()
                }, null)
            }
            scheduler.run(Stage.BLOCK_ENTITY)
        }
        val expected = (0 until 40).sumOf { round -> (0 until size).sumOf { x -> (0 until size).sumOf { z -> tasksAt(x, z, round) } } }
        assertEquals(expected, executed.get())
        assertEquals(0, violations.get(), "chunks within isolation overlapped")
        pool.close()
    }

    @Test
    fun heavyRegionsSwitchToClaimingAndKeepIsolation() {
        val pool = WorkerPool(6)
        val scheduler = TickScheduler(pool, 1)
        val size = 6
        val running = Array(size) { Array(size) { AtomicInteger() } }
        val violations = AtomicInteger()
        val executed = AtomicInteger()
        repeat(6) {
            scheduler.begin(Stage.BLOCK_ENTITY)
            for (x in 0 until size) for (z in 0 until size) {
                scheduler.enqueue(Stage.BLOCK_ENTITY, x, z, {
                    running[x][z].incrementAndGet()
                    for (dx in -1..1) for (dz in -1..1) {
                        val nx = x + dx
                        val nz = z + dz
                        if ((dx != 0 || dz != 0) && nx in 0 until size && nz in 0 until size && running[nx][nz].get() > 0) {
                            violations.incrementAndGet()
                        }
                    }
                    // Heavy enough that, once measured, regions run by dynamic claiming.
                    val until = System.nanoTime() + 150_000
                    while (System.nanoTime() < until) Thread.onSpinWait()
                    executed.incrementAndGet()
                    running[x][z].decrementAndGet()
                }, null)
            }
            scheduler.run(Stage.BLOCK_ENTITY)
        }
        assertEquals(6 * size * size, executed.get())
        assertEquals(0, violations.get(), "chunks within isolation overlapped")
        pool.close()
    }

    @Test
    fun wavesKeepEnqueueOrderWithinChunks() {
        val pool = WorkerPool(4)
        val scheduler = TickScheduler(pool, 2)
        val seen = List(9) { Collections.synchronizedList(ArrayList<Int>()) }
        scheduler.begin(Stage.CHUNK)
        for (i in 0 until 50) for (c in 0 until 9) {
            scheduler.enqueue(Stage.CHUNK, (c % 3) * 2, (c / 3) * 2, { seen[c].add(i) }, null)
        }
        scheduler.run(Stage.CHUNK)
        for (values in seen) assertEquals((0 until 50).toList(), values)
        pool.close()
    }

    @Test
    fun participantsFollowEstimatedWork() {
        WorkerPool(15).use { pool ->
            val scheduler = TickScheduler(pool, 1, participantCostNanos = 2_000.0)
            assertEquals(16, scheduler.participants(1000, 0.0), "unknown cost must use every worker")
            assertEquals(3, scheduler.participants(3, 0.0))
            assertEquals(1, scheduler.participants(1000, 1_000.0), "a wave cheaper than one thread's cost runs inline")
            assertEquals(10, scheduler.participants(1000, 200_000.0))
            assertEquals(4, scheduler.participants(4, 200_000.0), "never more participants than tasks")
            assertEquals(16, scheduler.participants(1000, 1e9), "never more participants than threads")
            assertEquals(16, TickScheduler(pool, 1, participantCostNanos = 0.0).participants(1000, 1_000.0))
            // Every slice grows with the wave: twice the participants for four times the work.
            assertEquals(2 * scheduler.participants(1000, 50_000.0), scheduler.participants(1000, 200_000.0))
        }
    }

    @Test
    fun cheapMeasuredWavesRunOnTheCaller() {
        WorkerPool(4).use { pool ->
            // Involving a thread "costs" a second, so any measured wave runs inline; the first is unmeasured.
            val scheduler = TickScheduler(pool, 1, participantCostNanos = 1e9)
            fun regionWave(gate: CountDownLatch?): Set<Thread> {
                val threads = ConcurrentHashMap.newKeySet<Thread>()
                scheduler.begin(Stage.BLOCK_ENTITY)
                for (i in 0 until 5) scheduler.enqueue(Stage.BLOCK_ENTITY, i * 4, 0, {
                    threads.add(Thread.currentThread())
                    if (gate != null) {
                        gate.countDown()
                        check(gate.await(5, TimeUnit.SECONDS)) { "the unmeasured wave did not use every worker" }
                    }
                }, null)
                scheduler.run(Stage.BLOCK_ENTITY)
                return threads
            }
            assertEquals(5, regionWave(CountDownLatch(5)).size)
            assertEquals(setOf(Thread.currentThread()), regionWave(null))

            fun flatWave(gate: CountDownLatch?): Set<Thread> {
                val threads = ConcurrentHashMap.newKeySet<Thread>()
                scheduler.runFlat(Stage.ENTITY, List(5) {
                    Runnable {
                        threads.add(Thread.currentThread())
                        if (gate != null) {
                            gate.countDown()
                            check(gate.await(5, TimeUnit.SECONDS)) { "the unmeasured flat wave did not use every worker" }
                        }
                    }
                })
                return threads
            }
            assertEquals(5, flatWave(CountDownLatch(5)).size)
            assertEquals(setOf(Thread.currentThread()), flatWave(null))

            fun preparation(gate: CountDownLatch?): Set<Thread> {
                val threads = ConcurrentHashMap.newKeySet<Thread>()
                scheduler.prepare(Preparation.CHUNK_BATCH, List(5) {
                    Runnable {
                        threads.add(Thread.currentThread())
                        if (gate != null) {
                            gate.countDown()
                            check(gate.await(5, TimeUnit.SECONDS)) { "the unmeasured preparation did not use every worker" }
                        }
                    }
                })
                return threads
            }
            assertEquals(5, preparation(CountDownLatch(5)).size)
            assertEquals(setOf(Thread.currentThread()), preparation(null))
        }
    }

    @Test
    fun expensiveMeasuredWavesKeepEveryWorker() {
        WorkerPool(3).use { pool ->
            val scheduler = TickScheduler(pool, 1, participantCostNanos = 1.0)
            repeat(3) {
                val gate = CountDownLatch(4)
                val threads = ConcurrentHashMap.newKeySet<Thread>()
                scheduler.begin(Stage.BLOCK_ENTITY)
                for (i in 0 until 4) scheduler.enqueue(Stage.BLOCK_ENTITY, i * 4, 0, {
                    threads.add(Thread.currentThread())
                    gate.countDown()
                    check(gate.await(5, TimeUnit.SECONDS)) { "a measured wave dropped a worker" }
                    Thread.sleep(1)
                }, null)
                scheduler.run(Stage.BLOCK_ENTITY)
                assertEquals(4, threads.size)
            }
        }
    }

    @Test
    fun wavesFinishOtherRegionsAndPropagateFailure() {
        val pool = WorkerPool(4)
        val scheduler = TickScheduler(pool, 1)
        val completed = AtomicInteger()
        val boom = IllegalStateException("boom")
        scheduler.begin(Stage.CHUNK)
        for (x in 0 until 6) for (z in 0 until 6) {
            val fail = x == 2 && z == 3
            scheduler.enqueue(Stage.CHUNK, x, z, {
                if (fail) throw boom
                completed.incrementAndGet()
            }, null)
        }
        val thrown = assertFailsWith<RuntimeException> { scheduler.run(Stage.CHUNK) }
        assertSame(boom, thrown.cause)
        assertEquals(35, completed.get())
        // The stage resets even after a failure.
        var runs = 0
        scheduler.begin(Stage.CHUNK)
        scheduler.enqueue(Stage.CHUNK, 0, 0, { runs++ }, null)
        scheduler.run(Stage.CHUNK)
        assertEquals(1, runs)
        pool.close()
    }
}
