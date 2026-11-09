package com.synsenetwork.vanadium.tick

import org.junit.jupiter.api.Assertions.assertTimeoutPreemptively
import java.time.Duration
import java.util.LinkedList
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicIntegerArray
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class WorkerPoolTest {
    @Test
    fun closeWaitsForWorkersWithoutInterruptingThem() {
        val pool = WorkerPool(1)
        val workerStarted = CountDownLatch(1)
        val releaseWorker = CountDownLatch(1)
        val closing = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        val task = Runnable {
            if (WorkerPool.isWorkerThread()) {
                workerStarted.countDown()
                await(releaseWorker)
            } else {
                await(workerStarted)
            }
        }
        val caller = Thread {
            try {
                pool.runWave(listOf(task, task))
            } catch (t: Throwable) {
                failure.set(t)
            }
        }
        val closer = Thread {
            closing.countDown()
            pool.close()
            closed.countDown()
        }
        try {
            caller.start()
            assertTrue(workerStarted.await(5, TimeUnit.SECONDS))
            closer.start()
            assertTrue(closing.await(5, TimeUnit.SECONDS))
            assertFalse(closed.await(100, TimeUnit.MILLISECONDS), "close returned with a worker still active")
            releaseWorker.countDown()
            assertTrue(closed.await(5, TimeUnit.SECONDS))
            caller.join(5000)
            assertFalse(caller.isAlive)
            assertNull(failure.get(), "closing the pool interrupted an active worker")
        } finally {
            releaseWorker.countDown()
            caller.join(5000)
            closer.join(5000)
            pool.close()
        }
    }

    @Test
    fun interruptedCallerStillWaitsForWorkers() = checkInterruptedBarrier(interruptBeforeWait = false, failTask = false)

    @Test
    fun alreadyInterruptedCallerStillWaitsForWorkers() = checkInterruptedBarrier(interruptBeforeWait = true, failTask = false)

    @Test
    fun interruptionPreservesBarrierAndTaskFailure() = checkInterruptedBarrier(interruptBeforeWait = true, failTask = true)

    private fun checkInterruptedBarrier(interruptBeforeWait: Boolean, failTask: Boolean) {
        val pool = WorkerPool(1)
        val workerStarted = CountDownLatch(1)
        val callerTaskFinished = CountDownLatch(1)
        val releaseWorker = CountDownLatch(1)
        val returned = CountDownLatch(1)
        val workerFinished = AtomicBoolean()
        val finishedAtReturn = AtomicBoolean()
        val interruptedAtReturn = AtomicBoolean()
        val failure = AtomicReference<Throwable?>()
        val taskFailure = IllegalStateException("task failed")
        val task = Runnable {
            if (WorkerPool.isWorkerThread()) {
                workerStarted.countDown()
                await(releaseWorker)
                workerFinished.set(true)
            } else {
                await(workerStarted)
                if (interruptBeforeWait) Thread.currentThread().interrupt()
                callerTaskFinished.countDown()
                if (failTask) throw taskFailure
            }
        }
        val caller = Thread({
            try {
                pool.runWave(listOf(task, task))
            } catch (t: Throwable) {
                failure.set(t)
            } finally {
                finishedAtReturn.set(workerFinished.get())
                interruptedAtReturn.set(Thread.currentThread().isInterrupted)
                returned.countDown()
            }
        }, "wave-test-caller").apply { isDaemon = true }
        try {
            caller.start()
            assertTrue(callerTaskFinished.await(5, TimeUnit.SECONDS))
            if (!interruptBeforeWait) {
                // The caller has left its task and parked at the wave barrier.
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                while (caller.state != Thread.State.WAITING && System.nanoTime() < deadline) Thread.yield()
                assertEquals(Thread.State.WAITING, caller.state)
                caller.interrupt()
            }
            assertFalse(returned.await(100, TimeUnit.MILLISECONDS), "interruption must not let the caller leave a worker running")
            releaseWorker.countDown()
            assertTrue(returned.await(5, TimeUnit.SECONDS))
            assertTrue(finishedAtReturn.get())
            assertTrue(interruptedAtReturn.get(), "restore the interrupt flag after the barrier")
            if (failTask) {
                assertIs<RuntimeException>(failure.get())
                assertSame(taskFailure, failure.get()!!.cause)
            } else {
                assertNull(failure.get())
            }
        } finally {
            releaseWorker.countDown()
            caller.join(5000)
            pool.close()
        }
    }

    @Test
    fun taskListsCanBeClearedAndReusedAfterEachWave() {
        val pool = WorkerPool(4)
        val tasks = ArrayList<Runnable>()
        val completed = AtomicInteger()
        var expected = 0
        try {
            for (wave in 0 until 500) {
                val count = if (wave % 2 == 0) 2 else 64
                repeat(count) { tasks.add(Runnable { completed.incrementAndGet() }) }
                pool.runWave(tasks)
                tasks.clear()
                expected += count
                assertEquals(expected, completed.get())
            }
        } finally {
            pool.close()
        }
    }

    @Test
    fun supportsSequentialListsAndOtherCollections() {
        val pool = WorkerPool(2)
        val completed = AtomicInteger()
        val tasks = listOf(Runnable { completed.incrementAndGet() }, Runnable { completed.incrementAndGet() })
        try {
            pool.runWave(LinkedList(tasks))
            pool.runWave(LinkedHashSet(tasks))
            assertEquals(4, completed.get())
        } finally {
            pool.close()
        }
    }

    @Test
    fun runWaveRunsEveryTask() {
        val pool = WorkerPool(4)
        val visits = AtomicIntegerArray(200)
        pool.runWave(List(visits.length()) { i -> Runnable { visits.incrementAndGet(i) } })
        for (i in 0 until visits.length()) assertEquals(1, visits.get(i))
        pool.close()
    }

    @Test
    fun closedPoolFinishesWaveBeforeRejecting() {
        val pool = WorkerPool(1)
        val completed = AtomicInteger()
        pool.close()
        assertFailsWith<RejectedExecutionException> {
            pool.runWave(listOf(Runnable { completed.incrementAndGet() }, Runnable { completed.incrementAndGet() }))
        }
        assertEquals(2, completed.get())
    }

    @Test
    fun runWaveBlocksUntilDone() {
        val pool = WorkerPool(2)
        val done = AtomicBoolean(false)
        pool.runWave(listOf(Runnable {
            Thread.sleep(40)
            done.set(true)
        }))
        assertTrue(done.get(), "runWave returned before its task finished")
        pool.close()
    }

    @Test
    fun runWavePropagatesTaskFailure() {
        val pool = WorkerPool(2)
        assertFailsWith<IllegalStateException> { pool.runWave(listOf(Runnable { throw IllegalStateException("boom") })) }
        pool.close()
    }

    @Test
    fun emptyWaveIsANoop() {
        val pool = WorkerPool(2)
        pool.runWave(emptyList())
        pool.close()
    }

    /** The gate forces both hands to be busy at once, so the helper provably runs one task. */
    @Test
    fun poolThreadsReportAsWorkers() {
        val pool = WorkerPool(2)
        val anyOnWorker = AtomicBoolean(false)
        val gate = CyclicBarrier(2)
        val task = Runnable {
            if (WorkerPool.isWorkerThread()) anyOnWorker.set(true)
            gate.await(2, TimeUnit.SECONDS)
        }
        pool.runWave(listOf(task, task))
        assertTrue(anyOnWorker.get(), "at least one task should run on a pool worker")
        assertFalse(WorkerPool.isWorkerThread(), "main thread must not report as a worker")
        pool.close()
    }

    @Test
    fun singleTaskRunsOnCaller() {
        val pool = WorkerPool(4)
        val ranOn = AtomicReference<Thread>()
        val wasWorker = AtomicBoolean(true)
        pool.runWave(listOf(Runnable {
            ranOn.set(Thread.currentThread())
            wasWorker.set(WorkerPool.isWorkerThread())
        }))
        assertSame(Thread.currentThread(), ranOn.get(), "single-task wave must run on the caller")
        assertFalse(wasWorker.get(), "caller must not report as a worker")
        pool.close()
    }

    /** With one worker, the wave can only complete if the caller runs the second gated task. */
    @Test
    fun callerParticipates() {
        val pool = WorkerPool(1)
        val ran = ConcurrentHashMap.newKeySet<Thread>()
        val gate = CyclicBarrier(2)
        val task = Runnable {
            ran.add(Thread.currentThread())
            gate.await(2, TimeUnit.SECONDS)
        }
        // assertTimeoutPreemptively runs its lambda on another thread; record which one called runWave.
        val callerRef = AtomicReference<Thread>()
        assertTimeoutPreemptively(Duration.ofSeconds(5)) {
            callerRef.set(Thread.currentThread())
            pool.runWave(listOf(task, task))
        }
        assertTrue(callerRef.get() in ran, "caller must execute a task, not just block")
        pool.close()
    }

    @Test
    fun errorPropagatesAndOthersComplete() {
        val pool = WorkerPool(4)
        val completed = AtomicInteger()
        val tasks = MutableList(50) { Runnable { completed.incrementAndGet() } }
        tasks.add(25, Runnable { throw IllegalStateException("boom") })
        assertFailsWith<RuntimeException> { pool.runWave(tasks) }
        assertEquals(50, completed.get(), "all non-throwing tasks must still run")
        pool.close()
    }

    @Test
    fun highContentionAllRun() {
        val pool = WorkerPool(4)
        val counter = AtomicInteger()
        pool.runWave(List(10_000) { Runnable { counter.incrementAndGet() } })
        assertEquals(10_000, counter.get())
        pool.close()
    }

    @Test
    fun workersDeferTasksThatNeedTheServerThread() {
        val outcomes = ConcurrentHashMap<String, Any>()
        val workerDone = CountDownLatch(1)
        // The caller takes one task and waits; the helper runs the other, so every check runs on a worker.
        val task = Runnable {
            if (!WorkerPool.isWorkerThread()) {
                await(workerDone)
                return@Runnable
            }
            try {
                outcomes["outside"] = WorkerPool.deferToServerThread("outside") == null
                outcomes["thrown"] = WorkerPool.runDeferrable {
                    WorkerPool.deferToServerThread("chunk")?.let { throw it }
                    outcomes["continued"] = true
                }
                outcomes["swallowed"] = WorkerPool.runDeferrable {
                    try {
                        WorkerPool.deferToServerThread("chunk")?.let { throw it }
                    } catch (_: RuntimeException) {
                        // A mod that catches broadly must not hide the deferral.
                    }
                }
                outcomes["consequence"] = WorkerPool.runDeferrable {
                    WorkerPool.deferToServerThread("chunk")
                    error("null chunk after a swallowed signal")
                }
                outcomes["nested"] = WorkerPool.runDeferrable {
                    outcomes["inner"] = WorkerPool.runDeferrable { WorkerPool.deferToServerThread("inner")?.let { throw it } }
                }
                outcomes["completed"] = WorkerPool.runDeferrable {}
                outcomes["failure"] = assertFailsWith<IllegalStateException> {
                    WorkerPool.runDeferrable { error("real failure") }
                }.message!!
                outcomes["after"] = WorkerPool.deferToServerThread("after") == null
            } finally {
                workerDone.countDown()
            }
        }
        WorkerPool(1).use { pool ->
            assertTimeoutPreemptively(Duration.ofSeconds(10)) { pool.runWave(listOf(task, task)) }
        }
        assertEquals(true, outcomes["outside"], "a plain worker task was offered deferral")
        assertEquals(false, outcomes["thrown"])
        assertNull(outcomes["continued"], "the task continued past the signal")
        assertEquals(false, outcomes["swallowed"], "a swallowed signal lost the deferral")
        assertEquals(false, outcomes["consequence"], "a failure caused by the deferral was not deferred")
        assertEquals(false, outcomes["inner"])
        assertEquals(true, outcomes["nested"], "an inner deferral leaked into its enclosing task")
        assertEquals(true, outcomes["completed"])
        assertEquals("real failure", outcomes["failure"])
        assertEquals(true, outcomes["after"], "deferral stayed enabled after the task")
    }

    @Test
    fun deferrableTasksRunDirectlyOffWorkers() {
        var ran = false
        assertTrue(WorkerPool.runDeferrable {
            assertNull(WorkerPool.deferToServerThread("chunk"), "the server thread was offered deferral")
            ran = true
        })
        assertTrue(ran)
    }

    private fun await(latch: CountDownLatch) {
        assertTrue(latch.await(5, TimeUnit.SECONDS), "test gate timed out")
    }
}
