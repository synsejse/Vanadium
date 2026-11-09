package com.synsenetwork.vanadium.collections

import it.unimi.dsi.fastutil.HashCommon
import it.unimi.dsi.fastutil.longs.AbstractLong2ObjectMap
import it.unimi.dsi.fastutil.longs.Long2ObjectMap
import it.unimi.dsi.fastutil.objects.ObjectSet
import it.unimi.dsi.fastutil.objects.ObjectSets
import java.util.function.LongUnaryOperator

/**
 * A [ConcurrentLongObjectMap] that reports insertions and removals, for maps whose owner keeps a derived
 * index. Writes whose keys share a [group] run with their callbacks under one stripe lock, so the index for
 * a group is never updated by two threads at once; reads take no lock. Callback semantics follow the
 * listening map it replaces: replacing a value reports a removal and then an addition, both with the new
 * value. Mutate only through [put] and [remove]; entry views are read-only.
 */
class ListeningConcurrentLongObjectMap<V>(
    private val listener: Listener<V>,
    private val group: LongUnaryOperator,
) : AbstractLong2ObjectMap<V>() {
    fun interface Listener<V> {
        fun changed(key: Long, value: V, added: Boolean)
    }

    private val backing = ConcurrentLongObjectMap<V>()
    private val stripes = Array(STRIPES) { Any() }

    private fun stripe(key: Long): Any = stripes[HashCommon.mix(group.applyAsLong(key)).toInt() and (STRIPES - 1)]

    override val size: Int get() = backing.size

    override fun isEmpty(): Boolean = backing.isEmpty()

    override fun get(key: Long): V? = backing.get(key)

    override fun containsKey(key: Long): Boolean = backing.containsKey(key)

    override fun containsValue(value: V): Boolean = backing.containsValue(value)

    override fun put(key: Long, value: V): V? = synchronized(stripe(key)) {
        val previous = backing.put(key, value)
        if (previous !== value) {
            if (previous != null) listener.changed(key, value, false)
            listener.changed(key, value, true)
        }
        previous
    }

    override fun remove(key: Long): V? = synchronized(stripe(key)) {
        val previous = backing.remove(key)
        if (previous != null) listener.changed(key, previous, false)
        previous
    }

    /** Copies entries that the owner's index already reflects, without reporting them. */
    fun putAllSilently(entries: Long2ObjectMap<out V>) {
        for (entry in entries.long2ObjectEntrySet()) backing.put(entry.longKey, entry.value)
    }

    override fun clear() {
        for (key in backing.keys.toLongArray()) remove(key)
    }

    override fun long2ObjectEntrySet(): ObjectSet<Long2ObjectMap.Entry<V>> = ObjectSets.unmodifiable(backing.long2ObjectEntrySet())

    private companion object {
        const val STRIPES = 64
    }
}
