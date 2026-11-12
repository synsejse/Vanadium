package com.synsenetwork.vanadium.concurrent

import it.unimi.dsi.fastutil.HashCommon
import java.util.concurrent.locks.ReentrantLock

/**
 * A fixed array of locks indexed by a hashed long key, so operations on unrelated keys proceed in
 * parallel while operations sharing a key (or a stripe) serialize. The two-key variants acquire both
 * stripes in index order, so callers in opposite order cannot deadlock.
 *
 * Holders run for microseconds, while waking a parked thread can take hundreds of them, so a waiter
 * spins [spins] times before it parks.
 *
 * @param stripes number of locks; must be a positive power of two
 */
class StripedLocks @JvmOverloads constructor(stripes: Int, private val spins: Int = DEFAULT_SPINS) {
    private val locks: Array<ReentrantLock>

    init {
        require(stripes > 0 && Integer.bitCount(stripes) == 1) { "stripes must be a positive power of two (got $stripes)" }
        require(spins >= 0) { "spins must not be negative (got $spins)" }
        locks = Array(stripes) { ReentrantLock() }
    }

    internal fun stripeIndex(key: Long): Int = HashCommon.mix(key).toInt() and (locks.size - 1)

    /** Whether the current thread holds [key]'s stripe; for assertions and tests. */
    fun isHeldByCurrentThread(key: Long): Boolean = locks[stripeIndex(key)].isHeldByCurrentThread

    fun runLocked(key: Long, action: Runnable) {
        val lock = locks[stripeIndex(key)]
        acquire(lock)
        try {
            action.run()
        } finally {
            lock.unlock()
        }
    }

    /** Runs the action holding both keys' stripes, acquired in index order (deadlock-free). */
    fun runLocked(keyA: Long, keyB: Long, action: Runnable) {
        lock(keyA, keyB)
        try {
            action.run()
        } finally {
            unlock(keyA, keyB)
        }
    }

    /** Takes both keys' stripes in index order (once when they share one); release with [unlock] and the same keys. */
    fun lock(keyA: Long, keyB: Long) {
        val a = stripeIndex(keyA)
        val b = stripeIndex(keyB)
        acquire(locks[minOf(a, b)])
        if (a != b) {
            try {
                acquire(locks[maxOf(a, b)])
            } catch (t: Throwable) {
                locks[minOf(a, b)].unlock()
                throw t
            }
        }
    }

    fun unlock(keyA: Long, keyB: Long) {
        val a = stripeIndex(keyA)
        val b = stripeIndex(keyB)
        if (a != b) locks[maxOf(a, b)].unlock()
        locks[minOf(a, b)].unlock()
    }

    private fun acquire(lock: ReentrantLock) {
        if (lock.tryLock()) return
        for (i in 0 until spins) {
            Thread.onSpinWait()
            if (!lock.isLocked && lock.tryLock()) return
        }
        lock.lock()
    }

    private companion object {
        /** About the length of one section crossing on the Xeon VM; then park. */
        const val DEFAULT_SPINS = 1_000
    }
}
