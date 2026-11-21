package com.synsenetwork.vanadium.tick

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicIntegerArray
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicReferenceArray
import java.util.concurrent.locks.LockSupport

/** One stage's execution policy; the caller joins every participant before inspecting the result. */
internal sealed interface RegionWave {
    val busyNanos: Long
    fun participants(participants: Int): List<Runnable>
    val failure: Throwable?
}

/**
 * Runs every planned region of a stage by dynamic claiming; used for heavy regions (see [TickScheduler]).
 * A region may start whenever none of its conflicts (within the isolation distance, see [RegionGrid]) is
 * running, so heavy regions pack as tightly as the conflicts allow instead of following a fixed order.
 *
 * Claim protocol (all state accesses are volatile and therefore sequentially consistent): a participant
 * moves a region from PENDING to TRYING, then inspects its conflicts. A RUNNING one makes it back off. For a
 * TRYING one the higher-index region backs off and the lower-index region waits for it to resolve. Two
 * conflicting claimants each write their own state before reading the other's, so at least one observes the
 * other: both can never pass. Waits only point to higher indices, so they cannot form a cycle.
 *
 * Claim attempts first read the conflicts and write nothing when one is busy, so idle participants do not
 * bounce the state lines that running regions and other scanners read. Once every remaining region is
 * already RUNNING ([NO_PENDING]), idle participants return at once rather than spinning through the tail.
 * When pending regions remain but all are conflict-blocked, idle participants spin briefly and then park on
 * per-participant waiter slots; each finishing region unparks only as many parked waiters as neighbors it
 * potentially freed (or all waiters once [remaining] hits 0), avoiding thundering-herd rescans across 30+
 * threads.
 *
 * Regions are tried in plan order (heaviest first); tasks within a region run in enqueue order. Conflicting
 * regions run in an order that varies between ticks. A failing region still completes, and the first failure
 * is rethrown by the caller after the wave.
 *
 * @param work the runnable for each region (the region itself or a diagnostic/profiling wrapper), index-aligned
 * @param neighborStart conflicts of region i are [neighbors] at neighborStart[i] until neighborStart[i + 1]
 */
