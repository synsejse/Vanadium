package com.synsenetwork.vanadium.chunk

import com.synsenetwork.vanadium.tick.WorkerPool
import org.junit.jupiter.api.Assertions.assertTimeoutPreemptively
import org.junit.jupiter.api.function.ThrowingSupplier
import java.time.Duration
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.function.Supplier
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A load started while another is in progress, as a chunk-load callback (entity or POI loading, a mod's load event)
 * does when it asks for a neighbour: on the server thread inside the outer load, on a worker that hands it to the
 * server thread, and on another thread the outer load waits for. The per-chunk load locks behaved exactly as the
 * single monitor they replaced in each case (the `parallelChunkLoads = false` mode, compared here before it was
 * removed): same loads, same order, same threads.
 */
class ChunkLoadReentrancyTest {
    /** One load's trace entry: which load ran, and whether it ran on the stand-in server thread. */
    private data class Ran(val what: String, val onServer: Boolean)

    private class Loads {
        @Volatile
        var server: Thread? = null
        val locks = ChunkLockTable()
        val router = ChunkLoadRouter(locks) { Thread.currentThread() === server }
        val trace: MutableList<Ran> = Collections.synchronizedList(ArrayList())

        fun <T> load(pos: Long, what: String, body: () -> T): T = router.load(pos, Supplier {
            trace += Ran(what, Thread.currentThread() === server)
            body()
        })

        /** Every reservation was dropped: an idle table evicts to nothing. */
        fun checkReleased() {
            locks.evictIdle()
            assertEquals(0, locks.size, "a load lock stayed reserved")
        }
    }

    @Test
    fun loadsStartedInsideALoadOnTheServerThreadRunInPlace() {
        val system = Loads()
        val stop = AtomicBoolean()
        // The maintenance thread's eviction must never drop a lock the nested loads still hold.
        val evictor = thread(name = "evictor") { while (!stop.get()) system.locks.evictIdle() }
        try {
            val result = onServerThread(system) {
                system.load(A, "A") {
                    // A callback during A's load asks for B, whose own callback asks for A again.
                    val nested = system.load(B, "B") { system.load(A, "A again") { "a" } + "b" }
                    system.trace += Ran("A done", true)
                    nested
                }
            }
            assertEquals("ab", result)
        } finally {
            stop.set(true)
            evictor.join(STUCK_MILLIS)
        }
        system.checkReleased()
        assertEquals(listOf(Ran("A", true), Ran("B", true), Ran("A again", true), Ran("A done", true)), system.trace)
    }

    @Test
    fun aWorkersLoadDuringTheServerThreadsLoadRunsOnTheServerThreadAfterIt() {
        val system = Loads()
        val insideA = CountDownLatch(1)
        val start = CyclicBarrier(2)
        val task = Runnable {
            start.await(STUCK_MILLIS, TimeUnit.MILLISECONDS)
            if (WorkerPool.isWorkerThread()) {
                // Asked while the server thread is inside A's load; the request's own callback loads D.
                check(insideA.await(STUCK_MILLIS, TimeUnit.MILLISECONDS)) { "the server thread never loaded A" }
                check(system.load(C, "C") { system.load(D, "D") { "d" } } == "d")
            } else {
                system.load(A, "A") {
                    insideA.countDown()
                    // The worker's request waits for this task to end: the caller serves it only between tasks.
                    awaitTrue("the worker's request") { WorkerPool.callerHasRequests() }
                    system.load(B, "B") {}
                    system.trace += Ran("A done", true)
                }
            }
        }
        onServerThread(system) { WorkerPool(1).use { pool -> pool.runWave(listOf(task, task)) } }
        system.checkReleased()
        assertEquals(listOf("A", "B", "A done", "C", "D").map { Ran(it, true) }, system.trace)
    }

    @Test
    fun anotherThreadsLoadDuringTheServerThreadsLoadTakesNoLock() {
        val system = Loads()
        onServerThread(system) {
            system.load(A, "A") {
                // The outer load waits for another thread's loads of the same and a different chunk, as it would for
                // a chunk-system thread whose callback looks one up: neither may wait for the server thread's lock.
                val other = thread(name = "chunk-system") {
                    system.load(A, "A elsewhere") { system.load(B, "B elsewhere") {} }
                }
                other.join(STUCK_MILLIS)
                check(!other.isAlive) { "a load off the server thread waited for its lock" }
                system.trace += Ran("A done", true)
            }
        }
        system.checkReleased()
        assertEquals(listOf(Ran("A", true), Ran("A elsewhere", false), Ran("B elsewhere", false), Ran("A done", true)), system.trace)
    }

    /** Runs [body] on a thread standing in for the server thread, failing instead of hanging. */
    private fun <T> onServerThread(system: Loads, body: () -> T): T =
        assertTimeoutPreemptively(Duration.ofSeconds(WAVE_SECONDS), ThrowingSupplier {
            system.server = Thread.currentThread()
            body()
        })

    private fun awaitTrue(what: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(STUCK_MILLIS)
        while (!condition()) {
            check(System.nanoTime() < deadline) { "timed out waiting for $what" }
            Thread.onSpinWait()
        }
    }

    private companion object {
        // Packed positions: adjacent, negative and far apart.
        const val A = 0x0000_0007_FFFF_FFFEL
        const val B = 0x0000_0007_FFFF_FFFFL
        const val C = -1L
        const val D = 0x7FFF_FFFF_0000_0000L

        const val STUCK_MILLIS = 3_000L
        const val WAVE_SECONDS = 20L
    }
}
