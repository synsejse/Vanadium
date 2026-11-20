package com.synsenetwork.vanadium.chunk

import com.synsenetwork.vanadium.tick.WorkerPool
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock
import java.util.function.BooleanSupplier
import java.util.function.Supplier

/**
 * Per-chunk reentrant load locks keyed by packed chunk position, so different chunks load in parallel, and where a
 * chunk lookup that the chunk system has not answered yet runs: no thread may wait for the server thread while
 * holding a load lock, or the server thread could wait for that lock in turn. Free of Minecraft types so the thread
 * rules are unit-tested.
 *
 * Only the server thread can finish such a lookup (with C2ME, new holders, ticket updates and FULL promotion
 * happen there), and only the server thread takes load locks:
 * - On the server thread, the load runs under the chunk's reentrant lock. No other thread holds one, so taking it
 *   never waits, and a load started inside another (a load callback asking for a neighbour, or for the same chunk)
 *   takes its lock in place.
 * - On a wave helper, the load runs on the wave's caller, the server thread, through
 *   [WorkerPool.callOnWaveCaller]: the caller serves it between its own tasks or while it waits for the wave's
 *   tail. The helper holds no load lock while it waits.
 * - Any other thread runs the load itself without a lock; vanilla and C2ME make it wait for the server thread.
 *
 * Locks are reference-counted, and the owning chunk cache evicts idle locks periodically, so the table
 * stays bounded however much of the world is explored. The reservation count is incremented atomically
 * with the lock's presence in the map, so the evictor can never drop a lock another thread is about to
 * acquire; [load] releases the lock before dropping its reservation, so a freed lock is never handed
 * out again while still held.
 */
internal class ChunkLoadLocks(private val isServerThread: BooleanSupplier) {
    private val locks = ConcurrentHashMap<Long, Held>()

    private class Held {
        val lock = ReentrantLock()
        val refs = AtomicInteger()
    }

    /**
     * Runs [work], a lookup of packed chunk [pos], where it can complete, per the class comment. [stalled] runs once
     * on a helper whose request waits too long (see [WorkerPool.callOnWaveCaller]).
     */
    fun <T> load(pos: Long, work: Supplier<T>, stalled: Runnable? = null): T {
        if (isServerThread.asBoolean) {
            // Reserve the entry before acquiring, atomically against eviction.
            val held = checkNotNull(locks.compute(pos) { _, existing ->
                val entry = existing ?: Held()
                entry.refs.incrementAndGet()
                entry
            }) { "compute keeps the entry it returns" }
            held.lock.lock()
            try {
                return work.get()
            } finally {
                // Unlock before dropping the reservation: the lock must stay un-evictable until it is actually
                // free, or a fresh acquirer could enter while it is still held.
                held.lock.unlock()
                held.refs.decrementAndGet()
            }
        }
        if (WorkerPool.isWorkerThread()) {
            // The caller reruns this routing, so the work runs under its load lock if the caller is the server thread.
            return WorkerPool.callOnWaveCaller({ load(pos, work) }, stalled)
        }
        return work.get()
    }

    /** Removes every lock without holders or waiters. Atomic per key against [load]'s reservation. */
    fun evictIdle() {
        for (key in locks.keys) locks.computeIfPresent(key) { _, held -> if (held.refs.get() == 0) null else held }
    }

    val size: Int get() = locks.size
}
