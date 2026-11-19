package com.synsenetwork.vanadium.tick

import com.synsenetwork.vanadium.config.DeveloperProperties
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicIntegerArray
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicLongArray
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.LockSupport
import java.util.function.LongBinaryOperator
import java.util.function.LongConsumer
import java.util.function.Supplier

/**
 * Persistent workers with caller participation and a full barrier per wave. Dispatch uses a volatile
 * work batch and Dekker wake-ups; small waves use low-numbered workers. Claims and completions are
 * batched to reduce contention. Tick-path locks must work on both the caller and workers.
 *
 * Wake-up fans out: the caller unparks at most [CALLER_WAKES] parked helpers, and every helper that joins a
 * waking batch unparks further ones from the batch's shared wake cursor before it claims work, so the server
 * thread never issues a wake-up per worker. Idle workers spin for [SpinPolicy]'s budget, which follows the
 * server's tick events, then park. Between ticks, [IdleWorkers] decides whether they park until the next tick's
 * start wakes them, or keep spinning.
 *
 * A wave is claimed in list order through one shared cursor, or, given [WaveHomes], home by home: each participant
 * runs its own homes' tasks first and takes others' only when it has none left, so the same tasks (and the data they
 * touch) stay on the same thread from wave to wave.
 */
class WorkerPool internal constructor(
    threads: Int,
    private val spin: SpinPolicy,
    /** Parked helpers the caller unparks itself; [Int.MAX_VALUE] is the serial loop, kept for benchmarks. */
    private val callerWakes: Int = CALLER_WAKES,
) : AutoCloseable {
    constructor(threads: Int) : this(threads, SpinPolicy.fromSystemProperties())

    private val workers = Array(maxOf(1, threads)) { WorkerThread(this, it) }

    /** The latest batch: a wave, or a tick start's wake-up. Only the thread that runs waves publishes. */
    private val current = AtomicReference<WorkBatch?>()

    @Volatile
    private var closed = false

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
     * The latest published wave's progress, for the watchdog crash report; any thread may call it, and it reads
     * counters only. Waves that run inline on their caller are never published.
     */
    internal fun latestWaveProgress(): String = current.get()?.progress() ?: "no parallel wave yet"

    /**
     * Runs every task and blocks until all have finished (a full barrier). A single-task wave runs
     * inline. Interruption does not cancel a wave: the caller waits for all tasks, then restores its
     * interrupt flag. Do not change [tasks] until this returns; only one thread may run waves at a time.
     *
     * @param callerWait optional profiling callback for the caller's tail-wait time
     * @param maxParticipants threads that may run tasks, the caller included; 1 runs the wave inline
     * @param homes where each task belongs ([WaveHomes]), or null to claim tasks in list order; read before
     *   this returns only, so the caller may reuse it afterwards
     * @return time participants spent claiming and running tasks, summed over participants
     */
    fun runWave(tasks: Collection<Runnable>, callerWait: LongConsumer? = null, maxParticipants: Int = Int.MAX_VALUE,
                homes: WaveHomes? = null): Long {
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
        val caller = if (helpers > 0) CALLER.get() else null
        val batch = WorkBatch(indexed, helpers + 1, Thread.currentThread(), caller, homes)
        if (caller != null) {
            caller.parallelWaves++
            if (helpers > tickPeakHelpers) tickPeakHelpers = helpers
            publish(batch)
        }
        var waveFailure: Throwable? = null
        try {
            batch.participate(caller, CALLER_SLOT)
            val waitStart = if (callerWait != null) System.nanoTime() else 0
            try {
                batch.awaitCompletion(caller, maxOf(CALLER_SPIN_FLOOR_NANOS, spin.callerSpinNanos))
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
     * Server thread, at the start of a tick: workers may spin between waves again, and the workers the last tick
     * used are woken now (fanning out as for a wave), so they spin by the time the first wave arrives instead of
     * being woken by it.
     */
    fun tickStarted() {
        spin.tickStarted()
        val peak = tickPeakHelpers
        tickPeakHelpers = 0
        if (peak == 0 || closed || CALLER.get().parallelWaves > 0) return
        publish(WorkBatch(emptyList(), minOf(peak, workers.size) + 1, null))
    }

    /**
     * Server thread, at the end of a tick: [nanosUntilNextTick] is how long the server will sleep before the
     * next one (zero or less when it is behind). With slack, idle workers park at once, except in
     * [IdleWorkers.HOT], where they spin until just after it is due. A tick that ran nothing in parallel (an idle
     * or frozen server) keeps nobody spinning. Under pressure (sprinting, or the next tick due within the long
     * spin) workers keep their long spin budget across the gap ([SpinPolicy.Gap.PRESSURE]).
     */
    fun tickEnded(nanosUntilNextTick: Long, sprinting: Boolean) {
        spin.tickEnded(nanosUntilNextTick, sprinting, HOT_GRACE_NANOS, ranParallel = tickPeakHelpers > 0)
    }

    /** Server thread: idle-worker behaviour chosen by configuration, applied from the next tick event. */
    fun configureIdle(mode: IdleWorkers) = spin.configure(mode)

    /** Idle-worker behaviour in effect. */
    val idleWorkers: IdleWorkers get() = spin.mode

    /** After [tickEnded]: how long before the next tick the server thread should spin ([ServerSpin.arm]). */
    val serverSpinNanos: Long get() = spin.serverWindowNanos

    /** For tests: whether [worker] has announced a park and not yet left it. */
    internal fun isParked(worker: Int): Boolean = workers[worker].parked

    /** Called after the last wave; finishes late helpers and releases the pool's threads. */
    override fun close() {
        closed = true
        workers.forEach(LockSupport::unpark)
        var interrupted = false
        for (thread in workers) {
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

    /**
     * A wave's tasks, or with no tasks a wake-up only ([tickStarted]); [waiter] is null for the latter. [caller] is
     * the waiter's state when the wave has helpers: it serves their [callOnWaveCaller] requests.
     */
    private class WorkBatch(
        private val tasks: List<Runnable>,
        participants: Int,
        private val waiter: Thread?,
        private val caller: CallerState? = null,
        homes: WaveHomes? = null,
    ) {
        private val taskCount = tasks.size
        val helpers = participants - 1

        /** [WaveHomes.phases] and [WaveHomes.homes] of a homed wave; 0 for a wave claimed in list order. */
        private val phases = homes?.phases ?: 0
        private val homeCount = homes?.homes ?: 0

        /**
         * Slots per home in [cells], a whole number of cache lines: a home's cells share lines only with each other.
         * Home h starts at (h + 1) strides; the first and last stride are padding, so no other object's fields
         * (this batch's, read on every claim) share a line with a cell that is written.
         */
        private val stride = maxOf(1, (phases + CELLS_PER_LINE - 1) / CELLS_PER_LINE) * CELLS_PER_LINE

        /**
         * A homed wave's unclaimed tasks: per home and phase, the next task from the front (high half) and the end
         * (low half). The home's owner claims from the front, others from the end. Copied from [WaveHomes] here, on
         * the caller, so a late helper never reads the caller's layout.
         */
        private val cells = homes?.let { layout ->
            require(layout.starts[0] == 0 && layout.starts[phases * homeCount] == taskCount) { "homes do not cover the wave" }
            AtomicLongArray((homeCount + 2) * stride).also { cells ->
                for (phase in 0 until phases) for (home in 0 until homeCount) {
                    val cell = phase * homeCount + home
                    cells.set((home + 1) * stride + phase, layout.starts[cell].toLong() shl 32 or layout.starts[cell + 1].toLong())
                }
            }
        }

        /** Chunks only when tasks greatly outnumber participants; cells stay individually balanced. */
        private val chunk = maxOf(1, taskCount / (participants * 8))

        /**
         * Claim cursor, completion counter, wake cursor and, for a homed wave, its leading phases some participant
         * found empty (so others skip scanning them; cells never refill), 64 bytes apart so they never share a cache
         * line.
         */
        private val counters = AtomicIntegerArray(EMPTY_PHASES + 1).apply { set(REMAINING, taskCount) }
        private val error = AtomicReference<Throwable?>()
        private val busy = AtomicLong()

        @Volatile
        private var waiterParked = false

        /**
         * Whether no task is left to claim: a worker that has not seen this batch could not help it. A homed wave
         * reports it once a participant has found every cell empty.
         */
        val fullyClaimed: Boolean get() = if (cells != null) counters.get(EMPTY_PHASES) >= phases else counters.get(NEXT) >= taskCount

        /** Set by the caller, after publishing, when some worker may be parked: joining helpers then wake others. */
        @Volatile
        var waking = false

        val busyNanos: Long get() = busy.get()

        /**
         * [latestWaveProgress]. A participant counts the tasks it ran as finished only when it runs out of work, so
         * every task of a participant still running counts as unfinished.
         */
        fun progress(): String {
            if (taskCount == 0) return "no parallel wave since the tick started"
            var unclaimed = 0
            if (cells == null) {
                unclaimed = maxOf(0, taskCount - counters.get(NEXT))
            } else {
                for (home in 0 until homeCount) for (phase in 0 until phases) {
                    val state = cells.get((home + 1) * stride + phase)
                    unclaimed += maxOf(0, state.toInt() - (state ushr 32).toInt())
                }
            }
            val finished = taskCount - counters.get(REMAINING)
            return "$finished of $taskCount tasks finished, $unclaimed unclaimed, ${helpers + 1} participants"
        }

        /**
         * Takes helper indices from the wake cursor and unparks those workers that are parked, until [limit]
         * were unparked, every helper has been taken, or no task is left to claim (a worker woken then would
         * find nothing). Each index is taken once, so every helper is checked by exactly one thread, and that
         * thread has seen this batch published: the Dekker pairing with [WorkerThread.run] holds whoever wakes.
         * Reads only counters, never the task list. Returns the number unparked.
         */
        fun wake(workers: Array<WorkerThread>, limit: Int): Int {
            var unparked = 0
            while (unparked < limit && (taskCount == 0 || !fullyClaimed)) {
                val index = counters.getAndIncrement(WAKE)
                if (index >= helpers) break
                if (workers[index].wake()) unparked++
            }
            return unparked
        }

        /**
         * Claims and runs tasks as participant [slot] (the caller is [CALLER_SLOT], worker i is i + 1); the caller
         * passes its [serving] state and serves helpers' requests between claims.
         */
        fun participate(serving: CallerState?, slot: Int) {
            if (taskCount == 0) return
            if (cells != null) {
                participateHomed(serving, slot, cells)
                return
            }
            var done = 0
            var begin = 0L
            // A late helper uses the saved count and never reads a list already reused by the caller.
            while (true) {
                if (serving != null && serving.hasRequests) serving.serveRequests()
                val start = counters.getAndAdd(NEXT, chunk)
                if (start >= taskCount) break
                if (done == 0) begin = System.nanoTime()
                val end = minOf(taskCount, start + chunk)
                for (i in start until end) runTask(i)
                done += end - start
            }
            if (done == 0) return
            // Busy time is recorded before completion so the caller reads it complete after the barrier.
            busy.addAndGet(System.nanoTime() - begin)
            if (counters.addAndGet(REMAINING, -done) == 0 && waiterParked) LockSupport.unpark(waiter)
        }

        /**
         * A homed wave: phase by phase, this slot's own homes ([WaveHomes] says which) from their fronts, then every
         * other home of the phase from its end, nearest home first, then the next phase. Every phase is claimed out
         * before a participant starts the next, as a list-order claim would, but each slot runs the same homes
         * every wave while the layout stays the same.
         *
         * The loops are split into small methods ([phase], [drain]) so that each is compiled on its own and every
         * activation is short: a long-running activation stays interpreted after a deoptimization until it returns.
         */
        private fun participateHomed(serving: CallerState?, slot: Int, cells: AtomicLongArray) {
            val begin = System.nanoTime()
            var done = 0
            for (phase in 0 until phases) done += phase(serving, slot, cells, phase)
            if (done == 0) return
            busy.addAndGet(System.nanoTime() - begin)
            if (counters.addAndGet(REMAINING, -done) == 0 && waiterParked) LockSupport.unpark(waiter)
        }

        /** One phase of [participateHomed]; returns the tasks this participant ran. */
        private fun phase(serving: CallerState?, slot: Int, cells: AtomicLongArray, phase: Int): Int {
            if (counters.get(EMPTY_PHASES) > phase) return 0
            val homes = homeCount
            val participants = helpers + 1
            var done = 0
            var home = slot
            while (home < homes) {
                done += drain(serving, cells, (home + 1) * stride + phase, true)
                home += participants
            }
            for (step in 1 until homes) {
                // +1, -1, +2, -2, …: every other home once.
                val offset = if (step and 1 == 1) (step + 1) / 2 else -(step / 2)
                done += drain(serving, cells, ((slot + offset).mod(homes) + 1) * stride + phase, false)
            }
            if (counters.get(EMPTY_PHASES) <= phase) counters.accumulateAndGet(EMPTY_PHASES, phase + 1, MAX)
            return done
        }

        /** Claims and runs [cell]'s tasks, from the front for its owner, until none is left; returns how many ran. */
        private fun drain(serving: CallerState?, cells: AtomicLongArray, cell: Int, front: Boolean): Int {
            var done = 0
            while (true) {
                if (serving != null && serving.hasRequests) serving.serveRequests()
                val task = claim(cells, cell, front)
                if (task < 0) return done
                runTask(task)
                done++
            }
        }

        /** Takes one task of [cell] from its front or its end; -1 when it has none left. */
        private fun claim(cells: AtomicLongArray, cell: Int, front: Boolean): Int {
            while (true) {
                val state = cells.get(cell)
                val next = (state ushr 32).toInt()
                val end = state.toInt()
                if (next >= end) return -1
                if (front) {
                    if (cells.compareAndSet(cell, state, state + (1L shl 32))) return next
                } else if (cells.compareAndSet(cell, state, state - 1)) {
                    return end - 1
                }
            }
        }

        /**
         * Runs one task, recording its failure. Every wave's tasks, homed or not and of every stage, run through this
         * one call site, so its receiver types are many and the JIT calls them instead of inlining one: inlined, an
         * entity tick made the claim loops' compiled code too large, and slow when the compiler gave up.
         */
        private fun runTask(index: Int) {
            try {
                tasks[index].run()
            } catch (t: Throwable) {
                error.compareAndSet(null, t)
            }
        }

        /**
         * The caller's wait for the tail: it spins up to [spinNanos], then parks until the last task finishes. It
         * serves helpers' requests as they arrive: a helper waiting for one keeps its task, and so this wait, from
         * finishing. A requester unparks the caller after queueing, so a request queued after the check below leaves
         * a permit and the park returns at once.
         */
        fun awaitCompletion(serving: CallerState?, spinNanos: Long) {
            var interrupted = false
            val deadline = System.nanoTime() + spinNanos
            while (counters.get(REMAINING) != 0) {
                if (serving != null && serving.hasRequests) {
                    serving.serveRequests()
                    continue
                }
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

        /** A helper's task: queues [work] for the caller and waits for it; see [WorkerPool.callOnWaveCaller]. */
        fun <T> callOnCaller(work: Supplier<T>, stalled: Runnable?): T {
            val state = caller ?: return work.get()
            val request = CallerRequest(work, Thread.currentThread())
            state.requests.add(request)
            LockSupport.unpark(waiter)
            val since = System.nanoTime()
            var reported = stalled == null
            while (!request.done) {
                LockSupport.parkNanos(request, REQUEST_CHECK_NANOS)
                if (!reported && System.nanoTime() - since > REQUEST_STALL_NANOS) {
                    reported = true
                    stalled?.run()
                }
            }
            request.failure?.let { throw it }
            @Suppress("UNCHECKED_CAST")
            return request.result as T
        }

        private companion object {
            const val NEXT = 0
            const val REMAINING = 16
            const val WAKE = 32
            const val EMPTY_PHASES = 48
            val MAX = java.util.function.IntBinaryOperator(Math::max)

            /** Longs per 64-byte cache line. */
            const val CELLS_PER_LINE = 8
        }
    }

    private class WorkerThread(private val pool: WorkerPool, private val index: Int) : TickThread("Vanadium-Worker-${index + 1}") {
        @Volatile
        var parked = false
            private set

        /** The deferral state of the task running on this thread; touched only by this thread. */
        val deferral = Deferral()

        /** The batch whose tasks this thread is running, for [callOnWaveCaller]; touched only by this thread. */
        var running: WorkBatch? = null

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
                        running = batch
                        batch.participate(null, index + 1)
                        running = null
                        idleSince = System.nanoTime()
                        rested = false
                    }
                    continue
                }
                if (pool.closed) return
                if (!rested) {
                    val now = System.nanoTime()
                    if (now - idleSince < pool.spin.workerSpinNanos || pool.spin.spinUntil - now > 0) {
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
        /** The wave caller's participant slot in a homed wave; worker i is slot i + 1. */
        const val CALLER_SLOT = 0

        /**
         * Least caller spin while waiting for the wave tail before parking; [SpinPolicy.callerSpinNanos] raises it
         * under pressure.
         */
        private const val CALLER_SPIN_FLOOR_NANOS = 200_000L

        /**
         * Parked helpers the caller unparks itself; each of them unparks more. Two start the fan-out on two
         * threads while costing the server thread only two wake-ups.
         */
        internal const val CALLER_WAKES = 2

        /**
         * How long past the due time [IdleWorkers.HOT] workers keep spinning for a tick that has not started: the
         * server thread's own wake-up and packet handling come first. A later tick start wakes them as before.
         */
        private const val HOT_GRACE_NANOS = 1_000_000L

        private val SET_BIT = LongBinaryOperator { word, bit -> word or bit }
        private val CLEAR_BIT = LongBinaryOperator { word, bit -> word and bit.inv() }

        /** How often a helper waiting for its [callOnWaveCaller] request checks whether it has stalled. */
        private const val REQUEST_CHECK_NANOS = 1_000_000_000L

        /** How long a [callOnWaveCaller] request may wait before its `stalled` callback runs. */
        private val REQUEST_STALL_NANOS = DeveloperProperties.long("vanadium.callerRequestStallSeconds", 10, 1L..3_600L) * 1_000_000_000L

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
         * From a wave task on a helper: runs [work] on the thread that runs the wave (the server thread) and returns
         * its result, or rethrows its failure here. For work only that thread can do, when the task cannot be
         * deferred: the caller runs it between its own tasks and while it waits for the wave's tail, so the wait ends
         * once the caller's current task does. Hold no lock that the caller's tasks or [work] may wait for. [stalled]
         * runs once on this thread if the wait exceeds `vanadium.callerRequestStallSeconds` (10 s). Anywhere else,
         * including on the caller, runs [work] here.
         */
        @JvmStatic
        fun <T> callOnWaveCaller(work: Supplier<T>, stalled: Runnable? = null): T {
            val thread = Thread.currentThread()
            val batch = (thread as? WorkerThread)?.running ?: return work.get()
            return batch.callOnCaller(work, stalled)
        }

        /** For tests, on a wave's caller: whether a helper's [callOnWaveCaller] request is waiting to be served. */
        internal fun callerHasRequests(): Boolean = CALLER.get().hasRequests

        /**
         * For schedulers that run a claim loop inside one pool task: the caller must also serve requests
         * between regions and while waiting for dependencies. Otherwise a region can wait for the caller
         * while the caller waits for that region. Cache once per participant; helpers get no pump.
         * Invoke only outside a region, with no claim or task lock held.
         */
        internal fun callerRequestPump(): Runnable? =
            if (Thread.currentThread() is WorkerThread) null else CALLER.get().takeIf { it.parallelWaves > 0 }

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

    /** A helper's [callOnWaveCaller] work; [result] and [failure] are published by [done]. */
    private class CallerRequest(val work: Supplier<*>, val requester: Thread) {
        var result: Any? = null
        var failure: Throwable? = null

        @Volatile
        var done = false
    }

    private class CallerState : Deferral(), Runnable {
        /** Nesting depth of waves with helpers that this thread is running. */
        var parallelWaves = 0
        val afterWave = ArrayList<Runnable>()

        /** Helpers' [callOnWaveCaller] work, from every wave this thread is running (nested ones included). */
        val requests = ConcurrentLinkedQueue<CallerRequest>()

        val hasRequests: Boolean get() = !requests.isEmpty()

        override fun run() {
            if (hasRequests) serveRequests()
        }

        /**
         * Runs every queued request on this thread, outside any task of its own, and releases its requester. The
         * work is never deferrable: it is the rerun the requester could not do itself.
         */
        fun serveRequests() {
            while (true) {
                val request = requests.poll() ?: return
                val outerDeferrable = deferrable
                val outerDeferred = deferred
                deferrable = false
                try {
                    request.result = request.work.get()
                } catch (t: Throwable) {
                    request.failure = t
                } finally {
                    deferrable = outerDeferrable
                    deferred = outerDeferred
                }
                request.done = true
                LockSupport.unpark(request.requester)
            }
        }

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

/** A pool worker. [context] gives tick code per-thread state without a thread-local lookup. */
abstract class TickThread(name: String) : Thread(name) {
    @JvmField
    var context: Any? = null
}

/** Unwinds a worker task that needs the server thread; see [WorkerPool.runDeferrable]. No stack trace is captured. */
class ServerThreadRequired(what: String) : RuntimeException("$what requires the server thread", null, false, false)
