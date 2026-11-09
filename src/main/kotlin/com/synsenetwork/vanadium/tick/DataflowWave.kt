package com.synsenetwork.vanadium.tick

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicIntegerArray
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Runs every planned region of a stage as one dataflow wave; used for cheap regions (see [TickScheduler]). Conflicting regions (within the isolation
 * distance, see [RegionGrid]) have different colours, and the lower colour always runs first: a region
 * becomes ready once all of its lower-coloured conflicts have finished. Two conflicting regions are
 * therefore never running together, and the order is acyclic, so the wave cannot deadlock. A region only
 * ever waits for its own conflicts, never for a stage-wide barrier.
 *
 * Ready regions are published into slots in the order they become ready, and every region becomes ready
 * exactly once, so a participant takes the next slot with one atomic increment and waits for that slot to
 * be filled. While a participant waits, the regions holding earlier slots are running or finished, and
 * their completions fill the slots in order, so every waited-on slot is eventually filled.
 *
 * Tasks within a region run in enqueue order; a failing region still releases its successors, and the
 * first failure is rethrown by the caller after the wave.
 *
 * @param work the runnable for each region (the region itself or a diagnostic/profiling wrapper), index-aligned
 * @param neighborStart conflicts of region i are [neighbors] at neighborStart[i] until neighborStart[i + 1]
 * @param colours a proper colouring of the conflict graph; lower colours run first
 */
internal class DataflowWave(
    private val work: List<Runnable>,
    private val neighborStart: IntArray,
    private val neighbors: IntArray,
    private val colours: IntArray,
) {
    private val count = work.size

    /** Unfinished lower-coloured conflicts per region, one per 64-byte line. */
    private val waiting = AtomicIntegerArray(count * STRIDE)

    /** Region index + 1 in the order regions became ready; 0 while a slot is unfilled. */
    private val slots = AtomicIntegerArray(count)
    private val published = AtomicInteger()
    private val taken = AtomicInteger()
    private val failure = AtomicReference<Throwable?>()
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
    fun participants(participants: Int): List<Runnable> = List(participants) { Runnable(::participate) }

    /** The first region failure, rethrown by the caller after the wave. */
    fun failure(): Throwable? = failure.get()

    /** Time participants spent running regions, excluding waiting; valid after the wave. */
    val busyNanos: Long get() = busy.get()

    private fun participate() {
        var spent = 0L
        while (true) {
            val slot = taken.getAndIncrement()
            if (slot >= count) break
            var entry = slots.get(slot)
            while (entry == 0) {
                Thread.onSpinWait()
                entry = slots.get(slot)
            }
            val region = entry - 1
            val start = System.nanoTime()
            try {
                work[region].run()
            } catch (t: Throwable) {
                failure.compareAndSet(null, t)
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
