package com.synsenetwork.vanadium.concurrent

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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
}
