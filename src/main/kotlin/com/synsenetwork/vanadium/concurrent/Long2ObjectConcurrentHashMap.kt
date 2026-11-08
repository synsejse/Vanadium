package com.synsenetwork.vanadium.concurrent

import it.unimi.dsi.fastutil.longs.AbstractLong2ObjectMap
import it.unimi.dsi.fastutil.longs.Long2ObjectFunction
import it.unimi.dsi.fastutil.longs.Long2ObjectMap
import it.unimi.dsi.fastutil.objects.AbstractObjectSet
import it.unimi.dsi.fastutil.objects.ObjectIterator
import it.unimi.dsi.fastutil.objects.ObjectSet
import java.util.concurrent.ConcurrentHashMap
import java.util.function.BiFunction
import java.util.function.LongFunction

/**
 * Thread-safe [Long2ObjectMap] over a [ConcurrentHashMap], replacing vanilla's long-keyed maps that
 * workers read and write. Keys are stored through [MixedLongKeys]; views expose the original keys.
 * Single-key operations, including the compute family, are atomic.
 */
class Long2ObjectConcurrentHashMap<V> : AbstractLong2ObjectMap<V>() {
    private val backing = ConcurrentHashMap<Long, V>()

    override val size: Int get() = backing.size

    override fun isEmpty(): Boolean = backing.isEmpty()

    override fun get(key: Long): V? = backing[MixedLongKeys.mix(key)] ?: defRetValue

    override fun containsKey(key: Long): Boolean = backing.containsKey(MixedLongKeys.mix(key))

    override fun containsValue(value: V): Boolean = backing.containsValue(value)

    override fun put(key: Long, value: V): V? = backing.put(MixedLongKeys.mix(key), value) ?: defRetValue

    override fun remove(key: Long): V? = backing.remove(MixedLongKeys.mix(key)) ?: defRetValue

    override fun remove(key: Long, value: Any?): Boolean = backing.remove(MixedLongKeys.mix(key), value)

    override fun putIfAbsent(key: Long, value: V): V? = backing.putIfAbsent(MixedLongKeys.mix(key), value) ?: defRetValue

    override fun clear() = backing.clear()

    @Suppress("UNCHECKED_CAST")
    override fun computeIfAbsent(key: Long, mappingFunction: LongFunction<out V>): V =
        backing.computeIfAbsent(MixedLongKeys.mix(key)) { mappingFunction.apply(key) } as V

    /** A partial function that does not define [key] leaves the map unchanged. */
    @Suppress("UNCHECKED_CAST")
    override fun computeIfAbsent(key: Long, mappingFunction: Long2ObjectFunction<out V>): V {
        if (!mappingFunction.containsKey(key)) return get(key) as V
        return backing.computeIfAbsent(MixedLongKeys.mix(key)) { mappingFunction.get(key) } as V
    }

    override fun computeIfPresent(key: Long, remappingFunction: BiFunction<in Long, in V, out V?>): V? =
        backing.computeIfPresent(MixedLongKeys.mix(key)) { _, old -> remappingFunction.apply(key, old) }

    override fun compute(key: Long, remappingFunction: BiFunction<in Long, in V?, out V?>): V? =
        backing.compute(MixedLongKeys.mix(key)) { _, old -> remappingFunction.apply(key, old) }

    override fun merge(key: Long, value: V, remappingFunction: BiFunction<in V, in V, out V?>): V? =
        backing.merge(MixedLongKeys.mix(key), value!!) { old, new -> remappingFunction.apply(old, new) }

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
        AbstractLong2ObjectMap.BasicEntry<V>(MixedLongKeys.unmix(source.key), source.value) {
        override fun setValue(value: V): V {
            this.value = value
            return source.setValue(value)
        }
    }
}
