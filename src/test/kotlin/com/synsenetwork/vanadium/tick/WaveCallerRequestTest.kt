package com.synsenetwork.vanadium.tick

import org.junit.jupiter.api.Assertions.assertTimeoutPreemptively
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit
import java.util.function.Supplier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** [WorkerPool.callOnWaveCaller]: a helper's work that only the wave's caller can do. */
class WaveCallerRequestTest {
    @Test
    fun runsInlineOffHelpers() {
        val caller = Thread.currentThread()
        assertSame(caller, WorkerPool.callOnWaveCaller(Supplier { Thread.currentThread() }))
        WorkerPool(1).use { pool ->
            val ranOn = ConcurrentHashMap<String, Thread>()
            val start = CyclicBarrier(2)
            pool.runWave(List(2) {
                Runnable {
                    start.await(5, TimeUnit.SECONDS)
                    if (!WorkerPool.isWorkerThread()) ranOn["caller"] = WorkerPool.callOnWaveCaller(Supplier { Thread.currentThread() })
                }
            })
            assertSame(caller, ranOn["caller"], "the caller's own request did not run in place")
        }
    }

    @Test
    fun failuresReachTheRequesterAndWorkIsNotDeferrable() {
        val outcomes = ConcurrentHashMap<String, Any>()
        val start = CyclicBarrier(2)
        val task = Runnable {
            start.await(5, TimeUnit.SECONDS)
            if (!WorkerPool.isWorkerThread()) return@Runnable
            outcomes["failure"] = runCatching {
                WorkerPool.callOnWaveCaller(Supplier<Unit> { throw IllegalStateException("Chunk not there when requested") })
            }.exceptionOrNull()!!
            // A deferrable task's rerun on the caller is the work it could not defer: it must not unwind again.
            outcomes["deferrable"] = WorkerPool.runDeferrable {
                outcomes["signal"] = WorkerPool.callOnWaveCaller(Supplier {
                    WorkerPool.deferToServerThread("chunk")?.let { return@Supplier "deferred" } ?: "ran"
                })
            }
        }
        WorkerPool(1).use { pool ->
            assertTimeoutPreemptively(Duration.ofSeconds(20)) {
                pool.runWave(listOf(task, task))
                assertFalse(WorkerPool.callerHasRequests())
            }
        }
        val failure = outcomes["failure"] as Throwable
        assertTrue(failure is IllegalStateException && failure.message == "Chunk not there when requested", "$failure")
        assertEquals("ran", outcomes["signal"])
        assertEquals(true, outcomes["deferrable"])
    }

    @Test
    fun nestedWaveTailServesTheOuterWavesHelpers() {
        val served = ConcurrentHashMap<String, Thread>()
        val outerStart = CyclicBarrier(2)
        var server: Thread? = null
        WorkerPool(1).use { outer ->
            WorkerPool(1).use { inner ->
                val innerStart = CyclicBarrier(2)
                val outerTask = Runnable {
                    outerStart.await(5, TimeUnit.SECONDS)
                    if (WorkerPool.isWorkerThread()) {
                        served["outer"] = WorkerPool.callOnWaveCaller(Supplier { Thread.currentThread() })
                        return@Runnable
                    }
                    awaitTrue { WorkerPool.callerHasRequests() }
                    // The caller is now in another pool's wave: its tail must serve the outer helper too.
                    inner.runWave(List(2) {
                        Runnable {
                            innerStart.await(5, TimeUnit.SECONDS)
                            if (WorkerPool.isWorkerThread()) awaitTrue { served.containsKey("outer") }
                        }
                    })
                }
                assertTimeoutPreemptively(Duration.ofSeconds(20)) {
                    server = Thread.currentThread()
                    outer.runWave(listOf(outerTask, outerTask))
                }
            }
        }
        assertSame(server, served["outer"], "the outer helper's request ran on ${served["outer"]}")
    }

    private fun awaitTrue(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!condition()) {
            check(System.nanoTime() < deadline) { "timed out" }
            Thread.onSpinWait()
        }
    }
}
