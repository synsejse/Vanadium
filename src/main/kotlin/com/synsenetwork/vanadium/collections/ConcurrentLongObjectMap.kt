package com.synsenetwork.vanadium.collections

import it.unimi.dsi.fastutil.longs.AbstractLong2ObjectMap
import it.unimi.dsi.fastutil.longs.Long2ObjectFunction
import it.unimi.dsi.fastutil.longs.Long2ObjectMap
import it.unimi.dsi.fastutil.objects.AbstractObjectSet
import it.unimi.dsi.fastutil.objects.ObjectIterator
import it.unimi.dsi.fastutil.objects.ObjectSet
import org.jctools.maps.NonBlockingHashMapLong
import java.util.function.BiFunction
import java.util.function.LongFunction

/**
 * Thread-safe, unboxed [Long2ObjectMap] over JCTools [NonBlockingHashMapLong], replacing vanilla's
 * long-keyed maps that workers read and write. Keys are stored through [PackedPositionKeys] so packed
 * chunk and section keys do not cluster on the low bits of [NonBlockingHashMapLong]'s hash; views
 * expose the original keys. Point reads and writes are lock-free and never box keys; the compute and
 * merge family serializes per-stripe on misses/updates so mapping functions run atomically.
 */
class ConcurrentLongObjectMap<V> @JvmOverloads constructor(initialCapacity: Int = 16) : AbstractLong2ObjectMap<V>() {
    private val backing = NonBlockingHashMapLong<V>(nonBlockingSlots(initialCapacity), true)

    @Volatile
    private var computeStripes: Array<Any>? = null

    override val size: Int get() = backing.size

    override fun isEmpty(): Boolean = backing.isEmpty()

    override fun get(key: Long): V? = backing.get(PackedPositionKeys.mix(key)) ?: defRetValue

    override fun getOrDefault(key: Long, defaultValue: V?): V? =
        backing.get(PackedPositionKeys.mix(key)) ?: defaultValue

    override fun containsKey(key: Long): Boolean = backing.containsKey(PackedPositionKeys.mix(key))

    override fun containsValue(value: V): Boolean = value != null && backing.containsValue(value)

    override fun put(key: Long, value: V): V? = backing.put(PackedPositionKeys.mix(key), value) ?: defRetValue

    override fun remove(key: Long): V? = backing.remove(PackedPositionKeys.mix(key)) ?: defRetValue

    override fun remove(key: Long, value: Any?): Boolean {
        if (value == null) return false
        val mixed = PackedPositionKeys.mix(key)
        while (true) {
            val current = backing.get(mixed) ?: return false
            if (current != value) return false
            // JCTools compares putIfMatch's return value by identity; pass the reference just read.
            if (backing.remove(mixed, current as Any)) return true
        }
    }

    override fun putIfAbsent(key: Long, value: V): V? =
        backing.putIfAbsent(PackedPositionKeys.mix(key), value) ?: defRetValue

    override fun replace(key: Long, value: V): V? =
        backing.replace(PackedPositionKeys.mix(key), value) ?: defRetValue

    override fun replace(key: Long, oldValue: V, newValue: V): Boolean {
        if (oldValue == null) return false
        val mixed = PackedPositionKeys.mix(key)
        while (true) {
            val current = backing.get(mixed) ?: return false
            if (current != oldValue) return false
            if (backing.replace(mixed, current, newValue)) return true
        }
    }

    override fun clear() = backing.clear()

    @Suppress("UNCHECKED_CAST")
    override fun computeIfAbsent(key: Long, mappingFunction: LongFunction<out V>): V {
        val mixed = PackedPositionKeys.mix(key)
        val existing = backing.get(mixed)
        if (existing != null) return existing
        synchronized(computeStripe(mixed)) {
            val rechecked = backing.get(mixed)
            if (rechecked != null) return rechecked
            val computed = mappingFunction.apply(key) ?: return defRetValue as V
            val raced = backing.putIfAbsent(mixed, computed)
            return raced ?: computed
        }
    }

