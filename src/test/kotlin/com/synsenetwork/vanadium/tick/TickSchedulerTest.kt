package com.synsenetwork.vanadium.tick

import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
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
    fun neighborhoodWaveNeverRunsAdjacentCellsTogether() {
        val pool = WorkerPool(8)
        val scheduler = TickScheduler(pool, 1)
        val size = 12
        val running = Array(size) { Array(size) { AtomicInteger() } }
        val violations = AtomicInteger()
        val executed = AtomicInteger()
        fun tasksAt(x: Int, z: Int, round: Int) = 1 + (x * 7 + z * 3 + round) % 4
        repeat(40) { round ->
            scheduler.begin(Stage.ENTITY)
            for (x in 0 until size) for (z in 0 until size) repeat(tasksAt(x, z, round)) {
                scheduler.enqueue(Stage.ENTITY, x, z, {
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
            scheduler.run(Stage.ENTITY)
        }
        val expected = (0 until 40).sumOf { round -> (0 until size).sumOf { x -> (0 until size).sumOf { z -> tasksAt(x, z, round) } } }
        assertEquals(expected, executed.get())
        assertEquals(0, violations.get(), "adjacent cells overlapped")
        pool.close()
    }

    @Test
    fun neighborhoodWaveKeepsInsertionOrderWithinCells() {
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
    fun neighborhoodWaveFinishesOtherCellsAndPropagatesFailure() {
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
