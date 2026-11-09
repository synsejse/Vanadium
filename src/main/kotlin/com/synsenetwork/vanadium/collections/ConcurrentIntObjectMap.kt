package com.synsenetwork.vanadium.collections

import it.unimi.dsi.fastutil.ints.AbstractInt2ObjectMap
import it.unimi.dsi.fastutil.ints.Int2ObjectFunction
import it.unimi.dsi.fastutil.ints.Int2ObjectMap
import it.unimi.dsi.fastutil.objects.AbstractObjectSet
import it.unimi.dsi.fastutil.objects.ObjectIterator
import it.unimi.dsi.fastutil.objects.ObjectSet
import java.util.concurrent.ConcurrentHashMap
import java.util.function.BiFunction
import java.util.function.IntFunction

/**
 * Thread-safe [Int2ObjectMap] over a [ConcurrentHashMap], replacing vanilla's entity-ID maps.
 * Sequential entity IDs hash well as-is. Single-key operations, including the compute family, are atomic.
 */
class ConcurrentIntObjectMap<V> : AbstractInt2ObjectMap<V>() {
    private val backing = ConcurrentHashMap<Int, V>()

    override val size: Int get() = backing.size

    override fun isEmpty(): Boolean = backing.isEmpty()

    override fun get(key: Int): V? = backing[key] ?: defRetValue

    override fun containsKey(key: Int): Boolean = backing.containsKey(key)

    override fun containsValue(value: V): Boolean = backing.containsValue(value)

    override fun put(key: Int, value: V): V? = backing.put(key, value) ?: defRetValue

    override fun remove(key: Int): V? = backing.remove(key) ?: defRetValue

    override fun remove(key: Int, value: Any?): Boolean = backing.remove(key, value)

    override fun putIfAbsent(key: Int, value: V): V? = backing.putIfAbsent(key, value) ?: defRetValue

    override fun clear() = backing.clear()

    @Suppress("UNCHECKED_CAST")
    override fun computeIfAbsent(key: Int, mappingFunction: IntFunction<out V>): V =
        backing.computeIfAbsent(key) { mappingFunction.apply(key) } as V

    /** A partial function that does not define [key] leaves the map unchanged. */
    @Suppress("UNCHECKED_CAST")
    override fun computeIfAbsent(key: Int, mappingFunction: Int2ObjectFunction<out V>): V {
        if (!mappingFunction.containsKey(key)) return get(key) as V
        return backing.computeIfAbsent(key) { mappingFunction.get(key) } as V
    }

    override fun computeIfPresent(key: Int, remappingFunction: BiFunction<in Int, in V, out V?>): V? =
        backing.computeIfPresent(key) { _, old -> remappingFunction.apply(key, old) }

    override fun compute(key: Int, remappingFunction: BiFunction<in Int, in V?, out V?>): V? =
        backing.compute(key) { _, old -> remappingFunction.apply(key, old) }

    override fun merge(key: Int, value: V, remappingFunction: BiFunction<in V, in V, out V?>): V? =
        backing.merge(key, value!!) { old, new -> remappingFunction.apply(old, new) }

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
    private class Entry<V>(private val source: MutableMap.MutableEntry<Int, V>) :
        AbstractInt2ObjectMap.BasicEntry<V>(source.key, source.value) {
        override fun setValue(value: V): V {
            this.value = value
            return source.setValue(value)
        }
    }
}