    /** A partial function that does not define [key] leaves the map unchanged. */
    @Suppress("UNCHECKED_CAST")
    override fun computeIfAbsent(key: Long, mappingFunction: Long2ObjectFunction<out V>): V {
        val mixed = PackedPositionKeys.mix(key)
        val existing = backing.get(mixed)
        if (existing != null) return existing
        if (!mappingFunction.containsKey(key)) return defRetValue as V
        synchronized(computeStripe(mixed)) {
            val rechecked = backing.get(mixed)
            if (rechecked != null) return rechecked
            if (!mappingFunction.containsKey(key)) return defRetValue as V
            val computed = mappingFunction.get(key) ?: return defRetValue as V
            val raced = backing.putIfAbsent(mixed, computed)
            return raced ?: computed
        }
    }

    override fun computeIfPresent(key: Long, remappingFunction: BiFunction<in Long, in V, out V?>): V? {
        val mixed = PackedPositionKeys.mix(key)
        if (backing.get(mixed) == null) return defRetValue
        synchronized(computeStripe(mixed)) {
            while (true) {
                val old = backing.get(mixed) ?: return defRetValue
                val next = remappingFunction.apply(key, old)
                if (next == null) {
                    if (backing.remove(mixed, old as Any)) return defRetValue
                } else if (backing.replace(mixed, old, next)) {
                    return next
                }
            }
        }
    }

    override fun compute(key: Long, remappingFunction: BiFunction<in Long, in V?, out V?>): V? {
        val mixed = PackedPositionKeys.mix(key)
        synchronized(computeStripe(mixed)) {
            while (true) {
                val old = backing.get(mixed)
                val next = remappingFunction.apply(key, old)
                if (next == null) {
                    if (old == null || backing.remove(mixed, old as Any)) return defRetValue
                } else if (old == null) {
                    if (backing.putIfAbsent(mixed, next) == null) return next
                } else if (backing.replace(mixed, old, next)) {
                    return next
                }
            }
        }
    }

    override fun merge(key: Long, value: V, remappingFunction: BiFunction<in V, in V, out V?>): V? {
        val nonNull = checkNotNull(value) { "null value" }
        val mixed = PackedPositionKeys.mix(key)
        synchronized(computeStripe(mixed)) {
            while (true) {
                val old = backing.get(mixed)
                if (old == null) {
                    if (backing.putIfAbsent(mixed, nonNull) == null) return nonNull
                } else {
                    val next = remappingFunction.apply(old, nonNull)
                    if (next == null) {
                        if (backing.remove(mixed, old as Any)) return defRetValue
                    } else if (backing.replace(mixed, old, next)) {
                        return next
                    }
                }
            }
        }
    }

    private fun computeStripe(mixed: Long): Any {
        val stripes = computeStripes ?: initComputeStripes()
        return stripes[mixed.toInt() and (COMPUTE_STRIPES - 1)]
    }

    @Synchronized
    private fun initComputeStripes(): Array<Any> {
        val existing = computeStripes
        if (existing != null) return existing
        val created = Array(COMPUTE_STRIPES) { Any() }
        computeStripes = created
        return created
    }

    override fun long2ObjectEntrySet(): ObjectSet<Long2ObjectMap.Entry<V>> = entrySetView

    private val entrySetView = object : AbstractObjectSet<Long2ObjectMap.Entry<V>>() {
        override val size: Int get() = backing.size

        override fun iterator(): ObjectIterator<Long2ObjectMap.Entry<V>> = object : ObjectIterator<Long2ObjectMap.Entry<V>> {
            private val source = backing.entries.iterator()
            override fun hasNext(): Boolean = source.hasNext()
            override fun next(): Long2ObjectMap.Entry<V> = Entry(source.next())
            override fun remove() = source.remove()
        }
    }

    /** Writes through to the backing entry. */
    private class Entry<V>(private val source: MutableMap.MutableEntry<Long, V>) :
        AbstractLong2ObjectMap.BasicEntry<V>(PackedPositionKeys.unmix(source.key), source.value) {
        override fun setValue(value: V): V {
            this.value = value
            return source.setValue(value)
        }
    }

    private companion object {
        const val COMPUTE_STRIPES = 32
    }
}

/**
 * Converts an expected entry count into a JCTools [NonBlockingHashMapLong] slot count. With `opt_for_space = true`,
 * JCTools resizes at 50% occupancy (vs. 25% by default); 3x keeps load at or below ~33% and avoids multi-thread
 * `help_copy` resize storms when many workers populate a fresh map simultaneously.
 */
internal fun nonBlockingSlots(expectedEntries: Int): Int {
    if (expectedEntries <= 4) return 16
    return (expectedEntries.toLong() * 3L).coerceAtMost(1L shl 24).toInt()
}
