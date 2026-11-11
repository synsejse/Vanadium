package com.synsenetwork.vanadium.concurrent

import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OwnerLocksTest {
    private val long = TimeUnit.SECONDS.toNanos(30)

    @Test
    fun ownersWritingToEachOtherNeverOverlapTheOwnerAndNeverDeadlock() {
        val locks = OwnerLocks(long)
        val a = OwnerLocks.Lock()
        val b = OwnerLocks.Lock()
        // True only while that object's owner holds its lock outside a foreign scope.
        val owning = mapOf(a to AtomicBoolean(), b to AtomicBoolean())
        val violations = AtomicInteger()
        // Both owners are inside their tasks before either writes to the other.
        val bothRunning = CyclicBarrier(2)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val jobs = listOf(a to b, b to a).map { (own, other) ->
                pool.submit {
                    val context = OwnerLocks.Context()
                    locks.beginOwn(context, own)
                    owning.getValue(own).set(true)
                    try {
                        bothRunning.await(10, TimeUnit.SECONDS)
                        repeat(2_000) {
                            owning.getValue(own).set(false)
                            locks.enterForeign(context, other)
                            try {
                                if (owning.getValue(other).get() || !other.isHeldByCurrentThread || own.isHeldByCurrentThread) {
                                    violations.incrementAndGet()
                                }
                            } finally {
                                locks.exitForeign(context)
                            }
                            if (!own.isHeldByCurrentThread) violations.incrementAndGet()
                            owning.getValue(own).set(true)
                        }
                        owning.getValue(own).set(false)
                    } finally {
                        locks.endOwn(context)
                    }
                }
            }
            jobs.forEach { it.get(20, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
        }
        assertEquals(0, violations.get(), "a foreign write overlapped its target's owner")
        assertEquals(0L, locks.timeouts.get() + locks.cycles.get())
        assertFalse(a.isLocked || b.isLocked)
    }

    @Test
    fun foreignWriteWaitsForTheOwnersTask() {
        val locks = OwnerLocks(long)
        val target = OwnerLocks.Lock()
        val ownerRunning = CountDownLatch(1)
        val releaseOwner = CountDownLatch(1)
        val writerEntering = CountDownLatch(1)
        val order = java.util.Collections.synchronizedList(ArrayList<String>())
        val pool = Executors.newFixedThreadPool(2)
        try {
            val owner = pool.submit {
                val context = OwnerLocks.Context()
                locks.beginOwn(context, target)
                ownerRunning.countDown()
                check(releaseOwner.await(10, TimeUnit.SECONDS))
                order.add("owner done")
                locks.endOwn(context)
            }
            assertTrue(ownerRunning.await(10, TimeUnit.SECONDS))
            val writer = pool.submit {
                val context = OwnerLocks.Context()
                locks.beginOwn(context, OwnerLocks.Lock())
                writerEntering.countDown()
                locks.enterForeign(context, target)
                order.add("write")
                locks.exitForeign(context)
                locks.endOwn(context)
            }
            assertTrue(writerEntering.await(10, TimeUnit.SECONDS))
            // The writer can only be queued behind the owner: wait until it is, then let the owner finish.
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            while (!target.hasQueuedThreads()) {
                check(System.nanoTime() < deadline) { "writer never waited for the owner" }
                Thread.onSpinWait()
            }
            assertFalse(writer.isDone)
            releaseOwner.countDown()
            owner.get(10, TimeUnit.SECONDS)
            writer.get(10, TimeUnit.SECONDS)
        } finally {
            releaseOwner.countDown()
            pool.shutdownNow()
        }
        assertEquals(listOf("owner done", "write"), order)
        assertEquals(0L, locks.timeouts.get() + locks.cycles.get())
    }

    @Test
    fun scopesNestAndRestoreInOrder() {
        val locks = OwnerLocks(long)
        val own = OwnerLocks.Lock()
        val first = OwnerLocks.Lock()
        val second = OwnerLocks.Lock()
        val context = OwnerLocks.Context()
        locks.beginOwn(context, own)
        assertTrue(context.ownLock === own && own.isHeldByCurrentThread)
        locks.enterForeign(context, first)
        assertFalse(own.isHeldByCurrentThread, "the owner stayed closed inside a foreign scope")
        locks.enterForeign(context, second)
        locks.enterForeign(context, first) // Reentrant target.
        locks.enterForeign(context, null) // Shared state.
        locks.enterForeign(context, own) // A write back to the owner's object takes its lock again.
        assertEquals(5, context.depth)
        assertTrue(first.isHeldByCurrentThread && second.isHeldByCurrentThread && own.isHeldByCurrentThread)
        locks.exitForeign(context)
        assertFalse(own.isHeldByCurrentThread)
        repeat(3) { locks.exitForeign(context) }
        assertTrue(first.isHeldByCurrentThread && !second.isLocked && !own.isLocked)
        locks.exitForeign(context)
        assertTrue(own.isHeldByCurrentThread && !first.isLocked)
        locks.endOwn(context)
        assertFalse(own.isLocked)
        // Outside an owner task a scope still excludes the target's owner.
        locks.enterForeign(context, first)
        assertTrue(first.isHeldByCurrentThread)
        locks.exitForeign(context)
        assertFalse(first.isLocked)
    }

    @Test
    fun nestedWritersWaitingForEachOtherAreReleasedByTheCycleCheck() {
        val locks = OwnerLocks(long) // A timeout would fail this test's own time limit.
        val x = OwnerLocks.Lock()
        val y = OwnerLocks.Lock()
        val bothHolding = CyclicBarrier(2)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val jobs = listOf(x to y, y to x).map { (first, second) ->
                pool.submit {
                    val context = OwnerLocks.Context()
                    locks.beginOwn(context, OwnerLocks.Lock())
                    locks.enterForeign(context, first)
                    bothHolding.await(10, TimeUnit.SECONDS)
                    locks.enterForeign(context, second) // Each holds one and wants the other.
                    locks.exitForeign(context)
                    locks.exitForeign(context)
                    locks.endOwn(context)
                }
            }
            jobs.forEach { it.get(15, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
        }
        assertTrue(locks.cycles.get() in 1L..2L, "cycles=${locks.cycles.get()}")
        assertEquals(0L, locks.timeouts.get())
        assertFalse(x.isLocked || y.isLocked)
    }

    @Test
    fun aLockStuckOutsideTheProtocolTimesOutInsteadOfStallingTheTask() {
        val locks = OwnerLocks(TimeUnit.MILLISECONDS.toNanos(20))
        val stuck = OwnerLocks.Lock()
        val held = CountDownLatch(1)
        val release = CountDownLatch(1)
        val holder = Thread {
            stuck.lock()
            held.countDown()
            release.await(30, TimeUnit.SECONDS)
            stuck.unlock()
        }.apply { start() }
        try {
            assertTrue(held.await(10, TimeUnit.SECONDS))
            val own = OwnerLocks.Lock()
            val context = OwnerLocks.Context()
            locks.beginOwn(context, own)
            locks.enterForeign(context, stuck)
            assertEquals(1L, locks.timeouts.get())
            assertEquals(0L, locks.cycles.get())
            assertFalse(stuck.isHeldByCurrentThread)
            locks.exitForeign(context) // Must not unlock a lock it never got.
            assertTrue(own.isHeldByCurrentThread && stuck.isLocked)
            locks.endOwn(context)
        } finally {
            release.countDown()
            holder.join(10_000)
        }
    }
}
