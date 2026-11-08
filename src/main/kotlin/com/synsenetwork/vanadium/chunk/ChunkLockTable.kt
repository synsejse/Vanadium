package com.synsenetwork.vanadium.chunk

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock

/**
 * Per-chunk reentrant load locks keyed by packed chunk position, so different chunks load in parallel.
 *
 * Locks are reference-counted, and the owning chunk manager evicts idle locks periodically, so the table
 * stays bounded however much of the world is explored. The reservation count is incremented atomically
 * with the lock's presence in the map, so the evictor can never drop a lock another thread is about to
 * acquire; [unlock] releases the lock before dropping its reservation, so a freed lock is never handed
 * out again while still held.
 */
class ChunkLockTable {
    private val locks = ConcurrentHashMap<Long, Held>()

    /** An acquired chunk lock; pass it back to [unlock]. */
    class Held internal constructor() {
        internal val lock = ReentrantLock()
        internal val refs = AtomicInteger()
    }

    /** Locks one chunk, reserving its table entry atomically before acquiring. */
    fun lock(chunkPos: Long): Held {
        val held = locks.compute(chunkPos) { _, existing -> (existing ?: Held()).also { it.refs.incrementAndGet() } }!!
        held.lock.lock()
        return held
    }

    fun unlock(held: Held) {
        // Unlock before dropping the reservation: the lock must stay un-evictable until it is actually
        // free, or a fresh acquirer could enter while it is still held.
        held.lock.unlock()
        held.refs.decrementAndGet()
    }

    /** Removes every lock without holders or waiters. Atomic per key against [lock]. */
    internal fun evictIdle() {
        for (key in locks.keys) locks.computeIfPresent(key) { _, held -> if (held.refs.get() == 0) null else held }
    }

    /** Number of locks currently retained. */
    internal val size: Int get() = locks.size
}
