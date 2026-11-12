package com.synsenetwork.vanadium.tick

import org.junit.jupiter.api.Assertions.assertTimeoutPreemptively
import java.time.Duration
import java.util.LinkedList
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.LockSupport
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicIntegerArray
import java.util.concurrent.atomic.AtomicLong
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
    fun deferrableTasksRunDirectlyOutsideParallelWaves() {
        var ran = false
        assertFalse(WorkerPool.isParallelWaveCaller())
        assertTrue(WorkerPool.runDeferrable {
            assertNull(WorkerPool.deferToServerThread("chunk"), "the server thread was offered deferral outside a wave")
            ran = true
        })
        assertTrue(ran)
        // A one-task wave runs inline with no helpers: nothing overlaps it.
        WorkerPool(2).use { pool ->
            pool.runWave(listOf(Runnable {
                assertFalse(WorkerPool.isParallelWaveCaller(), "an inline wave counted as parallel")
                assertTrue(WorkerPool.runDeferrable { WorkerPool.deferToServerThread("chunk")?.let { throw it } })
            }))
        }
    }

    @Test
    fun callerDefersAndQueuesWorkWhileHelpersRun() {
        val outcomes = ConcurrentHashMap<String, Any>()
        val order = java.util.Collections.synchronizedList(ArrayList<String>())
        val callerDone = CountDownLatch(1)
        val workerDone = CountDownLatch(1)
        // Each participant takes one task: the caller waits for the helper's, which waits for the caller's checks.
        val task = Runnable {
            if (WorkerPool.isWorkerThread()) {
                await(callerDone)
                outcomes["workerQueued"] = true
                WorkerPool.runAfterParallelWave { order.add("worker") } // Not the caller: runs now.
                workerDone.countDown()
                return@Runnable
            }
            try {
                outcomes["caller"] = WorkerPool.isParallelWaveCaller()
                outcomes["deferred"] = WorkerPool.runDeferrable { WorkerPool.deferToServerThread("chunk")?.let { throw it } }
                WorkerPool.runAfterParallelWave {
                    order.add("first")
                    outcomes["afterIsCaller"] = WorkerPool.isParallelWaveCaller()
                    WorkerPool.runAfterParallelWave { order.add("nested") }
                }
                WorkerPool.runAfterParallelWave { order.add("second") }
                order.add("wave")
            } finally {
                callerDone.countDown()
            }
            await(workerDone)
        }
        WorkerPool(1).use { pool ->
            assertTimeoutPreemptively(Duration.ofSeconds(10)) { pool.runWave(listOf(task, task)) }
        }
        assertEquals(true, outcomes["caller"], "the wave caller was not marked during a parallel wave")
        assertEquals(false, outcomes["deferred"], "the caller ran chunk work alongside helpers")
        assertEquals(true, outcomes["workerQueued"])
        assertEquals(false, outcomes["afterIsCaller"], "queued work ran before the wave finished")
        assertEquals(listOf("wave", "worker", "first", "nested", "second"), order)
        assertFalse(WorkerPool.isParallelWaveCaller())
    }

    @Test
    fun queuedWorkFailureDoesNotHideAWaveFailure() {
        val once = java.util.concurrent.atomic.AtomicBoolean()
        val task = Runnable {
            if (!WorkerPool.isWorkerThread() && once.compareAndSet(false, true)) {
                WorkerPool.runAfterParallelWave { error("queued failure") }
                error("wave failure")
            }
        }
        WorkerPool(1).use { pool ->
            val failure = assertFailsWith<RuntimeException> { pool.runWave(listOf(task, task)) }
            assertEquals("wave failure", failure.cause?.message)
            assertEquals("queued failure", failure.suppressed.single().message)
            // State resets: the next wave and queue behave normally.
            var ran = false
            WorkerPool.runAfterParallelWave { ran = true }
            assertTrue(ran)
        }
    }

    /** Spins for a minute in every state but after a tick with slack, so parking happens only when a test asks. */
    private fun steadyPool(threads: Int) = WorkerPool(threads, SpinPolicy(60_000_000_000L, 60_000_000_000L, fixed = false))

    /** Ends a tick with an hour of slack, more than any spin: idle workers park at once. */
    private fun parkAll(pool: WorkerPool, threads: Int) {
        pool.tickEnded(SLACK, sprinting = false)
        awaitCondition("workers did not park after a tick with slack") { (0 until threads).all(pool::isParked) }
    }

    /**
     * Runs [count] tasks that each wait until all have started: the wave completes only if [count] distinct
     * threads join it, so a worker that is never woken fails it within the gate's timeout.
     */
    private fun gatedWave(pool: WorkerPool, count: Int): Set<String> {
        val started = CountDownLatch(count)
        val threads = ConcurrentHashMap.newKeySet<String>()
        pool.runWave(List(count) {
            Runnable {
                threads.add(Thread.currentThread().name)
                started.countDown()
                check(started.await(5, TimeUnit.SECONDS)) { "only ${count - started.count} of $count participants joined" }
            }
        })
        return threads
    }

    private fun workerNames(range: IntRange) = range.map { "Vanadium-Worker-${it + 1}" }.toSet()

    @Test
    fun fanOutWakesEveryHelperWhenSomeAreParkedAndSomeSpinning() {
        val threads = 8
        steadyPool(threads).use { pool ->
            parkAll(pool, threads)
            pool.tickStarted() // Nothing ran last tick: nothing is woken in advance.
            assertTrue((0 until threads).all(pool::isParked))

            // A small wave wakes only the lowest-numbered workers; the caller unparks two, they wake the third.
            val small = gatedWave(pool, 4)
            assertEquals(workerNames(0..2) + Thread.currentThread().name, small)
            assertEquals(WorkerPool.CALLER_WAKES, pool.lastCallerUnparks)
            assertTrue((0..2).none(pool::isParked), "participants went back to parking while spinning for a minute")
            assertTrue((3 until threads).all(pool::isParked), "a worker outside the small wave was woken")

            // Three spinning, five parked: every one of them must join.
            val full = gatedWave(pool, threads + 1)
            assertEquals(workerNames(0 until threads) + Thread.currentThread().name, full)
            assertTrue(pool.lastCallerUnparks <= WorkerPool.CALLER_WAKES, "the caller woke ${pool.lastCallerUnparks} workers itself")
        }
    }

    @Test
    fun fanOutWakesAFullyParkedPoolRepeatedly() {
        val threads = 12
        steadyPool(threads).use { pool ->
            repeat(5) {
                parkAll(pool, threads)
                assertEquals(workerNames(0 until threads) + Thread.currentThread().name, gatedWave(pool, threads + 1))
                assertEquals(WorkerPool.CALLER_WAKES, pool.lastCallerUnparks, "the caller did more than start the fan-out")
            }
        }
    }

    @Test
    fun parkedWorkersOutsideAWaveDoNotStartAFanOut() {
        steadyPool(8).use { pool ->
            parkAll(pool, 8)
            pool.tickStarted() // Back to spinning between waves; nothing ran last tick, so nobody is woken.
            gatedWave(pool, 4)
            assertTrue(pool.lastFanOut)
            // Workers 0..2 spin, 3..7 stay parked: a wave on 0..2 alone needs no wake-up at all.
            assertEquals(workerNames(0..2) + Thread.currentThread().name, gatedWave(pool, 4))
            assertFalse(pool.lastFanOut, "parked workers outside the wave started a fan-out")
            gatedWave(pool, 5)
            assertTrue(pool.lastFanOut, "a parked helper did not start a fan-out")
        }
    }

    @Test
    fun spinningPoolNeedsNoWakeUps() {
        steadyPool(4).use { pool ->
            gatedWave(pool, 5)
            assertEquals(workerNames(0..3) + Thread.currentThread().name, gatedWave(pool, 5))
            assertEquals(0, pool.lastCallerUnparks)
        }
    }

    @Test
    fun waveReachesAWorkerPausedBeforeAnnouncingItsPark() = checkParkRace(WorkerPool.ParkPoint.BEFORE_ANNOUNCE, threads = 1)

    @Test
    fun waveReachesAWorkerPausedBeforeRecheckingForWork() = checkParkRace(WorkerPool.ParkPoint.BEFORE_RECHECK, threads = 1)

    @Test
    fun waveReachesAWorkerPausedJustBeforeParking() = checkParkRace(WorkerPool.ParkPoint.BEFORE_PARK, threads = 1)

    @Test
    fun fanOutReachesAWorkerPausedBeforeAnnouncingItsPark() = checkParkRace(WorkerPool.ParkPoint.BEFORE_ANNOUNCE, threads = 4)

    @Test
    fun fanOutReachesAWorkerPausedBeforeRecheckingForWork() = checkParkRace(WorkerPool.ParkPoint.BEFORE_RECHECK, threads = 4)

    @Test
    fun fanOutReachesAWorkerPausedJustBeforeParking() = checkParkRace(WorkerPool.ParkPoint.BEFORE_PARK, threads = 4)

    /**
     * Holds the last worker at [point] on its way to parking while a wave is published and every other
     * participant (with [threads] > 1, the other workers parked first, so a helper wakes it, not the caller) has
     * started, then lets it go: it must still join the wave.
     */
    private fun checkParkRace(point: WorkerPool.ParkPoint, threads: Int) {
        val target = threads - 1
        val reached = CountDownLatch(1)
        val release = AtomicBoolean()
        val armed = AtomicBoolean(true)
        val pool = steadyPool(threads)
        val caller = AtomicReference<Thread>()
        try {
            pool.parkHook = WorkerPool.ParkHook { worker, at ->
                if (worker == target && at == point && armed.compareAndSet(true, false)) {
                    reached.countDown()
                    // Spin, never park: a park here would consume the wake-up's permit, which production never does.
                    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
                    while (!release.get() && System.nanoTime() < deadline) Thread.onSpinWait()
                }
            }
            pool.tickEnded(SLACK, sprinting = false)
            await(reached)
            awaitCondition("other workers did not park") { (0 until target).all(pool::isParked) }
            val count = threads + 1
            val started = CountDownLatch(count)
            val failure = AtomicReference<Throwable?>()
            val joined = ConcurrentHashMap.newKeySet<String>()
            val done = CountDownLatch(1)
            caller.set(Thread {
                try {
                    pool.runWave(List(count) {
                        Runnable {
                            joined.add(Thread.currentThread().name)
                            started.countDown()
                            check(started.await(5, TimeUnit.SECONDS)) { "the paused worker never joined" }
                        }
                    })
                } catch (t: Throwable) {
                    failure.set(t)
                } finally {
                    done.countDown()
                }
            }.apply { start() })
            // Everyone but the paused worker is inside a task: the wave and all its wake-ups have been issued.
            awaitCondition("the other participants did not start") { started.count == 1L }
            release.set(true)
            assertTrue(done.await(10, TimeUnit.SECONDS), "wave did not finish")
            assertNull(failure.get())
            assertEquals(workerNames(0 until threads) + caller.get().name, joined)
            // Workers count themselves before rechecking, so only a worker paused before that is skipped.
            val callerUnparks = if (point == WorkerPool.ParkPoint.BEFORE_ANNOUNCE && threads == 1) 0 else minOf(threads, WorkerPool.CALLER_WAKES)
            assertEquals(callerUnparks, pool.lastCallerUnparks)
        } finally {
            release.set(true)
            caller.get()?.join(5000)
            pool.close()
        }
    }

    @Test
    fun workersParkAtOnceWhenATickEndsWithSlack() {
        steadyPool(4).use { pool ->
            gatedWave(pool, 5)
            assertTrue((0..3).none(pool::isParked))
            pool.tickEnded(-1, sprinting = false) // Behind: the next tick is due now, keep spinning.
            pool.tickEnded(10_000_000L, sprinting = true) // Sprinting: likewise.
            assertFalse(awaitConditionFor(200) { (0..3).any(pool::isParked) }, "a worker parked under pressure")
            parkAll(pool, 4)
        }
    }

    @Test
    fun tickStartWakesTheWorkersTheLastTickUsed() {
        val threads = 6
        steadyPool(threads).use { pool ->
            pool.tickStarted()
            gatedWave(pool, 3) // Two helpers.
            parkAll(pool, threads)
            pool.tickStarted()
            awaitCondition("the workers of the last tick were not woken") { (0..1).none(pool::isParked) }
            assertTrue((2 until threads).all(pool::isParked), "a worker the last tick did not use was woken")
            // That tick ran nothing, so the next start wakes nobody.
            parkAll(pool, threads)
            pool.tickStarted()
            assertFalse(awaitConditionFor(200) { (0 until threads).any { !pool.isParked(it) } }, "an idle tick woke workers")
        }
    }

    /** A clock the test advances by hand; the timer polls it in short real parks. */
    private class ManualClock : PrewakeClock {
        private val now = AtomicLong()

        override fun nanoTime(): Long = now.get()

        override fun park(blocker: Any, nanos: Long) = LockSupport.parkNanos(blocker, minOf(nanos, 1_000_000L))

        fun advance(nanos: Long) {
            now.addAndGet(nanos)
        }
    }

    /**
     * Like [steadyPool], in [mode], with timed wakes on [clock] [LEAD] before each tick. The lead is a minute so
     * that workers it wakes spin until the test starts the tick, however slow the machine.
     */
    private fun timedPool(threads: Int, clock: ManualClock, mode: IdleWorkers = IdleWorkers.BALANCED) =
        WorkerPool(threads, SpinPolicy(MINUTE, MINUTE, fixed = false, leadNanos = LEAD, forced = mode, hotNanos = MINUTE), clock = clock)

    /** One tick whose widest wave had [helpers] helpers, ended with [gap] until the next one. */
    private fun tick(pool: WorkerPool, helpers: Int, gap: Long) {
        pool.tickStarted()
        if (helpers > 0) gatedWave(pool, helpers + 1)
        pool.tickEnded(gap, sprinting = false)
    }

    @Test
    fun timedWakeBringsUpTheWorkersTheLastTickUsedBeforeTheTick() {
        val threads = 6
        val clock = ManualClock()
        timedPool(threads, clock).use { pool ->
            tick(pool, helpers = 2, gap = 2 * LEAD)
            awaitCondition("workers did not park when the tick ended") { (0 until threads).all(pool::isParked) }
            assertTrue(pool.timedWakePending)
            // Until the clock reaches the lead, nothing wakes.
            assertFalse(awaitConditionFor(100) { pool.timedWakeCount > 0 || (0 until threads).any { !pool.isParked(it) } })

            clock.advance(LEAD)
            awaitCondition("the timed wake did not bring up the last tick's workers") { (0..1).none(pool::isParked) }
            assertEquals(1, pool.timedWakeCount)
            assertFalse(pool.timedWakePending)
            assertTrue((2 until threads).all(pool::isParked), "a worker the last tick did not use was woken")

            // They spin into the tick: neither its start nor its first wave has anyone to wake.
            pool.tickStarted()
            assertFalse(pool.lastFanOut, "the tick start found a pre-woken worker parked")
            assertEquals(workerNames(0..1) + Thread.currentThread().name, gatedWave(pool, 3))
            assertFalse(pool.lastFanOut)
            assertEquals(0, pool.lastCallerUnparks)
        }
    }

    @Test
    fun timedWakeFansOutToEveryHelper() {
        val threads = 12
        val clock = ManualClock()
        timedPool(threads, clock).use { pool ->
            tick(pool, helpers = threads, gap = 2 * LEAD)
            awaitCondition("workers did not park") { (0 until threads).all(pool::isParked) }
            clock.advance(LEAD)
            awaitCondition("the fan-out did not reach every worker") { (0 until threads).none(pool::isParked) }
            pool.tickStarted()
            assertEquals(workerNames(0 until threads) + Thread.currentThread().name, gatedWave(pool, threads + 1))
            assertFalse(pool.lastFanOut)
        }
    }

    @Test
    fun nothingIsScheduledWithoutSlackOrWithoutParallelWork() {
        val clock = ManualClock()
        timedPool(4, clock).use { pool ->
            tick(pool, helpers = 0, gap = 2 * LEAD) // An idle or frozen tick ran nothing in parallel.
            assertFalse(pool.timedWakePending)
            tick(pool, helpers = 3, gap = LEAD - 1) // Behind schedule, or a rate too high for the lead.
            assertFalse(pool.timedWakePending)
            pool.tickStarted()
            gatedWave(pool, 4)
            pool.tickEnded(2 * LEAD, sprinting = true)
            assertFalse(pool.timedWakePending)
            assertFalse(pool.timerAlive, "a timer started with nothing to wake")
            clock.advance(10 * LEAD)
            assertFalse(awaitConditionFor(100) { pool.timedWakeCount > 0 })
        }
        // No tick events at all, as in tests and benchmarks.
        steadyPool(2).use { pool ->
            gatedWave(pool, 3)
            assertFalse(pool.timerAlive)
        }
    }

    @Test
    fun aPausedServerWakesNothingAfterTheLastTimedWake() {
        val threads = 4
        val clock = ManualClock()
        timedPool(threads, clock).use { pool ->
            val parks = AtomicIntegerArray(threads)
            pool.parkHook = WorkerPool.ParkHook { worker, at -> if (at == WorkerPool.ParkPoint.BEFORE_ANNOUNCE) parks.incrementAndGet(worker) }
            tick(pool, helpers = threads, gap = 2 * LEAD)
            awaitCondition("workers did not park") { (0 until threads).all(pool::isParked) }
            val before = IntArray(threads) { parks.get(it) }
            // The next tick never comes (pause-when-empty fires no tick events): the clock runs far past it.
            clock.advance(HOUR)
            // Past the due time, the woken workers spin only for the grace period, then park again.
            awaitCondition("pre-woken workers kept spinning with no tick") {
                (0 until threads).all { parks.get(it) > before[it] && pool.isParked(it) }
            }
            assertEquals(1, pool.timedWakeCount)
            val after = IntArray(threads) { parks.get(it) }
            clock.advance(HOUR)
            assertFalse(awaitConditionFor(200) {
                pool.timedWakeCount != 1 || (0 until threads).any { parks.get(it) != after[it] || !pool.isParked(it) }
            }, "something woke while no ticks ran")
        }
    }

    @Test
    fun aTickThatStartsBeforeTheTimedWakeGetsAllItsHelpersAndDropsIt() {
        val threads = 6
        val clock = ManualClock()
        timedPool(threads, clock).use { pool ->
            tick(pool, helpers = 2, gap = 2 * LEAD)
            awaitCondition("workers did not park") { (0 until threads).all(pool::isParked) }
            assertTrue(pool.timedWakePending)
            // The tick comes early (or the timer late): its start and its waves wake everyone they need.
            pool.tickStarted()
            assertFalse(pool.timedWakePending, "the tick start left a stale timed wake")
            assertEquals(workerNames(0 until threads) + Thread.currentThread().name, gatedWave(pool, threads + 1))
            clock.advance(10 * LEAD)
            assertFalse(awaitConditionFor(100) { pool.timedWakeCount > 0 }, "a dropped timed wake fired")
        }
    }

    @Test
    fun aTimedWakeNeverReplacesAWaveWithWorkLeft() {
        val clock = ManualClock()
        timedPool(1, clock).use { pool ->
            tick(pool, helpers = 1, gap = 2 * LEAD)
            // Another thread runs a wave whose third task nobody can claim while the first two block.
            val running = CountDownLatch(2)
            val release = CountDownLatch(1)
            val ran = AtomicInteger()
            val failure = AtomicReference<Throwable?>()
            val caller = Thread {
                try {
                    pool.runWave(List(3) {
                        Runnable {
                            running.countDown()
                            check(release.await(10, TimeUnit.SECONDS))
                            ran.incrementAndGet()
                        }
                    })
                } catch (t: Throwable) {
                    failure.set(t)
                }
            }.apply { start() }
            try {
                await(running)
                clock.advance(LEAD)
                awaitCondition("the timer did not look at the wave") { pool.timedWakeSkips == 1 }
                assertEquals(0, pool.timedWakeCount)
            } finally {
                release.countDown()
                caller.join(10_000)
            }
            assertFalse(caller.isAlive)
            assertNull(failure.get())
            assertEquals(3, ran.get())
        }
    }

    @Test
    fun closeStopsATimerWithAPendingWake() {
        val clock = ManualClock()
        val pool = timedPool(4, clock)
        tick(pool, helpers = 3, gap = 2 * LEAD)
        assertTrue(pool.timedWakePending)
        awaitCondition("the timer did not start") { pool.timerAlive }
        assertTimeoutPreemptively(Duration.ofSeconds(10)) { pool.close() }
        assertFalse(pool.timerAlive, "close left the timer running")
        assertFalse(pool.timedWakePending)
        clock.advance(10 * LEAD)
        assertEquals(0, pool.timedWakeCount)
        // Ticks after close schedule nothing and start no thread.
        pool.tickStarted()
        pool.tickEnded(2 * LEAD, sprinting = false)
        assertFalse(pool.timedWakePending)
    }

    @Test
    fun hotSpinsThroughTheGapAndLeanParksWithoutATimer() {
        val threads = 4
        val clock = ManualClock()
        timedPool(threads, clock, IdleWorkers.HOT).use { pool ->
            tick(pool, helpers = threads, gap = HOUR)
            assertFalse(pool.timedWakePending)
            assertFalse(awaitConditionFor(200) { (0 until threads).any(pool::isParked) }, "a hot worker parked between ticks")
            pool.tickStarted()
            assertEquals(workerNames(0 until threads) + Thread.currentThread().name, gatedWave(pool, threads + 1))
            assertFalse(pool.lastFanOut)
            // A tick without parallel work (a frozen server) keeps nobody spinning.
            pool.tickEnded(HOUR, sprinting = false)
            tick(pool, helpers = 0, gap = HOUR)
            awaitCondition("hot workers spun through a gap after a tick with no parallel work") { (0 until threads).all(pool::isParked) }
        }
        timedPool(threads, clock, IdleWorkers.LEAN).use { pool ->
            tick(pool, helpers = threads, gap = HOUR)
            awaitCondition("lean workers did not park when the tick ended") { (0 until threads).all(pool::isParked) }
            assertFalse(pool.timedWakePending)
            assertFalse(pool.timerAlive)
            // The tick start still wakes them, as before timed wakes existed.
            pool.tickStarted()
            awaitCondition("the tick start did not wake the last tick's workers") { (0 until threads).none(pool::isParked) }
        }
    }

    @Test
    fun configurationSwitchesModesFromTheNextTickEvent() {
        val threads = 3
        val clock = ManualClock()
        WorkerPool(threads, SpinPolicy(MINUTE, MINUTE, fixed = false, leadNanos = LEAD, hotNanos = MINUTE), clock = clock).use { pool ->
            assertEquals(IdleWorkers.BALANCED, pool.idleWorkers)
            pool.configureIdle(IdleWorkers.LEAN)
            tick(pool, helpers = threads, gap = HOUR)
            assertFalse(pool.timedWakePending)
            pool.configureIdle(IdleWorkers.BALANCED)
            tick(pool, helpers = threads, gap = HOUR)
            assertTrue(pool.timedWakePending)
            pool.configureIdle(IdleWorkers.HOT)
            assertEquals(IdleWorkers.HOT, pool.idleWorkers)
            tick(pool, helpers = threads, gap = HOUR)
            assertFalse(pool.timedWakePending, "the hot tick start dropped the old wake and scheduled none")
        }
    }

    @Test
    fun cappedWaveRunsInlineOrOnTheLowestWorkers() {
        steadyPool(4).use { pool ->
            val ran = ConcurrentHashMap.newKeySet<Thread>()
            pool.runWave(List(50) { Runnable { ran.add(Thread.currentThread()) } }, null, 1)
            assertEquals(setOf(Thread.currentThread()), ran, "a one-participant wave left the caller")
            assertFalse(WorkerPool.isParallelWaveCaller())
            val started = CountDownLatch(2)
            val names = ConcurrentHashMap.newKeySet<String>()
            pool.runWave(List(8) {
                Runnable {
                    names.add(Thread.currentThread().name)
                    started.countDown()
                    check(started.await(5, TimeUnit.SECONDS))
                }
            }, null, 2)
            assertEquals(setOf(Thread.currentThread().name, "Vanadium-Worker-1"), names)
        }
    }

    @Test
    fun runWaveReportsBusyTime() {
        WorkerPool(2).use { pool ->
            val busy = pool.runWave(List(3) { Runnable { Thread.sleep(5) } })
            assertTrue(busy >= 15_000_000L, "busy time $busy below the tasks' sleep")
        }
    }

    private fun awaitCondition(message: String, condition: () -> Boolean) {
        assertTrue(awaitConditionFor(5000, condition), message)
    }

    /** Polls [condition] for up to [millis]; true as soon as it holds. */
    private fun awaitConditionFor(millis: Long, condition: () -> Boolean): Boolean {
        val deadline = System.nanoTime() + millis * 1_000_000L
        while (System.nanoTime() < deadline) {
            if (condition()) return true
            Thread.sleep(1)
        }
        return condition()
    }

    private fun await(latch: CountDownLatch) {
        assertTrue(latch.await(5, TimeUnit.SECONDS), "test gate timed out")
    }

    private companion object {
        const val SLACK = 3_600_000_000_000L
        const val HOUR = 3_600_000_000_000L
        const val MINUTE = 60_000_000_000L
        const val LEAD = 60_000_000_000L
    }
}
