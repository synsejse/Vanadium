package com.synsenetwork.vanadium.tick

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicIntegerArray
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Runs cheap regions after their lower-coloured conflicts finish. Proper colouring makes the order
 * acyclic, so conflicting work never overlaps and every dependency chain can progress.
 *
 * Ready indices are published once into monotonic slots. Participants reserve a slot and wait on
 * that slot alone, avoiding shared queue polling. Failed regions still release their successors.
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
