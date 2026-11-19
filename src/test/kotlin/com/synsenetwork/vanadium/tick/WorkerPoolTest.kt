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

    /** A one-task wave runs inline: its task has finished, on the caller, before runWave returns. */
    @Test
    fun oneTaskWaveFinishesBeforeRunWaveReturns() {
        val pool = WorkerPool(2)
        val order = ArrayList<String>() // Unsynchronized on purpose: only the caller touches it.
        pool.runWave(listOf(Runnable { order.add("task on ${Thread.currentThread().name}") }))
        order.add("returned")
        assertEquals(listOf("task on ${Thread.currentThread().name}", "returned"), order)
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
    private fun steadyPool(threads: Int) = WorkerPool(threads, SpinPolicy(MINUTE, MINUTE))

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

    /**
     * The caller unparked at least the first parked helper and at most [limit]. It shares the wake cursor with
     * the helpers it wakes, so a helper that starts before the caller's next unpark can take the rest: the exact
     * count depends on timing (seen with fewer processors than threads).
     */
    private fun assertCallerStartedFanOut(pool: WorkerPool, limit: Int) {
        val unparks = pool.lastCallerUnparks
        assertTrue(unparks in 1..limit, "the caller unparked $unparks workers itself, expected 1..$limit")
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
            assertCallerStartedFanOut(pool, WorkerPool.CALLER_WAKES)
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
                assertCallerStartedFanOut(pool, WorkerPool.CALLER_WAKES)
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
            if (point == WorkerPool.ParkPoint.BEFORE_ANNOUNCE && threads == 1) assertEquals(0, pool.lastCallerUnparks)
            else assertCallerStartedFanOut(pool, minOf(threads, WorkerPool.CALLER_WAKES))
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

    /** Like [steadyPool], in [mode]; hot workers spin a minute between waves of a tick. */
    private fun modePool(threads: Int, mode: IdleWorkers) =
        WorkerPool(threads, SpinPolicy(MINUTE, MINUTE, hotNanos = MINUTE)).also { it.configureIdle(mode) }

    /** One tick whose widest wave had [helpers] helpers, ended with [gap] until the next one. */
    private fun tick(pool: WorkerPool, helpers: Int, gap: Long) {
        pool.tickStarted()
        if (helpers > 0) gatedWave(pool, helpers + 1)
        pool.tickEnded(gap, sprinting = false)
    }

    @Test
    fun aPausedServerWakesNothingBetweenTicks() {
        val threads = 4
        for (mode in IdleWorkers.entries) {
            // A 20 ms gap is slack against a 2 ms spin. Hot workers spin through it, then park: the next tick never
            // comes (pause-when-empty fires no tick events), and only its start would wake anyone.
            WorkerPool(threads, SpinPolicy(2_000_000L, MINUTE, hotNanos = MINUTE)).also { it.configureIdle(mode) }.use { pool ->
                val parks = AtomicIntegerArray(threads)
                pool.parkHook = WorkerPool.ParkHook { worker, at -> if (at == WorkerPool.ParkPoint.BEFORE_ANNOUNCE) parks.incrementAndGet(worker) }
                tick(pool, helpers = threads, gap = 20_000_000L)
                awaitCondition("$mode workers kept spinning with no tick") { (0 until threads).all(pool::isParked) }
                val after = IntArray(threads) { parks.get(it) }
                assertFalse(awaitConditionFor(200) {
                    (0 until threads).any { parks.get(it) != after[it] || !pool.isParked(it) }
                }, "something woke while no ticks ran in $mode")
            }
        }
    }

    @Test
    fun hotSpinsThroughTheGapAndLeanParks() {
        val threads = 4
        modePool(threads, IdleWorkers.HOT).use { pool ->
            tick(pool, helpers = threads, gap = HOUR)
            assertFalse(awaitConditionFor(200) { (0 until threads).any(pool::isParked) }, "a hot worker parked between ticks")
            pool.tickStarted()
            assertEquals(workerNames(0 until threads) + Thread.currentThread().name, gatedWave(pool, threads + 1))
            assertFalse(pool.lastFanOut)
            // A tick without parallel work (a frozen server) keeps nobody spinning.
            pool.tickEnded(HOUR, sprinting = false)
            tick(pool, helpers = 0, gap = HOUR)
            awaitCondition("hot workers spun through a gap after a tick with no parallel work") { (0 until threads).all(pool::isParked) }
        }
        modePool(threads, IdleWorkers.LEAN).use { pool ->
            tick(pool, helpers = threads, gap = HOUR)
            awaitCondition("lean workers did not park when the tick ended") { (0 until threads).all(pool::isParked) }
            // The tick start wakes them.
            pool.tickStarted()
            awaitCondition("the tick start did not wake the last tick's workers") { (0 until threads).none(pool::isParked) }
        }
    }

    @Test
    fun configurationSwitchesModesFromTheNextTickEvent() {
        val threads = 3
        WorkerPool(threads, SpinPolicy(MINUTE, MINUTE, hotNanos = MINUTE)).use { pool ->
            assertEquals(IdleWorkers.BALANCED, pool.idleWorkers)
            for (mode in listOf(IdleWorkers.LEAN, IdleWorkers.HOT, IdleWorkers.BALANCED, IdleWorkers.HOT, IdleWorkers.LEAN)) {
                pool.configureIdle(mode)
                assertEquals(mode, pool.idleWorkers)
                tick(pool, helpers = threads, gap = HOUR)
                if (mode == IdleWorkers.HOT) {
                    assertFalse(awaitConditionFor(200) { (0 until threads).any(pool::isParked) }, "a hot worker parked between ticks")
                    assertEquals(Long.MAX_VALUE, pool.serverSpinNanos)
                } else {
                    awaitCondition("$mode workers did not park when the tick ended") { (0 until threads).all(pool::isParked) }
                    assertEquals(if (mode == IdleWorkers.BALANCED) Long.MAX_VALUE else 0L, pool.serverSpinNanos)
                }
            }
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
    }

    /** One participant owns every home: phase by phase, homes in order, each cell from its front. */
    @Test
    fun aLoneCallerRunsAHomedWaveInLayoutOrder() {
        WorkerPool(2).use { pool ->
            val ran = ArrayList<Int>()
            val tasks = List(9) { i -> Runnable { ran.add(i) } }
            // Two phases over two homes: cells [0,2) [2,5) | [5,6) [6,9).
            val homes = WaveHomes(2).apply {
                reset(2)
                set(0, 0, 0); set(0, 1, 2); set(1, 0, 5); set(1, 1, 6); set(2, 0, 9)
            }
            pool.runWave(tasks, null, 1, homes)
            assertEquals((0 until 9).toList(), ran)
        }
    }

    /**
     * Each slot runs its own home: the caller (slot 0) home 0, worker i home i + 1. Every task waits at a barrier of
     * all participants, and a participant takes its own home's next task before anything else, so no task can be
     * stolen before every participant holds one of its own.
     */
    @Test
    fun eachParticipantRunsItsOwnHomeFirst() {
        repeat(20) {
            WorkerPool(2).use { pool ->
                val barrier = CyclicBarrier(3)
                val ranOn = arrayOfNulls<String>(6)
                val tasks = List(6) { i ->
                    Runnable {
                        ranOn[i] = Thread.currentThread().name
                        barrier.await(10, TimeUnit.SECONDS)
                    }
                }
                val homes = WaveHomes(3).apply {
                    reset(1)
                    set(0, 0, 0); set(0, 1, 2); set(0, 2, 4); set(1, 0, 6)
                }
                val caller = Thread.currentThread().name
                pool.runWave(tasks, null, 3, homes)
                assertEquals(listOf(caller, caller, "Vanadium-Worker-1", "Vanadium-Worker-1", "Vanadium-Worker-2", "Vanadium-Worker-2"),
                    ranOn.toList())
            }
        }
    }

    /** Homed waves of every shape run each task exactly once, with stealing, fewer participants than homes and reuse of the layout. */
    @Test
    fun homedWavesRunEveryTaskOnce() {
        WorkerPool(5).use { pool ->
            val random = java.util.Random(42)
            val homes = WaveHomes(6)
            repeat(300) { wave ->
                val phases = 1 + random.nextInt(4)
                val count = random.nextInt(200)
                val visits = AtomicIntegerArray(count)
                val tasks = List(count) { i ->
                    Runnable {
                        visits.incrementAndGet(i)
                        if (i % 7 == 0) LockSupport.parkNanos(20_000)
                    }
                }
                // Random cell boundaries, some cells empty.
                val cuts = (List(phases * 6 - 1) { random.nextInt(count + 1) } + 0).sorted()
                homes.reset(phases)
                for (cell in cuts.indices) homes.set(cell / 6, cell % 6, cuts[cell])
                homes.set(phases, 0, count)
                pool.runWave(tasks, null, 1 + wave % 7, homes)
                for (i in 0 until count) assertEquals(1, visits.get(i), "wave $wave task $i")
            }
        }
    }

    /** A homed wave runs every task even when some throw, then reports the first failure, as a list-order wave does. */
    @Test
    fun homedWavesRunEveryTaskThenFail() {
        WorkerPool(3).use { pool ->
            val visits = AtomicIntegerArray(40)
            val tasks = List(40) { i ->
                Runnable {
                    visits.incrementAndGet(i)
                    if (i % 9 == 4) throw IllegalStateException("task $i")
                }
            }
            val homes = WaveHomes(4).apply {
                reset(2)
                for (cell in 0 until 8) set(cell / 4, cell % 4, cell * 5)
                set(2, 0, 40)
            }
            val failure = assertFailsWith<RuntimeException> { pool.runWave(tasks, null, 4, homes) }
            assertIs<IllegalStateException>(failure.cause)
            for (i in 0 until 40) assertEquals(1, visits.get(i), "task $i")
        }
    }

    /**
     * The caller serves a helper's [WorkerPool.callOnWaveCaller] request between the claims of its own home: its first
     * task returns once the request is queued, and its second finds it served already.
     */
    @Test
    fun theCallerServesRequestsBetweenClaimsInAHomedWave() {
        WorkerPool(1).use { pool ->
            val caller = AtomicReference<Thread?>()
            val ranOn = AtomicReference<Thread?>()
            val servedBeforeSecond = AtomicBoolean(false)
            val served = AtomicBoolean(false)
            val tasks = listOf(
                Runnable {
                    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
                    while (!WorkerPool.callerHasRequests() && System.nanoTime() < deadline) Thread.onSpinWait()
                },
                Runnable { servedBeforeSecond.set(served.get()) },
                Runnable {
                    ranOn.set(WorkerPool.callOnWaveCaller(java.util.function.Supplier {
                        served.set(true)
                        Thread.currentThread()
                    }))
                },
            )
            // The caller (slot 0) owns home 0, tasks 0 and 1; the worker (slot 1) owns home 1, task 2. The caller is
            // inside task 0 until the worker has claimed task 2 and queued its request.
            val homes = WaveHomes(2).apply {
                reset(1)
                set(0, 0, 0); set(0, 1, 2); set(1, 0, 3)
            }
            assertTimeoutPreemptively(Duration.ofSeconds(20)) {
                caller.set(Thread.currentThread())
                pool.runWave(tasks, null, 2, homes)
            }
            assertSame(caller.get(), ranOn.get())
            assertTrue(servedBeforeSecond.get(), "the request was not served between the caller's claims")
        }
    }
}
