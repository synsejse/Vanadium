package com.synsenetwork.vanadium.collections

import it.unimi.dsi.fastutil.longs.AbstractLong2LongMap
import it.unimi.dsi.fastutil.longs.Long2LongMap
import it.unimi.dsi.fastutil.objects.AbstractObjectSet
import it.unimi.dsi.fastutil.objects.ObjectIterator
import it.unimi.dsi.fastutil.objects.ObjectSet
import org.jctools.maps.NonBlockingHashMapLong

/**
 * Thread-safe [Long2LongMap] over JCTools [NonBlockingHashMapLong], replacing vanilla's
 * `LevelTicks.nextTickForContainer` so concurrent chunk tick scheduling never contends on a level-wide
 * monitor or boxes position keys. Keys are mixed via [PackedPositionKeys] and decoded in views;
 * iterator writes and removals write through to the backing map.
 */
class ConcurrentLongLongMap @JvmOverloads constructor(initialCapacity: Int = 64) : AbstractLong2LongMap() {
    private val backing = NonBlockingHashMapLong<Long>(nonBlockingSlots(initialCapacity), true)

    override val size: Int get() = backing.size

    override fun isEmpty(): Boolean = backing.isEmpty()

    override fun get(key: Long): Long = backing.get(PackedPositionKeys.mix(key)) ?: defRetValue

    override fun containsKey(key: Long): Boolean = backing.containsKey(PackedPositionKeys.mix(key))

    override fun containsValue(value: Long): Boolean = backing.containsValue(value)

    override fun put(key: Long, value: Long): Long = backing.put(PackedPositionKeys.mix(key), value) ?: defRetValue

    override fun remove(key: Long): Long = backing.remove(PackedPositionKeys.mix(key)) ?: defRetValue

    override fun clear() = backing.clear()

    override fun long2LongEntrySet(): ObjectSet<Long2LongMap.Entry> = entrySetView

    private val entrySetView = object : AbstractObjectSet<Long2LongMap.Entry>() {
        override val size: Int get() = backing.size

        override fun iterator(): ObjectIterator<Long2LongMap.Entry> = object : ObjectIterator<Long2LongMap.Entry> {
            private val source = backing.entries.iterator()
            override fun hasNext(): Boolean = source.hasNext()
            override fun next(): Long2LongMap.Entry = Entry(source.next())
            override fun remove() = source.remove()
        }
    }

    /** Writes through to the backing entry. */
    private class Entry(private val source: MutableMap.MutableEntry<Long, Long>) :
        AbstractLong2LongMap.BasicEntry(PackedPositionKeys.unmix(source.key), source.value) {
        override fun setValue(value: Long): Long {
            val previous = this.value
            this.value = value
            source.setValue(value)
            return previous
        }
    }
}
