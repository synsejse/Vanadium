package com.synsenetwork.vanadium.tick

import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicIntegerArray
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicLongArray
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.LockSupport
import java.util.function.LongBinaryOperator
import java.util.function.LongConsumer

/**
 * Persistent workers with caller participation and a full barrier per wave. Dispatch uses a volatile
 * work batch and Dekker wake-ups; small waves use low-numbered workers. Claims and completions are
 * batched to reduce contention. Tick-path locks must work on both the caller and workers.
 *
 * Wake-up fans out: the caller unparks at most [CALLER_WAKES] parked helpers, and every helper that joins a
 * waking batch unparks further ones from the batch's shared wake cursor before it claims work, so the server
 * thread never issues a wake-up per worker. Idle workers spin for [SpinPolicy]'s budget, which follows the
 * server's tick events, then park. Between ticks, [IdleWorkers] decides whether they park and are woken shortly
 * before the next tick by a timer thread, or keep spinning.
 */
class WorkerPool internal constructor(
    threads: Int,
    private val spin: SpinPolicy,
    private val callerWakes: Int = CALLER_WAKES,
    private val clock: PrewakeClock = PrewakeClock.SYSTEM,
) : AutoCloseable {
    constructor(threads: Int) : this(threads, SpinPolicy.fromSystemProperties())

    private val workers = Array(maxOf(1, threads)) { WorkerThread(this, it) }

    /**
     * The latest batch. The caller publishes waves with a plain write; the timed wake only replaces a batch that
     * has nothing left to claim, with a compare-and-set, so it never hides a wave from a worker that could help.
     */
    private val current = AtomicReference<WorkBatch?>()

    @Volatile
    private var closed = false

    /** The timed wake [tickEnded] scheduled and neither the timer nor [tickStarted] has taken yet. */
    private val pendingWake = AtomicReference<TimedWake?>()

    /** Started by the first [tickEnded] that schedules a timed wake; tests and benches without ticks have none. */
    @Volatile
    private var timer: PrewakeTimer? = null

    /** Timed wakes that published a batch, and ones that found a wave with work left; for tests. */
    private val timedWakes = AtomicInteger()
    private val timedWakesSkipped = AtomicInteger()

    /**
     * One bit per worker between announcing a park and leaving it; the caller reads the bits of a wave's
     * helpers after publishing it (Dekker with [WorkerThread.run]). Workers outside a small wave may stay
     * parked without making its caller start a fan-out.
     */
    private val parkedBits = AtomicLongArray((workers.size + 63) ushr 6)

    /** Most helpers any wave used since [tickStarted]; caller-thread only. */
    private var tickPeakHelpers = 0

    /** Unparks the caller itself issued for its last published batch; caller-thread only, for tests. */
    internal var lastCallerUnparks = 0
        private set

    /** Whether the last published batch started a fan-out; caller-thread only, for tests. */
    internal var lastFanOut = false
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
     * Publishes [batch], then starts its wake-up if one of its helpers may be parked. A worker sets its bit in
     * [parkedBits] before its last look at [current], so finding no helper's bit set here means every helper
     * will see the batch without being woken.
     */
    private fun publish(batch: WorkBatch) {
        // Unlimited caller wakes is the serial loop this replaced, kept for comparison in benchmarks: no fan-out.
        val fanOut = callerWakes != Int.MAX_VALUE
        // A hint read before publishing lets spinning helpers, which see the batch first, help wake from the start.
        if (fanOut && anyParked(batch.helpers)) batch.waking = true
        current.set(batch)
        lastFanOut = anyParked(batch.helpers)
        if (!lastFanOut) {
            lastCallerUnparks = 0
            return
        }
        if (fanOut) batch.waking = true
        lastCallerUnparks = batch.wake(workers, callerWakes)
    }

    /** Whether any of workers 0 until [helpers] has announced a park. */
    private fun anyParked(helpers: Int): Boolean {
        var word = 0
        var remaining = helpers
        while (remaining > 0) {
            val mask = if (remaining >= 64) -1L else (1L shl remaining) - 1
            if (parkedBits.get(word) and mask != 0L) return true
            remaining -= 64
            word++
        }
        return false
    }

    /**
     * Server thread, at the start of a tick: workers may spin between waves again, a timed wake that has not
     * fired yet is dropped (the tick came first), and the workers the last tick used are woken now (fanning out
     * as for a wave), so they spin by the time the first wave arrives instead of being woken by it. Workers a
     * timed wake already brought up only restart their spin budget here.
     */
    fun tickStarted() {
        pendingWake.set(null)
        spin.tickStarted()
        val peak = tickPeakHelpers
        tickPeakHelpers = 0
        if (!PREWAKE || peak == 0 || closed || CALLER.get().parallelWaves > 0) return
        publish(WorkBatch(emptyList(), minOf(peak, workers.size) + 1, null))
    }

    /**
     * Server thread, at the end of a tick: [nanosUntilNextTick] is how long the server will sleep before the
     * next one (zero or less when it is behind). With slack, idle workers park at once, and in
     * [IdleWorkers.BALANCED] with a lead, a timer wakes the workers this tick used [SpinPolicy.leadNanos] before
     * the next one is due; in [IdleWorkers.HOT] they spin until just after it is due. A tick that ran nothing in parallel (an
     * idle or frozen server) wakes or keeps nobody. Under pressure workers spin through the gap.
     */
    fun tickEnded(nanosUntilNextTick: Long, sprinting: Boolean) {
        val peak = tickPeakHelpers
        val due = clock.nanoTime() + nanosUntilNextTick
        val gap = spin.tickEnded(nanosUntilNextTick, sprinting, PREWAKE_GRACE_NANOS, ranParallel = peak > 0)
        if (gap == SpinPolicy.Gap.WAKE_EARLY && peak > 0 && !closed) {
            schedule(TimedWake(due - spin.leadNanos, due, minOf(peak, workers.size)))
        }
    }

    /**
     * Server thread: idle-worker behaviour chosen by configuration, applied from the next tick event. A system
     * property override ([SpinPolicy.fromSystemProperties]) wins.
     */
    fun configureIdle(mode: IdleWorkers) = spin.configure(mode)

    /** Idle-worker behaviour in effect. */
    val idleWorkers: IdleWorkers get() = spin.mode

    /** After [tickEnded]: how long before the next tick the server thread should spin ([ServerSpin.arm]). */
    val serverSpinNanos: Long get() = spin.serverWindowNanos

    /** Hands [wake] to the timer, starting it on first use; the timer only ever acts on the latest request. */
    private fun schedule(wake: TimedWake) {
        pendingWake.set(wake)
        val thread = timer ?: PrewakeTimer(this).also {
            timer = it
            it.start()
        }
        LockSupport.unpark(thread)
    }

    /**
     * Timer thread: lets the workers of [wake] spin until just after its tick is due, then publishes an empty
     * batch for them and starts its fan-out, as a wave's caller would. A batch with work still to claim is never
     * replaced: a wave is running (outside a tick, as the tick's own start drops pending wakes) and its
     * wake-up covers its helpers. Each helper is checked by a thread that has seen the new batch, so the Dekker
     * pairing with [WorkerThread.run] holds as for a wave.
     */
    private fun fireTimedWake(wake: TimedWake) {
        if (closed) return
        spin.extendSpin(System.nanoTime() + maxOf(0L, wake.due - clock.nanoTime()) + PREWAKE_GRACE_NANOS)
        val batch = WorkBatch(emptyList(), wake.helpers + 1, null)
        val fanOut = callerWakes != Int.MAX_VALUE
        if (fanOut) batch.waking = true
        while (true) {
            val seen = current.get()
            if (seen != null && !seen.fullyClaimed) {
                timedWakesSkipped.incrementAndGet()
                return
            }
            if (current.compareAndSet(seen, batch)) break
        }
        timedWakes.incrementAndGet()
        batch.wake(workers, callerWakes)
    }

    /** For tests: whether [worker] has announced a park and not yet left it. */
    internal fun isParked(worker: Int): Boolean = workers[worker].parked

    /** For tests: timed wakes that have published a batch so far. */
    internal val timedWakeCount: Int get() = timedWakes.get()

    /** For tests: timed wakes that found a wave with work left to claim and left it alone. */
    internal val timedWakeSkips: Int get() = timedWakesSkipped.get()

    /** For tests: whether a timed wake is scheduled and has not fired or been dropped. */
    internal val timedWakePending: Boolean get() = pendingWake.get() != null

    /** For tests: whether the timer thread exists and is alive. */
    internal val timerAlive: Boolean get() = timer?.isAlive == true

    /** Called after the last wave; finishes late helpers and releases the pool's threads, the timer's included. */
    override fun close() {
        closed = true
        pendingWake.set(null)
        workers.forEach(LockSupport::unpark)
        val timer = timer
        if (timer != null) LockSupport.unpark(timer)
        var interrupted = false
        for (thread in workers.asList() + listOfNotNull(timer)) {
            if (thread === Thread.currentThread()) continue
            while (thread.isAlive) {
                try {
                    thread.join()
                } catch (_: InterruptedException) {
                    interrupted = true
                }
            }
        }
        if (interrupted) Thread.currentThread().interrupt()
    }

    /** A wake-up the timer issues at [at] ([clock] time) for a tick due at [due], for workers 0 until [helpers]. */
    private class TimedWake(val at: Long, val due: Long, val helpers: Int)

    /**
     * Sleeps until the pending wake is due and fires it. It only acts on a request it removed from
     * [pendingWake] itself, so each fires at most once, and one that [tickStarted] or [close] dropped, or a newer
     * one replaced, before that never fires. One taken just before a tick started can still fire into the tick:
     * it then finds a wave it leaves alone, or wakes workers that tick may use anyway, for at most its lead and
     * grace of spinning.
     * After [close] it exits; [close] unparks and joins it. A timer started after [close] read its field sees
     * [closed] (set first) at once.
     */
    private class PrewakeTimer(private val pool: WorkerPool) : Thread("Vanadium-Prewake") {
        init {
            isDaemon = true
        }

        override fun run() {
            while (!pool.closed) {
                val wake = pool.pendingWake.get()
                if (wake == null) {
                    LockSupport.park(pool)
                } else {
                    val remaining = wake.at - pool.clock.nanoTime()
                    if (remaining > 0) {
                        pool.clock.park(pool, remaining)
                    } else if (pool.pendingWake.compareAndSet(wake, null)) {
                        pool.fireTimedWake(wake)
                    }
                }
                interrupted() // Never interrupted for control; ignore strays.
            }
        }
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

        /** Whether no task is left to claim: a worker that has not seen this batch could not help it. */
        val fullyClaimed: Boolean get() = counters.get(NEXT) >= taskCount

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
         * Parking: [parked] and the pool's parked bit are written before [WorkerPool.current] is read again, and a
         * waker reads them after publishing. Either the waker sees the park announced and unparks (the permit
         * survives until the park), or this thread's recheck sees the new batch. A worker that has parked
         * parks again on a stray return instead of spinning: it only leaves for work or for close.
         */
        override fun run() {
            var seen: WorkBatch? = null
            var idleSince = System.nanoTime()
            var rested = false
            while (true) {
                val batch = pool.current.get()
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
                if (!rested) {
                    val now = System.nanoTime()
                    if (now - idleSince < pool.spin.workerSpinNanos || pool.spin.spinUntil.get() - now > 0) {
                        onSpinWait()
                        continue
                    }
                }
                val hook = pool.parkHook
                hook?.at(index, ParkPoint.BEFORE_ANNOUNCE)
                parked = true
                pool.parkedBits.getAndAccumulate(index ushr 6, 1L shl index, SET_BIT)
                hook?.at(index, ParkPoint.BEFORE_RECHECK)
                if (pool.current.get() === seen && !pool.closed) {
                    hook?.at(index, ParkPoint.BEFORE_PARK)
                    LockSupport.park(pool)
                    rested = true
                }
                pool.parkedBits.getAndAccumulate(index ushr 6, 1L shl index, CLEAR_BIT)
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

        /**
         * How long past the due time pre-woken workers keep spinning for a tick that has not started: the server
         * thread's own wake-up and packet handling come first. A later tick start wakes them as before.
         */
        private val PREWAKE_GRACE_NANOS = java.lang.Long.getLong("vanadium.prewakeGraceMicros", 1_000) * 1_000L

        private val SET_BIT = LongBinaryOperator { word, bit -> word or bit }
        private val CLEAR_BIT = LongBinaryOperator { word, bit -> word and bit.inv() }

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
 * What idle workers do between ticks that leave slack. Chosen with `idleWorkers` in the config, or
 * `-Dvanadium.idleWorkers`; `vanadium.workerSpinMicros` replaces all of it with one fixed spin.
 */
enum class IdleWorkers {
    /**
     * Workers park when a tick ends and wake when the next starts (or, with `vanadium.prewakeLeadMicros`, that
     * long before it is due); the server thread spins through its wait for the next tick ([ServerSpin]).
     * About one core while ticks run parallel work.
     */
    BALANCED,

    /** Workers and the server thread spin through the gap between ticks: every worker that ran holds a core. */
    HOT,

    /** Nothing spins between ticks: workers park when a tick ends and wake when the next one starts. */
    LEAN;

    val configName: String get() = name.lowercase()

    companion object {
        val names: List<String> = entries.map { it.configName }

        /** Case-insensitive; null for anything else. */
        fun parse(text: String): IdleWorkers? = entries.firstOrNull { it.name.equals(text.trim(), ignoreCase = true) }
    }
}

/**
 * How long idle workers spin before parking, from the server's tick events. Spinning costs a core per
 * worker; parking costs a wake-up (hundreds of microseconds on a VM) when the next wave comes, and a core
 * left idle for the rest of a 50 ms tick comes back slow.
 *
 * - Under pressure (sprinting, or the last tick ended less than [longNanos] before the next was due, or less
 *   than the lead in [IdleWorkers.BALANCED]), workers spin [longNanos] through every gap, including the one
 *   between ticks: throughput is what matters, and the next wave is close.
 * - With slack, workers spin [shortNanos] between waves of a tick ([hotNanos] in [IdleWorkers.HOT]); when the
 *   tick ends, [betweenTicks] says what the pool does until the next one.
 *
 * Without tick events (tests, benchmarks, before the first tick) workers spin [longNanos]. A fixed policy
 * ignores tick events. Events come from the server thread; workers only read [workerSpinNanos].
 */
internal class SpinPolicy(
    private val longNanos: Long,
    private val shortNanos: Long,
    private val fixed: Boolean,
    /** How long before a tick is due [IdleWorkers.BALANCED] wakes its workers. */
    val leadNanos: Long = DEFAULT_LEAD_MICROS * 1_000L,
    /** A mode set by system property; configuration cannot change it. */
    private val forced: IdleWorkers? = null,
    private val hotNanos: Long = HOT_SPIN_MICROS * 1_000L,
    /** How long before a tick is due the server thread starts spinning in [IdleWorkers.BALANCED]. */
    private val serverLeadNanos: Long = DEFAULT_SERVER_LEAD_MICROS * 1_000L,
) {
    /** What the pool does at the end of a tick. */
    enum class Gap {
        /** Under pressure or fixed: keep the spin budget, nothing more. */
        PRESSURE,

        /** Park now; a timer wakes the workers [leadNanos] before the next tick. */
        WAKE_EARLY,

        /** Spin until just after the next tick is due. */
        SPIN_THROUGH,

        /** Park now; the next tick's start wakes them. */
        PARK,
    }

    @Volatile
    var workerSpinNanos: Long = longNanos
        private set

    /**
     * [System.nanoTime] until which idle workers keep spinning whatever [workerSpinNanos] says: set by a timed
     * wake (timer thread) to just past the tick it wakes them for, and in [IdleWorkers.HOT] by [tickEnded] to just
     * past the next tick. Only raised, except by [tickEnded] when workers are to park.
     */
    val spinUntil = AtomicLong(System.nanoTime())

    var mode: IdleWorkers = forced ?: IdleWorkers.BALANCED
        private set

    /** Whether the last tick ended with the next one due soon (see the class comment), or the server was sprinting. */
    var underPressure = true
        private set

    var inTick = true
        private set

    /** Set by [tickEnded]: what the pool does until the next tick. */
    var betweenTicks = Gap.PRESSURE
        private set

    /**
     * Set by [tickEnded]: how long before the next tick the server thread spins instead of sleeping ([ServerSpin]);
     * [Long.MAX_VALUE] spins through the whole gap. Zero after a tick that ran nothing in parallel.
     */
    var serverWindowNanos = 0L
        private set

    /** Applies [mode] from the next tick event, unless a system property forced one. */
    fun configure(mode: IdleWorkers) {
        if (forced == null) this.mode = mode
    }

    fun tickStarted() {
        inTick = true
        update()
    }

    /**
     * Returns [betweenTicks]. Spinning through the gap is arranged before the spin budget drops, so no worker
     * parks in between; [graceNanos] is how long past the due time workers keep spinning for a late tick. Without
     * parallel work in the tick ([ranParallel] false) nobody spins through the gap.
     */
    fun tickEnded(nanosUntilNextTick: Long, sprinting: Boolean, graceNanos: Long = 0L, ranParallel: Boolean = true): Gap {
        inTick = false
        val threshold = if (mode == IdleWorkers.BALANCED) maxOf(longNanos, leadNanos) else longNanos
        underPressure = sprinting || nanosUntilNextTick < threshold
        val gap = when {
            fixed || underPressure -> Gap.PRESSURE
            mode == IdleWorkers.BALANCED && leadNanos > 0 -> Gap.WAKE_EARLY
            mode == IdleWorkers.HOT && ranParallel -> Gap.SPIN_THROUGH
            else -> Gap.PARK
        }
        betweenTicks = gap
        serverWindowNanos = when {
            !ranParallel -> 0L
            mode == IdleWorkers.HOT -> Long.MAX_VALUE
            mode == IdleWorkers.BALANCED -> serverLeadNanos
            else -> 0L
        }
        val now = System.nanoTime()
        when (gap) {
            Gap.SPIN_THROUGH -> extendSpin(now + nanosUntilNextTick + graceNanos)
            // A timed wake for a tick that ended within its grace period must not keep workers spinning.
            Gap.WAKE_EARLY, Gap.PARK -> spinUntil.set(now)
            Gap.PRESSURE -> {}
        }
        update()
        return gap
    }

    /** Raises [spinUntil] to [until]; never lowers it, as the timer and the server thread may both extend it. */
    fun extendSpin(until: Long) {
        spinUntil.getAndAccumulate(until, LATER)
    }

    private fun update() {
        workerSpinNanos = when {
            fixed -> longNanos
            mode == IdleWorkers.HOT -> if (inTick || underPressure) maxOf(hotNanos, longNanos) else 0L
            underPressure -> longNanos
            inTick -> shortNanos
            else -> 0L
        }
    }

    companion object {
        /** The later of two [System.nanoTime] values, safe across wrap-around. */
        private val LATER = LongBinaryOperator { a, b -> if (b - a > 0) b else a }

        /** Idle spin under pressure: two milliseconds covers inter-wave gaps on the Xeon VM. */
        private const val LONG_SPIN_MICROS = 2_000L

        /** Idle spin between waves of a tick with slack: covers the usual serial gaps, not long serial phases. */
        private const val SHORT_SPIN_MICROS = 500L

        /** Idle spin between waves in [IdleWorkers.HOT]: longer than any normal serial phase of a tick. */
        private const val HOT_SPIN_MICROS = 50_000L

        /**
         * How long before the next tick [IdleWorkers.BALANCED] wakes workers; none by default, as no lead from
         * 0.5 to 10 ms made 20 TPS ticks faster on the Xeon VM (see docs/PERFORMANCE.md).
         */
        const val DEFAULT_LEAD_MICROS = 0L

        /**
         * How long before the next tick the server thread spins in [IdleWorkers.BALANCED]: through the whole gap.
         * A 1 or 5 ms window measured no faster than none (see docs/PERFORMANCE.md).
         */
        const val DEFAULT_SERVER_LEAD_MICROS = Long.MAX_VALUE / 1_000L

        /**
         * `vanadium.workerSpinMicros` fixes the spin and ignores ticks; `vanadium.workerSlackSpinMicros` sets the
         * short one, `vanadium.prewakeLeadMicros` the lead, and `vanadium.idleWorkers` forces a mode.
         */
        fun fromSystemProperties(): SpinPolicy {
            val fixedMicros = java.lang.Long.getLong("vanadium.workerSpinMicros")
            if (fixedMicros != null) return SpinPolicy(fixedMicros * 1_000L, fixedMicros * 1_000L, fixed = true)
            val shortMicros = java.lang.Long.getLong("vanadium.workerSlackSpinMicros", SHORT_SPIN_MICROS)
            val leadMicros = java.lang.Long.getLong("vanadium.prewakeLeadMicros", DEFAULT_LEAD_MICROS).coerceAtLeast(0L)
            val forced = System.getProperty("vanadium.idleWorkers")?.let { text ->
                IdleWorkers.parse(text) ?: throw IllegalArgumentException(
                    "vanadium.idleWorkers must be one of ${IdleWorkers.names.joinToString()} (got '$text')"
                )
            }
            return SpinPolicy(LONG_SPIN_MICROS * 1_000L, shortMicros * 1_000L, fixed = false, leadMicros * 1_000L, forced)
        }

        /** The mode text `vanadium.idleWorkers` forces, if any; for status output. */
        val forcedMode: String? get() = System.getProperty("vanadium.idleWorkers")

        /** Whether `vanadium.workerSpinMicros` replaces the tick-driven policy; for status output. */
        val fixedSpin: Boolean get() = System.getProperty("vanadium.workerSpinMicros") != null
    }
}

/** Time source for timed wakes; tests substitute one they advance by hand. */
internal interface PrewakeClock {
    fun nanoTime(): Long

    /** Waits up to [nanos] or until unparked; may return early. */
    fun park(blocker: Any, nanos: Long)

    companion object {
        val SYSTEM = object : PrewakeClock {
            override fun nanoTime(): Long = System.nanoTime()
            override fun park(blocker: Any, nanos: Long) = LockSupport.parkNanos(blocker, nanos)
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
