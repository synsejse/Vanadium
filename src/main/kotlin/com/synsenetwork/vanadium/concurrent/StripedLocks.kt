package com.synsenetwork.vanadium.concurrent

import it.unimi.dsi.fastutil.HashCommon
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * A fixed array of locks indexed by a hashed long key, so operations on unrelated keys proceed in
 * parallel while operations sharing a key (or a stripe) serialize. The two-key variant acquires both
 * stripes in index order, so callers in opposite order cannot deadlock.
 *
 * @param stripes number of locks; must be a positive power of two
 */
class StripedLocks(stripes: Int) {
    private val locks: Array<ReentrantLock>

    init {
        require(stripes > 0 && Integer.bitCount(stripes) == 1) { "stripes must be a positive power of two (got $stripes)" }
        locks = Array(stripes) { ReentrantLock() }
    }

    internal fun stripeIndex(key: Long): Int = HashCommon.mix(key).toInt() and (locks.size - 1)

    fun runLocked(key: Long, action: Runnable) = locks[stripeIndex(key)].withLock { action.run() }

    /** Runs the action holding both keys' stripes, acquired in index order (deadlock-free). */
    fun runLocked(keyA: Long, keyB: Long, action: Runnable) {
        val a = stripeIndex(keyA)
        val b = stripeIndex(keyB)
        if (a == b) return runLocked(keyA, action)
        locks[minOf(a, b)].withLock { locks[maxOf(a, b)].withLock { action.run() } }
    }
}
