package com.synsenetwork.vanadium.tick

import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicIntegerArray
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.LockSupport
import java.util.function.LongConsumer

/**
 * Persistent workers with caller participation and a full barrier per wave. Dispatch uses a volatile
 * work batch and Dekker wake-ups; small waves use low-numbered workers. Claims and completions are
 * batched to reduce contention. Tick-path locks must work on both the caller and workers.
 *
 * Wake-up fans out: the caller unparks at most [CALLER_WAKES] parked helpers, and every helper that joins a
 * waking batch unparks further ones from the batch's shared wake cursor before it claims work, so the server
 * thread never issues a wake-up per worker. Idle workers spin for [SpinPolicy]'s budget, which follows the
 * server's tick events, then park.
 */
class WorkerPool internal constructor(
    threads: Int,
    private val spin: SpinPolicy,
    private val callerWakes: Int = CALLER_WAKES,
) : AutoCloseable {
    constructor(threads: Int) : this(threads, SpinPolicy.fromSystemProperties())

    private val workers = Array(maxOf(1, threads)) { WorkerThread(this, it) }

    @Volatile
    private var current: WorkBatch? = null

    @Volatile
    private var closed = false

    /** Workers between announcing a park and leaving it; read after each publish (Dekker with [WorkerThread.run]). */
    private val parkedWorkers = AtomicInteger()

    /** Most helpers any wave used since [tickStarted]; caller-thread only. */
    private var tickPeakHelpers = 0

    /** Unparks the caller itself issued for its last published batch; caller-thread only, for tests. */
    internal var lastCallerUnparks = 0
        private set

    /** Test hook on the park path only; null in production. */
    @Volatile
    internal var parkHook: ParkHook? = null

    init {
        workers.forEach(Thread::start)
    }

    val workerCount: Int get() = workers.size

    /**
     * Runs every task and blocks until all have finished (a full barrier). A single-task wave runs
     * inline. Interruption does not cancel a wave: the caller waits for all tasks, then restores its
     * interrupt flag. Do not change [tasks] until this returns; only one thread may run waves at a time.
     *
     * @param callerWait optional profiling callback for the caller's tail-wait time
     * @param maxParticipants threads that may run tasks, the caller included; 1 runs the wave inline
     * @return time participants spent claiming and running tasks, summed over participants
     */
    fun runWave(tasks: Collection<Runnable>, callerWait: LongConsumer? = null, maxParticipants: Int = Int.MAX_VALUE): Long {
        val n = tasks.size
        if (n == 0) return 0
        if (n == 1) {
            val start = System.nanoTime()
            tasks.first().run()
            return System.nanoTime() - start
        }
        // Claims index the list directly; anything without cheap random access is copied first.
        val indexed = if (tasks is List<Runnable> && tasks is RandomAccess) tasks else tasks.toList()
        val rejected = closed
        // At most n participants with the caller, and no more than asked for.
        val helpers = if (rejected) 0 else minOf(workers.size, n - 1, maxOf(0, maxParticipants - 1))
        val batch = WorkBatch(indexed, helpers + 1, Thread.currentThread())
        val caller = if (helpers > 0) CALLER.get() else null
        if (caller != null) {
            caller.parallelWaves++
            if (helpers > tickPeakHelpers) tickPeakHelpers = helpers
            publish(batch)
        }
        var waveFailure: Throwable? = null
        try {
            batch.participate()
            val waitStart = if (callerWait != null) System.nanoTime() else 0
            try {
                batch.awaitCompletion()
            } finally {
                callerWait?.accept(System.nanoTime() - waitStart)
            }
        } catch (t: Throwable) {
            waveFailure = t
            throw t
        } finally {
            if (caller != null && --caller.parallelWaves == 0) {
                try {
                    caller.runAfterWave()
                } catch (t: Throwable) {
                    // The wave's own failure stays primary.
                    waveFailure?.addSuppressed(t) ?: throw t
                }
            }
        }
        if (rejected) throw RejectedExecutionException("Vanadium worker pool is closed")
        return batch.busyNanos
    }

    /**
     * Publishes [batch], then starts its wake-up if any worker may be parked. A worker counts itself in
     * [parkedWorkers] before its last look at [current], so reading zero here means every worker will see
     * the batch without being woken.
     */
    private fun publish(batch: WorkBatch) {
        // Unlimited caller wakes is the serial loop this replaced, kept for comparison in benchmarks: no fan-out.
        val fanOut = callerWakes != Int.MAX_VALUE
        // A hint read before publishing lets spinning helpers, which see the batch first, help wake from the start.
        if (fanOut && parkedWorkers.get() > 0) batch.waking = true
        current = batch
        if (parkedWorkers.get() == 0) {
            lastCallerUnparks = 0
            return
        }
        if (fanOut) batch.waking = true
        lastCallerUnparks = batch.wake(workers, callerWakes)
    }

    /**
     * Server thread, at the start of a tick: workers may spin between waves again, and the workers the last
     * tick used are woken now (fanning out as for a wave), so they spin by the time the first wave arrives
     * instead of being woken by it.
     */
    fun tickStarted() {
        spin.tickStarted()
        val peak = tickPeakHelpers
        tickPeakHelpers = 0
        if (!PREWAKE || peak == 0 || closed || CALLER.get().parallelWaves > 0) return
        publish(WorkBatch(emptyList(), minOf(peak, workers.size) + 1, null))
    }

    /**
     * Server thread, at the end of a tick: [nanosUntilNextTick] is how long the server will sleep before the
     * next one (zero or less when it is behind). With slack, idle workers park at once.
     */
    fun tickEnded(nanosUntilNextTick: Long, sprinting: Boolean) = spin.tickEnded(nanosUntilNextTick, sprinting)

    /** For tests: whether [worker] has announced a park and not yet left it. */
    internal fun isParked(worker: Int): Boolean = workers[worker].parked

    /** Called after the last wave; finishes late helpers and releases the pool's threads. */
    override fun close() {
        closed = true
        workers.forEach(LockSupport::unpark)
        var interrupted = false
        for (worker in workers) {
            if (worker === Thread.currentThread()) continue
            while (worker.isAlive) {
                try {
                    worker.join()
                } catch (_: InterruptedException) {
                    interrupted = true
                }
            }
        }
        if (interrupted) Thread.currentThread().interrupt()
    }

    /** A wave's tasks, or with no tasks a wake-up only ([tickStarted]); [waiter] is null for the latter. */
    private class WorkBatch(private val tasks: List<Runnable>, participants: Int, private val waiter: Thread?) {
        private val taskCount = tasks.size
        val helpers = participants - 1

        /** Chunks only when tasks greatly outnumber participants; cells stay individually balanced. */
        private val chunk = maxOf(1, taskCount / (participants * 8))

        /** Claim cursor, completion counter and wake cursor, 64 bytes apart so they never share a cache line. */
        private val counters = AtomicIntegerArray(WAKE + 1).apply { set(REMAINING, taskCount) }
        private val error = AtomicReference<Throwable?>()
        private val busy = AtomicLong()

        @Volatile
        private var waiterParked = false

        /** Set by the caller, after publishing, when some worker may be parked: joining helpers then wake others. */
        @Volatile
        var waking = false

        val busyNanos: Long get() = busy.get()

        /**
         * Takes helper indices from the wake cursor and unparks those workers that are parked, until [limit]
         * were unparked, every helper has been taken, or no task is left to claim (a worker woken then would
         * find nothing). Each index is taken once, so every helper is checked by exactly one thread, and that
         * thread has seen this batch published: the Dekker pairing with [WorkerThread.run] holds whoever wakes.
         * Reads only counters, never the task list. Returns the number unparked.
         */
        fun wake(workers: Array<WorkerThread>, limit: Int): Int {
            var unparked = 0
            while (unparked < limit && (taskCount == 0 || counters.get(NEXT) < taskCount)) {
                val index = counters.getAndIncrement(WAKE)
                if (index >= helpers) break
                if (workers[index].wake()) unparked++
            }
            return unparked
        }

        fun participate() {
            if (taskCount == 0) return
            var done = 0
            var begin = 0L
            // A late helper uses the saved count and never reads a list already reused by the caller.
            while (true) {
                val start = counters.getAndAdd(NEXT, chunk)
                if (start >= taskCount) break
                if (done == 0) begin = System.nanoTime()
                val end = minOf(taskCount, start + chunk)
                for (i in start until end) {
                    try {
                        tasks[i].run()
                    } catch (t: Throwable) {
                        error.compareAndSet(null, t)
                    }
                }
                done += end - start
            }
            if (done == 0) return
            // Busy time is recorded before completion so the caller reads it complete after the barrier.
            busy.addAndGet(System.nanoTime() - begin)
            if (counters.addAndGet(REMAINING, -done) == 0 && waiterParked) LockSupport.unpark(waiter)
        }

        fun awaitCompletion() {
            var interrupted = false
            val deadline = System.nanoTime() + CALLER_SPIN_NANOS
            while (counters.get(REMAINING) != 0) {
                if (System.nanoTime() < deadline) {
                    Thread.onSpinWait()
                    continue
                }
                waiterParked = true
                if (counters.get(REMAINING) != 0) LockSupport.park(this)
                waiterParked = false
                if (Thread.interrupted()) interrupted = true
            }
            if (interrupted) Thread.currentThread().interrupt()
            error.get()?.let { throw RuntimeException("Vanadium worker task failed", it) }
        }

        private companion object {
            const val NEXT = 0
            const val REMAINING = 16
            const val WAKE = 32
        }
    }

    private class WorkerThread(private val pool: WorkerPool, private val index: Int) : TickThread("Vanadium-Worker-${index + 1}") {
        @Volatile
        var parked = false
            private set

        /** The deferral state of the task running on this thread; touched only by this thread. */
        val deferral = Deferral()

        init {
            isDaemon = true
        }

        /** Dekker pairing with [run]: the batch is published before [parked] is read. Returns whether it unparked. */
        fun wake(): Boolean {
            if (!parked) return false
            LockSupport.unpark(this)
            return true
        }

        /**
         * Parking: [parked] and the pool's count are written before [WorkerPool.current] is read again, and a
         * waker reads them after publishing. Either the waker sees the park announced and unparks (the permit
         * survives until the park), or this thread's recheck sees the new batch. A worker that has parked
         * parks again on a stray return instead of spinning: it only leaves for work or for close.
         */
        override fun run() {
            var seen: WorkBatch? = null
            var idleSince = System.nanoTime()
            var rested = false
            while (true) {
                val batch = pool.current
                if (batch !== seen) {
                    seen = batch
                    if (batch != null && index < batch.helpers) {
                        // Waking first: workers woken now start while this one claims.
                        if (batch.waking) batch.wake(pool.workers, Int.MAX_VALUE)
                        batch.participate()
                        idleSince = System.nanoTime()
                        rested = false
                    }
                    continue
                }
                if (pool.closed) return
                if (!rested && System.nanoTime() - idleSince < pool.spin.workerSpinNanos) {
                    onSpinWait()
                    continue
                }
                val hook = pool.parkHook
                hook?.at(index, ParkPoint.BEFORE_ANNOUNCE)
                parked = true
                pool.parkedWorkers.incrementAndGet()
                hook?.at(index, ParkPoint.BEFORE_RECHECK)
                if (pool.current === seen && !pool.closed) {
                    hook?.at(index, ParkPoint.BEFORE_PARK)
                    LockSupport.park(pool)
                    rested = true
                }
                pool.parkedWorkers.decrementAndGet()
                parked = false
                interrupted() // Pool threads are never interrupted for control; ignore strays.
            }
        }
    }

    /** Points on a worker's way to parking, for tests that publish a wave inside the race window. */
    internal enum class ParkPoint { BEFORE_ANNOUNCE, BEFORE_RECHECK, BEFORE_PARK }

    internal fun interface ParkHook {
        fun at(worker: Int, point: ParkPoint)
    }

    companion object {
        /** Caller spin while waiting for the wave tail before parking. */
        private val CALLER_SPIN_NANOS = java.lang.Long.getLong("vanadium.callerSpinMicros", 200) * 1_000L

        /**
         * Parked helpers the caller unparks itself; each of them unparks more. Two start the fan-out on two
         * threads while costing the server thread only two wake-ups.
         */
        internal val CALLER_WAKES = Integer.getInteger("vanadium.callerWakes", 2).coerceAtLeast(1)

        /** Whether [tickStarted] wakes the workers the previous tick used. */
        private val PREWAKE = System.getProperty("vanadium.prewake", "true").toBoolean()

        /** Wave callers' state; read only on the calling thread, outside per-task hot paths. */
        private val CALLER = ThreadLocal.withInitial(::CallerState)

        /** True when called from a thread owned by a WorkerPool. */
        @JvmStatic
        fun isWorkerThread(): Boolean = Thread.currentThread() is WorkerThread

        /**
         * True on the thread that is running a wave with helpers (the server thread) until every task has
         * finished. Work it does then overlaps the workers, so it must not run chunk-system work either.
         */
        @JvmStatic
        fun isParallelWaveCaller(): Boolean = Thread.currentThread() !is WorkerThread && CALLER.get().parallelWaves > 0

        /**
         * Runs [task] once the current parallel wave has finished, on the thread that ran it, when called by that
         * wave's caller; otherwise runs it now. Queued tasks run in order, before [runWave] returns.
         */
        @JvmStatic
        fun runAfterParallelWave(task: Runnable) {
            if (Thread.currentThread() !is WorkerThread) {
                val caller = CALLER.get()
                if (caller.parallelWaves > 0) {
                    caller.afterWave.add(task)
                    return
                }
            }
            task.run()
        }

        /** The deferral state for the current thread, or null where work may simply block. */
        private fun currentDeferral(): Deferral? {
            val thread = Thread.currentThread()
            if (thread is WorkerThread) return thread.deferral
            return CALLER.get().takeIf { it.parallelWaves > 0 }
        }

        /**
         * Runs [task], letting work that needs the server thread to itself unwind it with [ServerThreadRequired].
         * A worker waiting for the server thread would never be released (it participates in the same wave), and
         * chunk-system work the server thread did mid-wave would overlap the workers. Returns false when the task
         * was abandoned and must be rerun on the server thread after the wave; anything it did before that point
         * stays done. Outside parallel waves the task just runs: the server thread may block.
         */
        @JvmStatic
        fun runDeferrable(task: Runnable): Boolean {
            val state = currentDeferral()
            if (state == null) {
                task.run()
                return true
            }
            val outerDeferrable = state.deferrable
            val outerDeferred = state.deferred
            state.deferrable = true
            state.deferred = false
            try {
                task.run()
                return !state.deferred
            } catch (t: Throwable) {
                // A failure after a swallowed signal is a consequence of it; the server-thread rerun shows real bugs.
                if (t !is ServerThreadRequired && !state.deferred) throw t
                return false
            } finally {
                // The rerun belongs to this task's caller; an enclosing deferrable task keeps only its own state.
                state.deferrable = outerDeferrable
                state.deferred = outerDeferred
            }
        }

        /**
         * Inside [runDeferrable] during a parallel wave, marks the task for the server thread and returns the signal
         * to throw; otherwise null, and the caller must handle [what] itself. Marking survives a caller swallowing
         * the signal.
         */
        @JvmStatic
        fun deferToServerThread(what: String): ServerThreadRequired? {
            val state = currentDeferral() ?: return null
            if (!state.deferrable) return null
            state.deferred = true
            return ServerThreadRequired(what)
        }
    }

    /** Whether the running task may be deferred, and whether it asked to be. */
    private open class Deferral {
        var deferrable = false
        var deferred = false
    }

    private class CallerState : Deferral() {
        /** Nesting depth of waves with helpers that this thread is running. */
        var parallelWaves = 0
        val afterWave = ArrayList<Runnable>()

        /** Runs every queued task, including ones queued by them, then rethrows the first failure. */
        fun runAfterWave() {
            var failure: Throwable? = null
            var i = 0
            while (i < afterWave.size) {
                try {
                    afterWave[i].run()
                } catch (t: Throwable) {
                    if (failure == null) failure = t else failure.addSuppressed(t)
                }
                i++
            }
            afterWave.clear()
            failure?.let { throw it }
        }
    }
}

