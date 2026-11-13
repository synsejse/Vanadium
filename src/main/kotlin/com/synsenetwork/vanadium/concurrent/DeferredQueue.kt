package com.synsenetwork.vanadium.concurrent

import java.util.concurrent.CopyOnWriteArrayList
import java.util.function.Consumer

/**
 * Items that wave tasks set aside for after the wave, kept in one list per thread so adding takes no shared
 * write. Between waves one thread [drain]s them all: inline when there are few, otherwise as a wave of slices.
 *
 * Each [Local] is appended to only by its own thread, during a wave; [drain] runs on the wave's caller after the
 * wave's barrier. The drain swaps every list for an empty spare before it applies anything, so items added while
 * a parallel round runs (by the round's own tasks) land in fresh lists and are applied in the next round.
 */
class DeferredQueue<T : Any>(
    /** Items one parallel task applies. */
    private val sliceSize: Int = 32,
    /** Fewer items than this are applied inline: a wave costs more than it saves. */
    private val parallelFrom: Int = 128,
) {
    init {
        require(sliceSize > 0 && parallelFrom > 0)
    }

    /** One thread's list. [owner] null: never pruned (a caller context that may run on any thread). */
    class Local<T : Any> internal constructor(internal val owner: Thread?) {
        internal var items = ArrayList<T>()
        internal var spare = ArrayList<T>()

        /** Called only by the owning thread during a wave. */
        fun add(item: T) {
            items.add(item)
        }
    }

    private val locals = CopyOnWriteArrayList<Local<T>>()
    private val draining = ArrayList<ArrayList<T>>()
    private val slices = ArrayList<Slice<T>>()
    private val wave = ArrayList<Runnable>()

    /** A list for [owner]'s items. Rare: once per thread. */
    fun register(owner: Thread?): Local<T> = Local<T>(owner).also(locals::add)

    /** Registered lists, including those of finished threads not yet pruned; for tests. */
    internal val registered: Int get() = locals.size

    /** Items added since the last drain; for diagnostics and tests, between waves only. */
    fun pending(): Int = locals.sumOf { it.items.size }

    /**
     * Between waves, on the thread that ran the wave: applies every item added so far, and those added while
     * applying. Small rounds, and every round when [runWave] is null, apply inline with [inline]; larger ones
     * run [runWave] over slices that apply each item with [parallel]. After [MAX_PARALLEL_ROUNDS] parallel
     * rounds the rest apply inline. Returns the number of parallel rounds.
     */
    fun drain(inline: Consumer<T>, parallel: Consumer<T>, runWave: Consumer<List<Runnable>>?): Int {
        var rounds = 0
        while (true) {
            val total = takeAll()
            if (total == 0) return rounds
            try {
                if (runWave == null || total < parallelFrom || rounds >= MAX_PARALLEL_ROUNDS) {
                    for (list in draining) list.forEach(inline)
                } else {
                    rounds++
                    runWave.accept(slice(parallel))
                }
            } finally {
                for (list in draining) list.clear()
                draining.clear()
                wave.clear()
            }
        }
    }

    /** Moves every thread's items to [draining], leaving each an empty list. */
    private fun takeAll(): Int {
        var total = 0
        for (local in locals) {
            val items = local.items
            if (items.isEmpty()) {
                val owner = local.owner
                if (owner != null && !owner.isAlive) locals.remove(local)
                continue
            }
            local.items = local.spare
            local.spare = items
            draining.add(items)
            total += items.size
        }
        return total
    }

    private fun slice(apply: Consumer<T>): List<Runnable> {
        var used = 0
        for (list in draining) {
            var from = 0
            while (from < list.size) {
                val to = minOf(list.size, from + sliceSize)
                if (used == slices.size) slices.add(Slice())
                val slice = slices[used++]
                slice.items = list
                slice.from = from
                slice.to = to
                slice.apply = apply
                wave.add(slice)
                from = to
            }
        }
        return wave
    }

    private class Slice<T : Any> : Runnable {
        var items: List<T> = emptyList()
        var from = 0
        var to = 0
        var apply: Consumer<T>? = null

        override fun run() {
            val apply = apply!!
            for (i in from until to) apply.accept(items[i])
        }
    }

    private companion object {
        /** Parallel rounds only repeat when applying an item defers another; that is never expected to recur. */
        const val MAX_PARALLEL_ROUNDS = 4
    }
}
