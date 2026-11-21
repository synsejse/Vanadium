package com.synsenetwork.vanadium.concurrent

import com.synsenetwork.vanadium.tick.TickThread
import it.unimi.dsi.fastutil.objects.Reference2DoubleOpenHashMap
import java.util.Arrays
import java.util.function.ToDoubleFunction
import net.minecraft.world.entity.Entity

/**
 * Sorting entities by distance while they move on other threads: a comparator that reads live positions can
 * contradict itself mid-sort, and the sort then throws ("Comparison method violates its general contract").
 *
 * [sortByDistance] snapshots each entity's squared distance once into reusable per-thread arrays and sorts
 * stably without hash-map probes or per-sort allocations; [comparator] remains for `Stream.sorted` callers
 * (`PlayerSensor`).
 */
object StableDistance {
    private const val INSERTION_CUTOFF = 24
    private const val INITIAL_CAPACITY = 64

    /** Zero-allocation placeholder for redirected `Comparator.comparingDouble` calls paired with [sortByDistance]. */
    @JvmField
    val UNUSED: Comparator<Any?> = Comparator { _, _ -> 0 }

    private val LOCAL = ThreadLocal.withInitial(::Scratch)

    /** Orders by squared distance to [body], reading each entity's position once per sort. */
    @JvmStatic
    fun <T : Entity> comparator(body: Entity): Comparator<T> {
        val distances = Reference2DoubleOpenHashMap<Entity>()
        val measure = ToDoubleFunction<Entity> { body.distanceToSqr(it.position()) }
        return Comparator { a, b -> distances.computeIfAbsent(a, measure).compareTo(distances.computeIfAbsent(b, measure)) }
    }

    /**
     * Sorts [list] stably by squared distance to [body], snapshotting [body] and each entity's position once.
     * Matches `list.sort(Comparator.comparingDouble(body::distanceToSqr))` including tie order and `Double.compare`
     * semantics for `-0.0` and `NaN`, without map lookups or per-sort allocation.
     */
    @JvmStatic
    fun <T : Entity> sortByDistance(list: MutableList<T>, body: Entity) {
        val n = list.size
        if (n <= 1) return
        val scratch = takeScratch()
        var used = 0
        try {
            scratch.ensureCapacity(n)
            val keys = scratch.keys
            val items = scratch.items
            val origin = body.position()
            val bx = origin.x
            val by = origin.y
            val bz = origin.z
            if (list is RandomAccess) {
                for (i in 0 until n) {
                    val entity = list[i]
                    val pos = entity.position()
                    val dx = bx - pos.x
                    val dy = by - pos.y
                    val dz = bz - pos.z
                    keys[i] = dx * dx + dy * dy + dz * dz
                    items[i] = entity
                }
            } else {
                var i = 0
                for (entity in list) {
                    val pos = entity.position()
                    val dx = bx - pos.x
                    val dy = by - pos.y
                    val dz = bz - pos.z
                    keys[i] = dx * dx + dy * dy + dz * dz
                    items[i] = entity
                    i++
                }
            }
            used = n
            sortScratch(scratch, n)
            writeBack(list, items, n)
        } finally {
            scratch.release(used)
        }
    }

    /**
     * Minecraft-free test seam for the same precomputed stable sort used by [sortByDistance].
     */
    internal fun <T> sortBy(list: MutableList<T>, key: ToDoubleFunction<in T>) {
        val n = list.size
        if (n <= 1) return
        val scratch = takeScratch()
        var used = 0
        try {
            scratch.ensureCapacity(n)
            val keys = scratch.keys
            val items = scratch.items
            if (list is RandomAccess) {
                for (i in 0 until n) {
                    val item = list[i]
                    keys[i] = key.applyAsDouble(item)
                    items[i] = item
                }
            } else {
                var i = 0
                for (item in list) {
                    keys[i] = key.applyAsDouble(item)
                    items[i] = item
                    i++
                }
            }
            used = n
            sortScratch(scratch, n)
            writeBack(list, items, n)
        } finally {
            scratch.release(used)
        }
    }

