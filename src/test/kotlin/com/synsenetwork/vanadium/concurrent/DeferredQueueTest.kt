package com.synsenetwork.vanadium.concurrent

import com.synsenetwork.vanadium.tick.WorkerPool
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.function.Consumer
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

class DeferredQueueTest {
    /** Adds [perThread] items from each of [threads] threads, as wave tasks would, and waits for them. */
    private fun fill(queue: DeferredQueue<Int>, threads: Int, perThread: Int, keepAlive: CountDownLatch? = null): List<Thread> {
        val added = CountDownLatch(threads)
        val workers = List(threads) { t ->
            thread {
                val local = queue.register(Thread.currentThread())
                repeat(perThread) { local.add(t * perThread + it) }
                added.countDown()
                keepAlive?.await(10, TimeUnit.SECONDS)
            }
        }
        assertTrue(added.await(10, TimeUnit.SECONDS), "adding threads timed out")
        return workers
    }

    private fun counter() = ConcurrentHashMap<Int, AtomicInteger>()

    private fun ConcurrentHashMap<Int, AtomicInteger>.record() = Consumer<Int> { getOrPut(it, ::AtomicInteger).incrementAndGet() }

    private fun ConcurrentHashMap<Int, AtomicInteger>.assertEachOnce(expected: Collection<Int>) {
        assertEquals(expected.toSet(), keys, "applied items")
        assertTrue(values.all { it.get() == 1 }, "an item applied twice: ${filterValues { it.get() != 1 }}")
    }

    @Test
    fun fewItemsApplyInlineWithoutAWave() {
        val queue = DeferredQueue<Int>(sliceSize = 4, parallelFrom = 100)
        val release = CountDownLatch(1)
        val threads = fill(queue, 3, 10, release)
        val inline = counter()
        val rounds = queue.drain(inline.record(), { fail("parallel apply for a small drain") }, { fail("wave for a small drain") })
        release.countDown()
        threads.forEach { it.join(10_000) }
        assertEquals(0, rounds)
        inline.assertEachOnce((0 until 30).toList())
        assertEquals(0, queue.pending())
        // A second drain finds nothing: the lists were emptied, not re-applied.
        assertEquals(0, queue.drain({ fail("re-applied $it") }, { fail("re-applied $it") }, null))
    }

    @Test
    fun manyItemsApplyAsOneWaveOfSlices() {
        val queue = DeferredQueue<Int>(sliceSize = 8, parallelFrom = 50)
        val release = CountDownLatch(1)
        val threads = fill(queue, 4, 30, release)
        val parallel = counter()
        val sliceCounts = ArrayList<Int>()
        WorkerPool(3).use { pool ->
            val rounds = queue.drain({ fail("inline apply for a large drain") }, parallel.record()) { slices ->
                sliceCounts.add(slices.size)
                pool.runWave(slices)
            }
            assertEquals(1, rounds)
        }
        release.countDown()
        threads.forEach { it.join(10_000) }
        parallel.assertEachOnce((0 until 120).toList())
        assertEquals(listOf(4 * 4), sliceCounts, "four lists of 30 make four slices each")
    }

    @Test
    fun withoutAPoolEveryRoundIsInline() {
        val queue = DeferredQueue<Int>(sliceSize = 8, parallelFrom = 10)
        val release = CountDownLatch(1)
        val threads = fill(queue, 2, 40, release)
        val inline = counter()
        assertEquals(0, queue.drain(inline.record(), { fail("parallel apply without a pool") }, null))
        release.countDown()
        threads.forEach { it.join(10_000) }
        inline.assertEachOnce((0 until 80).toList())
    }

    /** Applying an item may defer another (a push whose override pushes someone else): it lands in a later round. */
    @Test
    fun itemsDeferredWhileApplyingApplyInALaterRound() {
        val queue = DeferredQueue<Int>(sliceSize = 4, parallelFrom = 40)
        val release = CountDownLatch(1)
        val threads = fill(queue, 2, 40, release)
        val locals = ThreadLocal.withInitial { queue.register(Thread.currentThread()) }
        val parallel = counter()
        val inline = counter()
        val record = parallel.record()
        WorkerPool(3).use { pool ->
            val rounds = queue.drain(inline.record(), { item ->
                record.accept(item)
                if (item < 20) locals.get().add(1_000 + item)
            }, pool::runWave)
            assertEquals(1, rounds, "20 follow-up items are below the parallel threshold")
        }
        release.countDown()
        threads.forEach { it.join(10_000) }
        parallel.assertEachOnce((0 until 80).toList())
        inline.assertEachOnce((1_000 until 1_020).toList())
        assertEquals(0, queue.pending())
    }

    @Test
    fun parallelRoundsAreBounded() {
        val queue = DeferredQueue<Int>(sliceSize = 2, parallelFrom = 4)
        val local = queue.register(null)
        repeat(8) { local.add(it) }
        val inlineApplied = AtomicInteger()
        // Every item defers another forever in parallel rounds; inline application ends it.
        val rounds = queue.drain({ inlineApplied.incrementAndGet() }, { item -> synchronized(local) { local.add(item) } }) { slices ->
            slices.forEach(Runnable::run)
        }
        assertEquals(4, rounds)
        assertEquals(8, inlineApplied.get())
        assertEquals(0, queue.pending())
    }

    @Test
    fun listsOfFinishedThreadsArePruned() {
        val queue = DeferredQueue<Int>()
        val caller = queue.register(null)
        fill(queue, 3, 1).forEach { it.join(10_000) }
        assertEquals(4, queue.registered)
        val inline = counter()
        queue.drain(inline.record(), { fail("parallel") }, null) // Takes their items, leaving the lists empty.
        queue.drain(inline.record(), { fail("parallel") }, null) // Prunes the empty lists of dead threads.
        inline.assertEachOnce(listOf(0, 1, 2))
        assertEquals(1, queue.registered, "only the caller's list remains")
        caller.add(7)
        queue.drain(inline.record(), { fail("parallel") }, null)
        assertTrue(inline.containsKey(7))
    }
}
