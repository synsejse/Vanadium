package com.synsenetwork.vanadium.chunk

import com.synsenetwork.vanadium.tick.WorkerPool
import org.junit.jupiter.api.Assertions.assertTimeoutPreemptively
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.function.Supplier
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The per-chunk locks exclude per chunk and survive eviction while held. A wave must not deadlock or stall on a chunk
 * load requested from inside it. The reported deadlock: a worker took a chunk's load lock and waited for a load only
 * the server thread can finish, while the server thread, a participant in the same wave, waited for that lock.
 */
class ChunkLoadLocksTest {
    // The lock tests count every thread as the server thread, so each load takes its chunk's lock.

    @Test
    fun sameChunkLockIsMutuallyExclusiveEvenWhileEvicting() {
        val locks = ChunkLoadLocks { true }
        val inside = AtomicInteger()
        val overlap = AtomicBoolean(false)
        val ops = AtomicInteger()
        val stop = AtomicBoolean(false)
        // Hammer eviction concurrently to stress the reserve-vs-evict race.
        val evictor = thread { while (!stop.get()) locks.evictIdle() }
        val threads = 8
        val pool = Executors.newFixedThreadPool(threads)
        val done = CountDownLatch(threads)
        repeat(threads) {
            pool.submit {
                try {
                    repeat(5000) {
                        locks.load(42L, Supplier {
                            if (inside.incrementAndGet() != 1) overlap.set(true)
                            ops.incrementAndGet()
                            inside.decrementAndGet()
                        })
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
        val locks = ChunkLoadLocks { true }
        val acquired = AtomicBoolean(false)
        locks.load(1L, Supplier {
            thread { locks.load(2L, Supplier { acquired.set(true) }) }.join(2000)
        })
        assertTrue(acquired.get(), "locking a different chunk blocked on an unrelated chunk's lock")
    }

    @Test
    fun idleLocksAreEvictedButHeldLocksAreNot() {
        val locks = ChunkLoadLocks { true }
        locks.load(7L, Supplier {})
        assertEquals(1, locks.size, "lock is retained until eviction runs")
        locks.load(9L, Supplier {
            locks.evictIdle()
            assertEquals(1, locks.size, "only the idle lock is evicted")
        })
        locks.evictIdle()
        assertEquals(0, locks.size)
    }
    /** A chunk that, as with C2ME, only the server thread can finish loading; elsewhere a load waits for it. */
    private class ServerOnlyChunk {
        @Volatile
        var server: Thread? = null
        val chunk = Any()
        val loads = ConcurrentLinkedQueue<Thread>()
        val finished = CountDownLatch(1)

        /** Counted when a load starts off the server thread: before the fix, the worker then held the load lock. */
        val loadingOffServer = CountDownLatch(1)

        val load = Supplier {
            val thread = Thread.currentThread()
            loads.add(thread)
            if (thread === server) {
                finished.countDown()
            } else {
                loadingOffServer.countDown()
                // Bounded, so a wave that could never finish fails instead of hanging.
                check(finished.await(STUCK_SECONDS, TimeUnit.SECONDS)) {
                    "${thread.name} waited for a load that only the server thread can finish"
                }
            }
            chunk
        }

        val locks = ChunkLoadLocks { Thread.currentThread() === server }

        /** Whether the worker's lookup is in flight: queued for the caller, or (before the fix) loading itself. */
        fun workerAsked(): Boolean = WorkerPool.callerHasRequests() || loadingOffServer.count == 0L
    }

    @Test
    fun workerAndServerThreadLoadTheSameChunkInOneWave() {
        val system = ServerOnlyChunk()
        val results = ConcurrentHashMap<String, Any>()
        val start = CyclicBarrier(2)
        // Two tasks on two threads: the worker asks first, then the server thread asks for the same chunk.
        val task = Runnable {
            start.await(STUCK_SECONDS, TimeUnit.SECONDS)
            if (WorkerPool.isWorkerThread()) {
                results["worker"] = system.locks.load(POS, system.load)
            } else {
                awaitTrue("the worker's lookup") { system.workerAsked() }
                results["server"] = system.locks.load(POS, system.load)
            }
        }
        runOnServerThread(system) { pool -> pool.runWave(listOf(task, task)) }
        assertSame(system.chunk, results["worker"])
        assertSame(system.chunk, results["server"])
        assertTrue(system.loads.all { it === system.server }, "a load ran off the server thread: ${system.loads}")
    }

    @Test
    fun workerLoadIsServedWhileTheCallerWaitsForTheTail() {
        val system = ServerOnlyChunk()
        val result = AtomicBoolean()
        val start = CyclicBarrier(2)
        val task = Runnable {
            start.await(STUCK_SECONDS, TimeUnit.SECONDS)
            // The caller's own task ends at once: nothing it does would load for the worker.
            if (WorkerPool.isWorkerThread()) result.set(system.locks.load(POS, system.load) === system.chunk)
        }
        runOnServerThread(system) { pool -> pool.runWave(listOf(task, task)) }
        assertTrue(result.get())
        assertEquals(listOf(system.server), system.loads.toList(), "the load did not run once, on the server thread")
    }

    @Test
    fun callerServesRequestsBetweenItsOwnTasks() {
        val system = ServerOnlyChunk()
        val callerTasks = AtomicInteger()
        val servedBeforeSecondTask = AtomicBoolean()
        val callerSecond = CountDownLatch(1)
        val workerAsked = AtomicBoolean()
        val start = CyclicBarrier(2)
        val claims = AtomicInteger()
        val task = Runnable {
            // The first two claims go to different threads: the first waits here until another thread claims.
            if (claims.getAndIncrement() < 2) start.await(STUCK_SECONDS, TimeUnit.SECONDS)
            if (WorkerPool.isWorkerThread()) {
                if (workerAsked.compareAndSet(false, true)) {
                    system.locks.load(POS, system.load)
                } else {
                    // Leave the remaining tasks to the caller until it has run its second one.
                    callerSecond.await(STUCK_SECONDS, TimeUnit.SECONDS)
                }
                return@Runnable
            }
            when (callerTasks.getAndIncrement()) {
                0 -> awaitTrue("the worker's lookup") { system.workerAsked() }
                1 -> {
                    servedBeforeSecondTask.set(system.finished.count == 0L)
                    callerSecond.countDown()
                }
            }
        }
        runOnServerThread(system) { pool -> pool.runWave(List(8) { task }) }
        assertTrue(callerTasks.get() >= 2, "the caller ran ${callerTasks.get()} tasks")
        assertTrue(servedBeforeSecondTask.get(), "the caller ran its next task before serving the waiting worker")
        assertEquals(listOf(system.server), system.loads.toList())
    }

    /** Runs [wave] on a thread that stands in for the server thread, failing instead of hanging if it never ends. */
    private fun runOnServerThread(system: ServerOnlyChunk, wave: (WorkerPool) -> Unit) {
        WorkerPool(1).use { pool ->
            assertTimeoutPreemptively(Duration.ofSeconds(WAVE_SECONDS)) {
                system.server = Thread.currentThread()
                wave(pool)
            }
        }
    }

    private fun awaitTrue(what: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(STUCK_SECONDS)
        while (!condition()) {
            check(System.nanoTime() < deadline) { "timed out waiting for $what" }
            Thread.onSpinWait()
        }
    }

    private companion object {
        const val POS = 0x0000_0007_FFFF_FFFEL
        const val STUCK_SECONDS = 3L
        const val WAVE_SECONDS = 20L
    }
}