internal class ClaimWave(
    private val work: List<Runnable>,
    private val neighborStart: IntArray,
    private val neighbors: IntArray,
) : RegionWave {
    private val count = work.size
    private val states = AtomicIntegerArray(count * STRIDE)
    private val remaining = AtomicInteger(count)
    private val firstPending = AtomicInteger()
    private val firstFailure = AtomicReference<Throwable?>()
    private val busy = AtomicLong()

    /** One loop per participant; each returns once every region has finished or no pending regions remain. */
    override fun participants(participants: Int): List<Runnable> {
        val waiters = AtomicReferenceArray<Thread?>(participants)
        return List(participants) { slot -> Runnable { participate(slot, waiters) } }
    }

    /** The first region failure, rethrown by the caller after the wave. */
    override val failure: Throwable? get() = firstFailure.get()

    /** Time participants spent running regions, excluding waiting; valid after the wave. */
    override val busyNanos: Long get() = busy.get()

    private fun participate(slot: Int, waiters: AtomicReferenceArray<Thread?>) {
        val caller = WorkerPool.waveCaller()
        var spent = 0L
        var interrupted = false
        while (true) {
            if (caller != null && caller.hasRequests) caller.serveRequests()
            val seen = remaining.get()
            if (seen == 0) break
            val ran = claimAndRunOne(waiters)
            if (ran >= 0L) {
                spent += ran
                continue
            }
            if (ran == NO_PENDING) break
            // All remaining pending regions are blocked by running conflicts: spin briefly, then park.
            var spins = 0
            var parkAt = 0L
            while (remaining.get() == seen) {
                if (caller != null && caller.hasRequests) {
                    caller.serveRequests()
                    spins = 0
                    parkAt = 0L
                    continue
                }
                if (spins < SPIN_BATCH) {
                    spins++
                    Thread.onSpinWait()
                    continue
                }
                val now = System.nanoTime()
                if (parkAt == 0L) {
                    parkAt = now + PARK_AFTER_NANOS
                    spins = 0
                    continue
                }
                if (now < parkAt) {
                    spins = 0
                    continue
                }
                waiters.set(slot, Thread.currentThread())
                if (remaining.get() == seen && (caller == null || !caller.hasRequests)) {
                    LockSupport.park(this)
                    if (Thread.interrupted()) interrupted = true
                }
                waiters.set(slot, null)
            }
        }
        if (interrupted) Thread.currentThread().interrupt()
        busy.addAndGet(spent)
    }

    /**
     * Runs one claimable region and returns its run time, [BLOCKED] if pending/trying regions still exist but
     * none could be claimed, or [NO_PENDING] if every unfinished region is already RUNNING.
     */
    private fun claimAndRunOne(waiters: AtomicReferenceArray<Thread?>): Long {
        var start = firstPending.get()
        var sawPending = false
        for (i in start until count) {
            val state = states.get(i * STRIDE)
            if (state == DONE) {
                if (i == start) {
                    // Advance the shared low-water mark over the finished prefix.
                    firstPending.compareAndSet(i, i + 1)
                    start = i + 1
                }
                continue
            }
            if (state == RUNNING) continue
            sawPending = true
            if (state != PENDING || blocked(i) || !tryClaim(i)) continue
            val begin = System.nanoTime()
            try {
                work[i].run()
            } catch (t: Throwable) {
                firstFailure.compareAndSet(null, t)
            } finally {
                states.set(i * STRIDE, DONE)
                val left = remaining.decrementAndGet()
                wakeWaiters(i, left, waiters)
            }
            return System.nanoTime() - begin
        }
        return if (sawPending) BLOCKED else NO_PENDING
    }

    /**
     * Wakes all parked waiters when the wave finishes, or at most as many parked waiters as neighbors of [region]
     * that are now potentially runnable (including TRYING neighbors in case one backs off concurrently).
     */
    private fun wakeWaiters(region: Int, left: Int, waiters: AtomicReferenceArray<Thread?>) {
        val slots = waiters.length()
        if (left == 0) {
            for (s in 0 until slots) {
                val t = waiters.getAndSet(s, null)
                if (t != null) LockSupport.unpark(t)
            }
            return
        }
        var budget = 0
        for (k in neighborStart[region] until neighborStart[region + 1]) {
            val nb = neighbors[k]
            val st = states.get(nb * STRIDE)
            if (st == TRYING || (st == PENDING && !blocked(nb))) {
                budget++
                if (budget >= slots) break
            }
        }
        if (budget == 0) return
        var woken = 0
        for (s in 0 until slots) {
            val t = waiters.getAndSet(s, null)
            if (t != null) {
                LockSupport.unpark(t)
                if (++woken >= budget) return
            }
        }
    }

    /** Read-only precheck: a claim attempt that would back off writes nothing. */
    private fun blocked(region: Int): Boolean {
        for (k in neighborStart[region] until neighborStart[region + 1]) {
            val state = states.get(neighbors[k] * STRIDE)
            if (state == RUNNING || state == TRYING) return true
        }
        return false
    }

    private fun tryClaim(region: Int): Boolean {
        if (!states.compareAndSet(region * STRIDE, PENDING, TRYING)) return false
        for (k in neighborStart[region] until neighborStart[region + 1]) {
            val neighbor = neighbors[k]
            while (true) {
                val state = states.get(neighbor * STRIDE)
                if (state == RUNNING || state == TRYING && neighbor < region) {
                    states.set(region * STRIDE, PENDING)
                    return false
                }
                if (state != TRYING) break
                Thread.onSpinWait() // A higher-index claimant resolves without waiting on us.
            }
        }
        states.set(region * STRIDE, RUNNING)
        return true
    }

    private companion object {
        const val PENDING = 0
        const val TRYING = 1
        const val RUNNING = 2
        const val DONE = 3
        const val BLOCKED = -1L
        const val NO_PENDING = -2L
        const val SPIN_BATCH = 64
        const val PARK_AFTER_NANOS = 30_000L

        /** One state per 64-byte line: claims on one region do not invalidate a neighbor's line. */
        const val STRIDE = 16
    }
}

