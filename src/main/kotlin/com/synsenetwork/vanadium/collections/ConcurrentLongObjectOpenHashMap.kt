package com.synsenetwork.vanadium.collections

import it.unimi.dsi.fastutil.longs.Long2ObjectFunction
import it.unimi.dsi.fastutil.longs.Long2ObjectMap
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap
import it.unimi.dsi.fastutil.longs.LongObjectBiConsumer
import it.unimi.dsi.fastutil.longs.LongSet
import it.unimi.dsi.fastutil.objects.AbstractObjectSet
import it.unimi.dsi.fastutil.objects.ObjectCollection
import it.unimi.dsi.fastutil.objects.ObjectIterator
import java.util.function.BiFunction
import java.util.function.LongFunction

/**
 * Thread-safe stand-in for fields typed [Long2ObjectOpenHashMap], backed by a [ConcurrentLongObjectMap].
 * The superclass storage is never used, so every method that would read it is overridden. Single-key
 * operations are atomic; views are weakly consistent and write through to the backing map.
 */
class ConcurrentLongObjectOpenHashMap<V> : Long2ObjectOpenHashMap<V>() {
    private val backing = ConcurrentLongObjectMap<V>()

    override fun defaultReturnValue(rv: V?) {
        super.defaultReturnValue(rv)
        backing.defaultReturnValue(rv)
    }

    override val size: Int get() = backing.size

    override fun isEmpty(): Boolean = backing.isEmpty()

    override fun get(k: Long): V? = backing.get(k)

    override fun getOrDefault(k: Long, defaultValue: V?): V? = backing.getOrDefault(k, defaultValue)

    override fun containsKey(k: Long): Boolean = backing.containsKey(k)

    override fun containsValue(v: V): Boolean = backing.containsValue(v)

    override fun put(k: Long, v: V): V? = backing.put(k, v)

    override fun putAll(from: Map<out Long, V>) = backing.putAll(from)

    override fun putIfAbsent(k: Long, v: V): V? = backing.putIfAbsent(k, v)

    override fun remove(k: Long): V? = backing.remove(k)

    override fun remove(k: Long, v: Any?): Boolean = backing.remove(k, v)

    override fun replace(k: Long, oldValue: V, v: V): Boolean = backing.replace(k, oldValue, v)

    override fun replace(k: Long, v: V): V? = backing.replace(k, v)

    override fun computeIfAbsent(k: Long, mappingFunction: LongFunction<out V>): V = backing.computeIfAbsent(k, mappingFunction)

    override fun computeIfAbsent(key: Long, mappingFunction: Long2ObjectFunction<out V>): V = backing.computeIfAbsent(key, mappingFunction)

    override fun computeIfPresent(k: Long, remappingFunction: BiFunction<in Long, in V, out V?>): V? =
        backing.computeIfPresent(k, remappingFunction)

    override fun compute(k: Long, remappingFunction: BiFunction<in Long, in V?, out V?>): V? = backing.compute(k, remappingFunction)

    override fun merge(k: Long, v: V, remappingFunction: BiFunction<in V, in V, out V?>): V? = backing.merge(k, v, remappingFunction)

    override fun clear() = backing.clear()

    override fun forEach(consumer: LongObjectBiConsumer<in V>) {
        for (entry in backing.long2ObjectEntrySet()) consumer.accept(entry.longKey, entry.value)
    }

    override fun long2ObjectEntrySet(): Long2ObjectMap.FastEntrySet<V> = entryView

    private val entryView = object : AbstractObjectSet<Long2ObjectMap.Entry<V>>(), Long2ObjectMap.FastEntrySet<V> {
        override val size: Int get() = backing.size

        override fun iterator(): ObjectIterator<Long2ObjectMap.Entry<V>> = backing.long2ObjectEntrySet().iterator()

        override fun fastIterator(): ObjectIterator<Long2ObjectMap.Entry<V>> = iterator()
    }

    override val keys: LongSet get() = backing.keys

    override val values: ObjectCollection<V> get() = backing.values

    override fun ensureCapacity(capacity: Int) = Unit

    override fun trim(): Boolean = true

    override fun trim(n: Int): Boolean = true

    override fun clone(): Long2ObjectOpenHashMap<V> = ConcurrentLongObjectOpenHashMap<V>().also { it.putAll(this) }

    override fun hashCode(): Int = backing.hashCode()
}
