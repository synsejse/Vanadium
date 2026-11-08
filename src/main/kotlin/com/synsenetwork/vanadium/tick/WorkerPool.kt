package com.synsenetwork.vanadium.tick

import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicIntegerArray
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.LockSupport
import java.util.function.LongConsumer

/**
 * A fixed set of persistent worker threads that runs a batch of tasks and waits for all of them.
 *
 * In [runWave] the calling thread participates as a worker and claims tasks alongside the pool,
 * waiting only for the tail (tasks still in flight once every index has been claimed).
 *
 * Dispatch publishes the wave through one volatile field. Idle workers spin for a short, configurable
 * window before parking, so back-to-back waves of a tick reach running threads without futex wake-ups.
 * Parked workers are unparked individually only when a wave needs them; low-numbered workers take
 * small waves. Tasks are claimed in chunks once they greatly outnumber participants, and each
 * participant reports its completed count once, so per-task work does not bounce shared counters.
 *
 * Invariant: tick-path locking is unconditional, never gated on [isWorkerThread], so a cell runs the
 * same whether a worker or the calling thread executes it.
 */
class WorkerPool(threads: Int) : AutoCloseable {
    private val workers = Array(maxOf(1, threads)) { WorkerThread(this, it) }

    @Volatile
    private var current: Wave? = null

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
        val wave = Wave(indexed, helpers + 1, Thread.currentThread())
        if (helpers > 0) {
            current = wave
            for (i in 0 until helpers) workers[i].wake()
        }
        wave.participate()
        val waitStart = if (callerWait != null) System.nanoTime() else 0
        try {
            wave.awaitCompletion()
        } finally {
            callerWait?.accept(System.nanoTime() - waitStart)
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

    private class Wave(private val tasks: List<Runnable>, participants: Int, private val waiter: Thread) {
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

    private class WorkerThread(private val pool: WorkerPool, private val index: Int) : Thread("Vanadium-Worker-${index + 1}") {
        @Volatile
        private var parked = false

        init {
            isDaemon = true
        }

        /** Dekker pairing with [run]: the wave is published before [parked] is read. */
        fun wake() {
            if (parked) LockSupport.unpark(this)
        }

        override fun run() {
            var seen: Wave? = null
            var deadline = System.nanoTime() + WORKER_SPIN_NANOS
            while (true) {
                val wave = pool.current
                if (wave !== seen) {
                    seen = wave
                    if (wave != null && index < wave.helpers) {
                        wave.participate()
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
        /** Idle spin before a worker parks; covers the gaps between a tick's waves. */
        private val WORKER_SPIN_NANOS = java.lang.Long.getLong("vanadium.workerSpinMicros", 100) * 1_000L

        /** Caller spin while waiting for the wave tail before parking. */
        private val CALLER_SPIN_NANOS = java.lang.Long.getLong("vanadium.callerSpinMicros", 200) * 1_000L

        /** True when called from a thread owned by a WorkerPool. */
        @JvmStatic
        fun isWorkerThread(): Boolean = Thread.currentThread() is WorkerThread
    }
}