/**
 * How long idle workers spin before parking, from the server's tick events. Spinning costs a core per
 * worker; parking costs a wake-up (hundreds of microseconds on a VM) when the next wave comes.
 *
 * - Under pressure (sprinting, or the last tick ended less than [longNanos] before the next was due), workers
 *   spin [longNanos] through every gap, including the one between ticks: throughput is what matters, and the
 *   next wave is close.
 * - With slack, workers spin [shortNanos] between waves of a tick, and park as soon as the tick ends: the
 *   server sleeps until the next one, and [WorkerPool.tickStarted] wakes them again.
 *
 * Without tick events (tests, benchmarks, before the first tick) workers spin [longNanos]. A fixed policy
 * ignores tick events. Events come from the server thread; workers only read [workerSpinNanos].
 */
internal class SpinPolicy(private val longNanos: Long, private val shortNanos: Long, private val fixed: Boolean) {
    @Volatile
    var workerSpinNanos: Long = longNanos
        private set

    /** Whether the last tick ended with the next one due within [longNanos], or the server was sprinting. */
    var underPressure = true
        private set

    var inTick = true
        private set

    fun tickStarted() {
        inTick = true
        update()
    }

    fun tickEnded(nanosUntilNextTick: Long, sprinting: Boolean) {
        inTick = false
        underPressure = sprinting || nanosUntilNextTick < longNanos
        update()
    }

