package com.synsenetwork.vanadium.concurrent

import it.unimi.dsi.fastutil.longs.AbstractLong2ByteMap
import it.unimi.dsi.fastutil.longs.Long2ByteMap
import it.unimi.dsi.fastutil.objects.AbstractObjectSet
import it.unimi.dsi.fastutil.objects.ObjectIterator
import it.unimi.dsi.fastutil.objects.ObjectSet
import java.util.concurrent.ConcurrentHashMap

/**
 * Thread-safe [Long2ByteMap] over a [ConcurrentHashMap], replacing vanilla's chunk-level maps that
 * workers read. Keys are stored through [MixedLongKeys]; views expose the original keys.
 */
class Long2ByteConcurrentHashMap @JvmOverloads constructor(initialCapacity: Int = 16, loadFactor: Float = 0.75f) :
    AbstractLong2ByteMap() {
    private val backing = ConcurrentHashMap<Long, Byte>(initialCapacity, loadFactor)

    override val size: Int get() = backing.size

    override fun isEmpty(): Boolean = backing.isEmpty()

    override fun get(key: Long): Byte = backing[MixedLongKeys.mix(key)] ?: defRetValue

    override fun containsKey(key: Long): Boolean = backing.containsKey(MixedLongKeys.mix(key))

    override fun containsValue(value: Byte): Boolean = backing.containsValue(value)

    override fun put(key: Long, value: Byte): Byte = backing.put(MixedLongKeys.mix(key), value) ?: defRetValue

    override fun remove(key: Long): Byte = backing.remove(MixedLongKeys.mix(key)) ?: defRetValue

    override fun clear() = backing.clear()

    /** Visits every mapping with original keys, without entry objects. */
    fun forEachEntry(consumer: EntryConsumer) = backing.forEach { key, value -> consumer.accept(MixedLongKeys.unmix(key), value) }

    fun interface EntryConsumer {
        fun accept(key: Long, value: Byte)
    }

    override fun long2ByteEntrySet(): ObjectSet<Long2ByteMap.Entry> = entrySetView

    private val entrySetView = object : AbstractObjectSet<Long2ByteMap.Entry>() {
        override val size: Int get() = backing.size

        override fun iterator(): ObjectIterator<Long2ByteMap.Entry> = object : ObjectIterator<Long2ByteMap.Entry> {
            private val source = backing.entries.iterator()
            override fun hasNext(): Boolean = source.hasNext()
            override fun next(): Long2ByteMap.Entry = Entry(source.next())
            override fun remove() = source.remove()
        }
    }

    /** Writes through to the backing entry. */
    private class Entry(private val source: MutableMap.MutableEntry<Long, Byte>) :
        AbstractLong2ByteMap.BasicEntry(MixedLongKeys.unmix(source.key), source.value) {
        override fun setValue(value: Byte): Byte {
            this.value = value
            return source.setValue(value)
        }
    }
}
