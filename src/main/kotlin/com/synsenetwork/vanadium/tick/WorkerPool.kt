package com.synsenetwork.vanadium.tick

import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicIntegerArray
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.LockSupport
import java.util.function.LongConsumer

/**
 * Persistent workers with caller participation and a full barrier per wave. Dispatch uses a volatile
 * work batch and Dekker wake-ups; small waves use low-numbered workers. Claims and completions are
 * batched to reduce contention. Tick-path locks must work on both the caller and workers.
 */
class WorkerPool(threads: Int) : AutoCloseable {
    private val workers = Array(maxOf(1, threads)) { WorkerThread(this, it) }

    @Volatile
    private var current: WorkBatch? = null

    @Volatile
    private var closed = false

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
     */
    fun runWave(tasks: Collection<Runnable>, callerWait: LongConsumer? = null) {
        val n = tasks.size
        if (n == 0) return
        if (n == 1) {
            tasks.first().run()
            return
        }
        // Claims index the list directly; anything without cheap random access is copied first.
        val indexed = if (tasks is List<Runnable> && tasks is RandomAccess) tasks else tasks.toList()
        val rejected = closed
        val helpers = if (rejected) 0 else minOf(workers.size, n - 1) // At most n participants with the caller.
        val batch = WorkBatch(indexed, helpers + 1, Thread.currentThread())
        val caller = if (helpers > 0) CALLER.get() else null
        if (caller != null) {
            caller.parallelWaves++
            current = batch
            for (i in 0 until helpers) workers[i].wake()
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
    }

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

    private class WorkBatch(private val tasks: List<Runnable>, participants: Int, private val waiter: Thread) {
        private val taskCount = tasks.size
        val helpers = participants - 1

        /** Chunks only when tasks greatly outnumber participants; cells stay individually balanced. */
        private val chunk = maxOf(1, taskCount / (participants * 8))

        /** Claim cursor and completion counter, 64 bytes apart so they never share a cache line. */
        private val counters = AtomicIntegerArray(REMAINING + 1).apply { set(REMAINING, taskCount) }
        private val error = AtomicReference<Throwable?>()

        @Volatile
        private var waiterParked = false

        fun participate() {
            var done = 0
            // A late helper uses the saved count and never reads a list already reused by the caller.
            while (true) {
                val start = counters.getAndAdd(NEXT, chunk)
                if (start >= taskCount) break
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
            if (done > 0 && counters.addAndGet(REMAINING, -done) == 0 && waiterParked) LockSupport.unpark(waiter)
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
        }
    }

    private class WorkerThread(private val pool: WorkerPool, private val index: Int) : TickThread("Vanadium-Worker-${index + 1}") {
        @Volatile
        private var parked = false

        /** The deferral state of the task running on this thread; touched only by this thread. */
        val deferral = Deferral()

        init {
            isDaemon = true
        }

        /** Dekker pairing with [run]: the batch is published before [parked] is read. */
        fun wake() {
            if (parked) LockSupport.unpark(this)
        }

        override fun run() {
            var seen: WorkBatch? = null
            var deadline = System.nanoTime() + WORKER_SPIN_NANOS
            while (true) {
                val batch = pool.current
                if (batch !== seen) {
                    seen = batch
                    if (batch != null && index < batch.helpers) {
                        batch.participate()
                        deadline = System.nanoTime() + WORKER_SPIN_NANOS
                    }
                    continue
                }
                if (pool.closed) return
                if (System.nanoTime() < deadline) {
                    onSpinWait()
                    continue
                }
                parked = true
                if (pool.current === seen && !pool.closed) LockSupport.park(pool)
                parked = false
                interrupted() // Pool threads are never interrupted for control; ignore strays.
            }
        }
    }

    companion object {
        /**
         * Idle spin before parking. Two milliseconds covers inter-wave gaps on the Xeon VM,
         * where waking parked workers costs hundreds of microseconds. Lower this for CPU economy.
         */
        private val WORKER_SPIN_NANOS = java.lang.Long.getLong("vanadium.workerSpinMicros", 2_000) * 1_000L

        /** Caller spin while waiting for the wave tail before parking. */
        private val CALLER_SPIN_NANOS = java.lang.Long.getLong("vanadium.callerSpinMicros", 200) * 1_000L

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

/** A pool worker. [context] gives tick code per-thread state without a thread-local lookup. */
abstract class TickThread(name: String) : Thread(name) {
    @JvmField
    var context: Any? = null
}

/** Unwinds a worker task that needs the server thread; see [WorkerPool.runDeferrable]. No stack trace is captured. */
class ServerThreadRequired(what: String) : RuntimeException("$what requires the server thread", null, false, false)
