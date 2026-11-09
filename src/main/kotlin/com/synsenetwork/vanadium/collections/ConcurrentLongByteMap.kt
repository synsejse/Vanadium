package com.synsenetwork.vanadium.collections

import it.unimi.dsi.fastutil.longs.AbstractLong2ByteMap
import it.unimi.dsi.fastutil.longs.Long2ByteMap
import it.unimi.dsi.fastutil.objects.AbstractObjectSet
import it.unimi.dsi.fastutil.objects.ObjectIterator
import it.unimi.dsi.fastutil.objects.ObjectSet
import org.jctools.maps.NonBlockingHashMapLong

/** Fastutil field adapter; JCTools owns storage, publication and resizing. Byte values are JVM-cached. */
class ConcurrentLongByteMap : AbstractLong2ByteMap() {
    private val backing = NonBlockingHashMapLong<Byte>()

    override val size: Int get() = backing.size
    override fun isEmpty(): Boolean = backing.isEmpty()
    override fun get(key: Long): Byte = backing.get(key) ?: defRetValue
    override fun containsKey(key: Long): Boolean = backing.containsKey(key)
    override fun containsValue(value: Byte): Boolean = backing.containsValue(value)
    override fun put(key: Long, value: Byte): Byte = backing.put(key, value) ?: defRetValue
    override fun remove(key: Long): Byte = backing.remove(key) ?: defRetValue
    override fun clear() = backing.clear()

    fun forEachEntry(consumer: EntryConsumer) {
        val keys = backing.IteratorLong()
        while (keys.hasNext()) {
            val key = keys.nextLong()
            val value: Byte? = backing.get(key)
            if (value != null) consumer.accept(key, value)
        }
    }

    fun interface EntryConsumer {
        fun accept(key: Long, value: Byte)
    }

    override fun long2ByteEntrySet(): ObjectSet<Long2ByteMap.Entry> = entrySetView

    private val entrySetView = object : AbstractObjectSet<Long2ByteMap.Entry>() {
        override val size: Int get() = backing.size
        override fun iterator(): ObjectIterator<Long2ByteMap.Entry> = object : ObjectIterator<Long2ByteMap.Entry> {
            private val source = backing.entries.iterator()
            override fun hasNext(): Boolean = source.hasNext()
            override fun next(): Long2ByteMap.Entry = source.next().let { BasicEntry(it.key.toLong(), it.value.toByte()) }
            override fun remove() = source.remove()
        }
    }
}
