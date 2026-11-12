package com.synsenetwork.vanadium.concurrent

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StripedLocksTest {
    @Test
    fun runsTheAction() {
        val locks = StripedLocks(64)
        var ran = 0
        locks.runLocked(123L) { ran++ }
        locks.runLocked(123L, 456L) { ran++ }
        assertEquals(2, ran)
    }

    @Test
    fun sameKeyPairDoesNotSelfDeadlock() {
        val locks = StripedLocks(64)
        var ran = 0
        locks.runLocked(42L, 42L) { ran++ } // identical keys → same stripe → must lock once
        assertEquals(1, ran)
    }

    @Test
    fun stripeCountMustBePowerOfTwo() {
        assertFailsWith<IllegalArgumentException> { StripedLocks(48) }
        assertFailsWith<IllegalArgumentException> { StripedLocks(0) }
    }

    @Test
    fun mutualExclusionOnSameKey() {
        val locks = StripedLocks(64)
        val threads = 8
        val perThread = 20_000
        var counter = 0 // plain var: only correct if runLocked really excludes
        val pool = Executors.newFixedThreadPool(threads)
        val start = CountDownLatch(1)
        val done = CountDownLatch(threads)
        repeat(threads) {
            pool.execute {
                start.await()
                repeat(perThread) { locks.runLocked(7L) { counter++ } }
                done.countDown()
            }
        }
        start.countDown()
        assertTrue(done.await(20, TimeUnit.SECONDS))
        pool.shutdownNow()
        assertEquals(threads * perThread, counter)
    }

    /** Two threads acquiring the same pair in opposite orders must not deadlock (ordered acquisition). */
    @Test
    fun crossOrderPairsDoNotDeadlock() {
        val locks = StripedLocks(64)
        val a = 0L
        val b = generateSequence(1L) { it + 1 }.first { locks.stripeIndex(it) != locks.stripeIndex(a) }
        val pool = Executors.newFixedThreadPool(2)
        val done = CountDownLatch(2)
        for ((first, second) in listOf(a to b, b to a)) {
            pool.execute {
                repeat(50_000) { locks.runLocked(first, second) {} }
                done.countDown()
            }
        }
        assertTrue(done.await(20, TimeUnit.SECONDS), "cross-order acquisition deadlocked")
        pool.shutdownNow()
    }

    @Test
    fun explicitPairLockHoldsBothStripesUntilUnlocked() {
        val locks = StripedLocks(64)
        val a = 0L
        val b = generateSequence(1L) { it + 1 }.first { locks.stripeIndex(it) != locks.stripeIndex(a) }
        locks.lock(a, b)
        assertTrue(locks.isHeldByCurrentThread(a) && locks.isHeldByCurrentThread(b))
        locks.unlock(a, b)
        assertFalse(locks.isHeldByCurrentThread(a) || locks.isHeldByCurrentThread(b))
        // Two keys on one stripe take it once, so one unlock releases it.
        val c = generateSequence(1L) { it + 1 }.first { locks.stripeIndex(it) == locks.stripeIndex(a) }
        locks.lock(a, c)
        locks.unlock(a, c)
        assertFalse(locks.isHeldByCurrentThread(a), "a shared stripe was taken twice")
    }

    /** A waiter that outlasts its spins parks, and still gets the stripe once the holder lets go. */
    @Test
    fun waiterGetsTheStripeAfterSpinningAndParking() {
        for (spins in listOf(0, 10, 100_000)) {
            val locks = StripedLocks(64, spins)
            val entered = CountDownLatch(1)
            val starting = CountDownLatch(1)
            locks.lock(5L, 6L)
            val waiter = Thread {
                starting.countDown()
                locks.runLocked(6L) { entered.countDown() }
            }.apply { start() }
            assertTrue(starting.await(10, TimeUnit.SECONDS))
            // Bounded negative check: the waiter must not get in while the pair is held.
            assertFalse(entered.await(50, TimeUnit.MILLISECONDS), "entered a held stripe (spins=$spins)")
            locks.unlock(5L, 6L)
            assertTrue(entered.await(10, TimeUnit.SECONDS), "waiter never got the stripe (spins=$spins)")
            waiter.join(10_000)
        }
    }

    /** Crossings between overlapping key pairs from many threads stay exclusive and finish. */
    @Test
    fun overlappingPairsExcludeEachOther() {
        val locks = StripedLocks(64, spins = 50)
        val threads = 8
        val perThread = 5_000
        var shared = 0 // Every pair below includes key 0: plain var, correct only under exclusion.
        val pool = Executors.newFixedThreadPool(threads)
        val start = CountDownLatch(1)
        val done = CountDownLatch(threads)
        repeat(threads) { t ->
            pool.execute {
                start.await()
                repeat(perThread) {
                    if ((t + it) % 2 == 0) locks.runLocked(0L, t + 1L) { shared++ } else locks.runLocked(t + 1L, 0L) { shared++ }
                }
                done.countDown()
            }
        }
        start.countDown()
        assertTrue(done.await(30, TimeUnit.SECONDS), "pair acquisition deadlocked")
        pool.shutdownNow()
        assertEquals(threads * perThread, shared)
    }
}