/**
 * Runs cheap regions after their lower-coloured conflicts finish. Proper colouring makes the order
 * acyclic, so conflicting work never overlaps and every dependency chain can progress.
 *
 * Ready indices are published once into monotonic slots. Participants reserve a slot and wait on
 * that slot alone, avoiding shared queue polling. Cheap regions complete in tens of microseconds, so
 * spinning on the slot avoids kernel park/unpark latency across colour layers. Failed regions still
 * release their successors.
 */
internal class DataflowWave(
    private val work: List<Runnable>,
    private val neighborStart: IntArray,
    private val neighbors: IntArray,
    private val colours: IntArray,
) : RegionWave {
    private val count = work.size

    /** Unfinished lower-coloured conflicts per region, one per 64-byte line. */
    private val waiting = AtomicIntegerArray(count * STRIDE)

    /** Region index + 1 in the order regions became ready; 0 while a slot is unfilled. */
    private val slots = AtomicIntegerArray(count)
    private val published = AtomicInteger()
    private val taken = AtomicInteger()
    private val firstFailure = AtomicReference<Throwable?>()
    private val busy = AtomicLong()

    init {
        for (i in 0 until count) {
            var lower = 0
            for (k in neighborStart[i] until neighborStart[i + 1]) if (colours[neighbors[k]] < colours[i]) lower++
            waiting.set(i * STRIDE, lower)
        }
        // Regions are sorted largest first, so the initially ready ones start in that order.
        for (i in 0 until count) if (waiting.get(i * STRIDE) == 0) publish(i)
    }

    /** One loop per participant; each returns once every region has been taken and run. */
    override fun participants(participants: Int): List<Runnable> = List(participants) { Runnable(::participate) }

    /** The first region failure, rethrown by the caller after the wave. */
    override val failure: Throwable? get() = firstFailure.get()

    /** Time participants spent running regions, excluding waiting; valid after the wave. */
    override val busyNanos: Long get() = busy.get()

    private fun participate() {
        val caller = WorkerPool.waveCaller()
        var spent = 0L
        while (true) {
            if (caller != null && caller.hasRequests) caller.serveRequests()
            val slot = taken.getAndIncrement()
            if (slot >= count) break
            var entry = slots.get(slot)
            while (entry == 0) {
                // The predecessor holding this slot back may be waiting for the caller.
                if (caller != null && caller.hasRequests) caller.serveRequests()
                Thread.onSpinWait()
                entry = slots.get(slot)
            }
            val region = entry - 1
            val start = System.nanoTime()
            try {
                work[region].run()
            } catch (t: Throwable) {
                firstFailure.compareAndSet(null, t)
            } finally {
                spent += System.nanoTime() - start
                release(region)
            }
        }
        busy.addAndGet(spent)
    }

    /** Lets higher-coloured conflicts of a finished region proceed. */
    private fun release(region: Int) {
        val colour = colours[region]
        for (k in neighborStart[region] until neighborStart[region + 1]) {
            val next = neighbors[k]
            if (colours[next] > colour && waiting.decrementAndGet(next * STRIDE) == 0) publish(next)
        }
    }

    private fun publish(region: Int) {
        slots.set(published.getAndIncrement(), region + 1)
    }

    private companion object {
        /** One counter per 64-byte line: releases of one region do not invalidate a neighbor's line. */
        const val STRIDE = 16
    }
}