    private fun sortScratch(scratch: Scratch, n: Int) {
        if (n <= INSERTION_CUTOFF) {
            insertionSort(scratch.keys, scratch.items, 0, n)
            return
        }
        mergeSort(scratch.keys, scratch.items, scratch.tmpKeys, scratch.tmpItems, 0, n)
    }

    private fun insertionSort(keys: DoubleArray, items: Array<Any?>, from: Int, to: Int) {
        for (i in from + 1 until to) {
            val key = keys[i]
            val item = items[i]
            var j = i - 1
            while (j >= from && java.lang.Double.compare(keys[j], key) > 0) {
                keys[j + 1] = keys[j]
                items[j + 1] = items[j]
                j--
            }
            keys[j + 1] = key
            items[j + 1] = item
        }
    }

    private fun mergeSort(
        keys: DoubleArray,
        items: Array<Any?>,
        tmpKeys: DoubleArray,
        tmpItems: Array<Any?>,
        from: Int,
        to: Int,
    ) {
        if (to - from <= INSERTION_CUTOFF) {
            insertionSort(keys, items, from, to)
            return
        }
        val mid = (from + to) ushr 1
        mergeSort(keys, items, tmpKeys, tmpItems, from, mid)
        mergeSort(keys, items, tmpKeys, tmpItems, mid, to)
        if (java.lang.Double.compare(keys[mid - 1], keys[mid]) <= 0) return
        var i = from
        var j = mid
        var k = from
        while (i < mid && j < to) {
            if (java.lang.Double.compare(keys[i], keys[j]) <= 0) {
                tmpKeys[k] = keys[i]
                tmpItems[k] = items[i]
                i++
            } else {
                tmpKeys[k] = keys[j]
                tmpItems[k] = items[j]
                j++
            }
            k++
        }
        if (i < mid) {
            val rem = mid - i
            System.arraycopy(keys, i, tmpKeys, k, rem)
            System.arraycopy(items, i, tmpItems, k, rem)
            k += rem
        }
        if (j < to) {
            val rem = to - j
            System.arraycopy(keys, j, tmpKeys, k, rem)
            System.arraycopy(items, j, tmpItems, k, rem)
        }
        val len = to - from
        System.arraycopy(tmpKeys, from, keys, from, len)
        System.arraycopy(tmpItems, from, items, from, len)
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> writeBack(list: MutableList<T>, items: Array<Any?>, n: Int) {
        if (list is RandomAccess) {
            for (i in 0 until n) list[i] = items[i] as T
            return
        }
        val it = list.listIterator()
        for (i in 0 until n) {
            it.next()
            it.set(items[i] as T)
        }
    }

    private fun takeScratch(): Scratch {
        val thread = Thread.currentThread()
        val scratch = if (thread is TickThread) {
            var existing = thread.sortScratch as Scratch?
            if (existing == null) {
                existing = Scratch()
                thread.sortScratch = existing
            }
            existing
        } else {
            LOCAL.get()
        }
        if (scratch.busy) return Scratch().also { it.busy = true }
        scratch.busy = true
        return scratch
    }

    private class Scratch {
        var keys = DoubleArray(INITIAL_CAPACITY)
        var items = arrayOfNulls<Any>(INITIAL_CAPACITY)
        var tmpKeys = DoubleArray(INITIAL_CAPACITY)
        var tmpItems = arrayOfNulls<Any>(INITIAL_CAPACITY)
        var busy = false

        fun ensureCapacity(needed: Int) {
            if (keys.size >= needed) return
            val capacity = maxOf(needed, keys.size * 2)
            keys = DoubleArray(capacity)
            items = arrayOfNulls(capacity)
            tmpKeys = DoubleArray(capacity)
            tmpItems = arrayOfNulls(capacity)
        }

        fun release(used: Int) {
            if (used > 0) {
                Arrays.fill(items, 0, used, null)
                if (used > INSERTION_CUTOFF) Arrays.fill(tmpItems, 0, used, null)
            }
            busy = false
        }
    }
}
