package com.synsenetwork.vanadium.tick

import net.minecraft.world.level.ChunkPos

/**
 * Workers map recorded items into disjoint array slots; the caller enqueues them in recording order.
 * Server-thread only and non-reentrant outside mapping jobs.
 */
class BatchEnqueue<T : Any>(private val kind: Preparation, private val mapper: Mapper<T>) {
    interface Mapper<T> {
        /** A packed chunk position, [SERIAL] or [SKIP]. May run on a worker. */
        fun key(item: T): Long

        /** The cell task for an item with a chunk key. May run on a worker. */
        fun task(item: T): Runnable
    }

    private var items = arrayOfNulls<Any>(INITIAL_CAPACITY)
    private var keys = LongArray(INITIAL_CAPACITY)
    private var tasks = arrayOfNulls<Runnable>(INITIAL_CAPACITY)
    private var count = 0

    fun add(item: T) {
        if (count == items.size) {
            val capacity = items.size * 2
            items = items.copyOf(capacity)
            keys = keys.copyOf(capacity)
            tasks = tasks.copyOf(capacity)
        }
        items[count++] = item
    }

    /**
     * Maps every recorded item, runs [SERIAL] items through [serial] in recording order, and enqueues
     * the rest into [stage], or appends them to [flat] in recording order when one is given.
     * Clears the batch, including on failure.
     */
    @Suppress("UNCHECKED_CAST")
    fun dispatch(scheduler: TickScheduler, stage: Stage, flat: MutableList<Runnable>? = null, serial: (T) -> Unit) {
        val n = count
        try {
            if (n >= MIN_PARALLEL_ITEMS) {
                scheduler.prepare(kind, List((n + ITEMS_PER_JOB - 1) / ITEMS_PER_JOB) { job ->
                    Runnable { map(job * ITEMS_PER_JOB, minOf(n, (job + 1) * ITEMS_PER_JOB)) }
                })
            } else {
                map(0, n)
            }
            for (i in 0 until n) {
                when (val key = keys[i]) {
                    SKIP -> continue
                    SERIAL -> serial(items[i] as T)
                    else -> if (flat != null) flat.add(tasks[i]!!) else
                        scheduler.enqueue(stage, ChunkPos.getX(key), ChunkPos.getZ(key), tasks[i]!!, null)
                }
            }
        } finally {
            items.fill(null, 0, n)
            tasks.fill(null, 0, n)
            count = 0
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun map(start: Int, end: Int) {
        for (i in start until end) {
            val item = items[i] as T
            val key = mapper.key(item)
            keys[i] = key
            tasks[i] = if (key == SERIAL || key == SKIP) null else mapper.task(item)
        }
    }

    companion object {
        /** Key for items that must run on the server thread, in order, before the stage's wave. */
        const val SERIAL = Long.MIN_VALUE

        /** Key for items that need no work this stage. */
        const val SKIP = Long.MIN_VALUE + 1
        private const val INITIAL_CAPACITY = 256
        private const val MIN_PARALLEL_ITEMS = 512
        private const val ITEMS_PER_JOB = 256
    }
}