    private fun update() {
        workerSpinNanos = when {
            fixed || underPressure -> longNanos
            inTick -> shortNanos
            else -> 0L
        }
    }

    companion object {
        /** Idle spin under pressure: two milliseconds covers inter-wave gaps on the Xeon VM. */
        private const val LONG_SPIN_MICROS = 2_000L

        /** Idle spin between waves of a tick with slack: covers the usual serial gaps, not long serial phases. */
        private const val SHORT_SPIN_MICROS = 500L

        /** `vanadium.workerSpinMicros` fixes the spin and ignores ticks; `vanadium.workerSlackSpinMicros` sets the short one. */
        fun fromSystemProperties(): SpinPolicy {
            val fixedMicros = java.lang.Long.getLong("vanadium.workerSpinMicros")
            if (fixedMicros != null) return SpinPolicy(fixedMicros * 1_000L, fixedMicros * 1_000L, fixed = true)
            val shortMicros = java.lang.Long.getLong("vanadium.workerSlackSpinMicros", SHORT_SPIN_MICROS)
            return SpinPolicy(LONG_SPIN_MICROS * 1_000L, shortMicros * 1_000L, fixed = false)
        }
    }
}

/** A pool worker. [context] gives tick code per-thread state without a thread-local lookup. */
abstract class TickThread(name: String) : Thread(name) {
    @JvmField
    var context: Any? = null
}

/** Unwinds a worker task that needs the server thread; see [WorkerPool.runDeferrable]. No stack trace is captured. */
class ServerThreadRequired(what: String) : RuntimeException("$what requires the server thread", null, false, false)
