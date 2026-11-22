package com.synsenetwork.vanadium.collections

import it.unimi.dsi.fastutil.ints.AbstractInt2ObjectMap
import it.unimi.dsi.fastutil.ints.Int2ObjectFunction
import it.unimi.dsi.fastutil.ints.Int2ObjectMap
import it.unimi.dsi.fastutil.objects.AbstractObjectSet
import it.unimi.dsi.fastutil.objects.ObjectIterator
import it.unimi.dsi.fastutil.objects.ObjectSet
import org.jctools.maps.NonBlockingHashMapLong
import java.util.function.BiFunction
import java.util.function.IntFunction

/**
 * Thread-safe, unboxed [Int2ObjectMap] over JCTools [NonBlockingHashMapLong], replacing vanilla's
 * entity-ID and chunk-section-Y maps. Sequential int keys hash well directly as longs; point reads
 * and writes are lock-free and never box keys, while the compute and merge family serializes
 * per-stripe on misses/updates so mapping functions run atomically.
 */
class ConcurrentIntObjectMap<V> @JvmOverloads constructor(initialCapacity: Int = 16) : AbstractInt2ObjectMap<V>() {
    private val backing = NonBlockingHashMapLong<V>(nonBlockingSlots(initialCapacity), true)

    @Volatile
    private var computeStripes: Array<Any>? = null

    override val size: Int get() = backing.size

    override fun isEmpty(): Boolean = backing.isEmpty()

    override fun get(key: Int): V? = backing.get(key.toLong()) ?: defRetValue

    override fun getOrDefault(key: Int, defaultValue: V?): V? = backing.get(key.toLong()) ?: defaultValue

    override fun containsKey(key: Int): Boolean = backing.containsKey(key.toLong())

    override fun containsValue(value: V): Boolean = value != null && backing.containsValue(value)

    override fun put(key: Int, value: V): V? = backing.put(key.toLong(), value) ?: defRetValue

    override fun remove(key: Int): V? = backing.remove(key.toLong()) ?: defRetValue

    override fun remove(key: Int, value: Any?): Boolean {
        if (value == null) return false
        val k = key.toLong()
        while (true) {
            val current = backing.get(k) ?: return false
            if (current != value) return false
            // JCTools compares putIfMatch's return value by identity; pass the reference just read.
            if (backing.remove(k, current as Any)) return true
        }
    }

    override fun putIfAbsent(key: Int, value: V): V? = backing.putIfAbsent(key.toLong(), value) ?: defRetValue

    override fun replace(key: Int, value: V): V? = backing.replace(key.toLong(), value) ?: defRetValue

    override fun replace(key: Int, oldValue: V, newValue: V): Boolean {
        if (oldValue == null) return false
        val k = key.toLong()
        while (true) {
            val current = backing.get(k) ?: return false
            if (current != oldValue) return false
            if (backing.replace(k, current, newValue)) return true
        }
    }

    override fun clear() = backing.clear()

    @Suppress("UNCHECKED_CAST")
    override fun computeIfAbsent(key: Int, mappingFunction: IntFunction<out V>): V {
        val k = key.toLong()
        val existing = backing.get(k)
        if (existing != null) return existing
        synchronized(computeStripe(key)) {
            val rechecked = backing.get(k)
            if (rechecked != null) return rechecked
            val computed = mappingFunction.apply(key) ?: return defRetValue as V
            val raced = backing.putIfAbsent(k, computed)
            return raced ?: computed
        }
    }

    /** A partial function that does not define [key] leaves the map unchanged. */
    @Suppress("UNCHECKED_CAST")
    override fun computeIfAbsent(key: Int, mappingFunction: Int2ObjectFunction<out V>): V {
        val k = key.toLong()
        val existing = backing.get(k)
        if (existing != null) return existing
        if (!mappingFunction.containsKey(key)) return defRetValue as V
        synchronized(computeStripe(key)) {
            val rechecked = backing.get(k)
            if (rechecked != null) return rechecked
            if (!mappingFunction.containsKey(key)) return defRetValue as V
            val computed = mappingFunction.get(key) ?: return defRetValue as V
            val raced = backing.putIfAbsent(k, computed)
            return raced ?: computed
        }
    }

    override fun computeIfPresent(key: Int, remappingFunction: BiFunction<in Int, in V, out V?>): V? {
        val k = key.toLong()
        if (backing.get(k) == null) return defRetValue
        synchronized(computeStripe(key)) {
            while (true) {
                val old = backing.get(k) ?: return defRetValue
                val next = remappingFunction.apply(key, old)
                if (next == null) {
                    if (backing.remove(k, old as Any)) return defRetValue
                } else if (backing.replace(k, old, next)) {
                    return next
                }
            }
        }
    }

    override fun compute(key: Int, remappingFunction: BiFunction<in Int, in V?, out V?>): V? {
        val k = key.toLong()
        synchronized(computeStripe(key)) {
            while (true) {
                val old = backing.get(k)
                val next = remappingFunction.apply(key, old)
                if (next == null) {
                    if (old == null || backing.remove(k, old as Any)) return defRetValue
                } else if (old == null) {
                    if (backing.putIfAbsent(k, next) == null) return next
                } else if (backing.replace(k, old, next)) {
                    return next
                }
            }
        }
    }

    override fun merge(key: Int, value: V, remappingFunction: BiFunction<in V, in V, out V?>): V? {
        val nonNull = checkNotNull(value) { "null value" }
        val k = key.toLong()
        synchronized(computeStripe(key)) {
            while (true) {
                val old = backing.get(k)
                if (old == null) {
                    if (backing.putIfAbsent(k, nonNull) == null) return nonNull
                } else {
                    val next = remappingFunction.apply(old, nonNull)
                    if (next == null) {
                        if (backing.remove(k, old as Any)) return defRetValue
                    } else if (backing.replace(k, old, next)) {
                        return next
                    }
                }
            }
        }
    }

    private fun computeStripe(key: Int): Any {
        val stripes = computeStripes ?: initComputeStripes()
        return stripes[key and (COMPUTE_STRIPES - 1)]
    }

    @Synchronized
    private fun initComputeStripes(): Array<Any> {
        val existing = computeStripes
        if (existing != null) return existing
        val created = Array(COMPUTE_STRIPES) { Any() }
        computeStripes = created
        return created
    }

    override fun int2ObjectEntrySet(): ObjectSet<Int2ObjectMap.Entry<V>> = entrySetView

    private val entrySetView = object : AbstractObjectSet<Int2ObjectMap.Entry<V>>() {
        override val size: Int get() = backing.size

        override fun iterator(): ObjectIterator<Int2ObjectMap.Entry<V>> = object : ObjectIterator<Int2ObjectMap.Entry<V>> {
            private val source = backing.entries.iterator()
            override fun hasNext(): Boolean = source.hasNext()
            override fun next(): Int2ObjectMap.Entry<V> = Entry(source.next())
            override fun remove() = source.remove()
        }
    }

    /** Writes through to the backing entry. */
    private class Entry<V>(private val source: MutableMap.MutableEntry<Long, V>) :
        AbstractInt2ObjectMap.BasicEntry<V>(source.key.toInt(), source.value) {
        override fun setValue(value: V): V {
            this.value = value
            return source.setValue(value)
        }
    }

    private companion object {
        const val COMPUTE_STRIPES = 32
    }
}
