package com.synsenetwork.vanadium.collections

import it.unimi.dsi.fastutil.longs.AbstractLong2ByteMap
import it.unimi.dsi.fastutil.longs.Long2ByteMap
import it.unimi.dsi.fastutil.objects.AbstractObjectSet
import it.unimi.dsi.fastutil.objects.ObjectIterator
import it.unimi.dsi.fastutil.objects.ObjectSet
import org.jctools.maps.NonBlockingHashMapLong
import java.util.concurrent.atomic.AtomicLong

/**
 * Fastutil field adapter; JCTools owns storage, publication and resizing. Byte values are JVM-cached.
 *
 * A [versioned] map counts writes that may change an entry, so a reader can cache what it derived from it:
 * read [version] before deriving, and the cache is current while [version] still returns that value. Only
 * for rarely written maps (simulation levels): every write increments one shared counter.
 */
class ConcurrentLongByteMap @JvmOverloads constructor(versioned: Boolean = false) : AbstractLong2ByteMap() {
    private val backing = NonBlockingHashMapLong<Byte>()
    private val writes: AtomicLong? = if (versioned) AtomicLong() else null

    val isVersioned: Boolean get() = writes != null

    /** Advances after every write that may have changed an entry; requires a [versioned] map. */
    val version: Long get() = checkNotNull(writes) { "not a versioned map" }.get()

    override val size: Int get() = backing.size
    override fun isEmpty(): Boolean = backing.isEmpty()
    override fun get(key: Long): Byte = backing.get(key) ?: defRetValue
    override fun containsKey(key: Long): Boolean = backing.containsKey(key)
    override fun containsValue(value: Byte): Boolean = backing.containsValue(value)
    override fun put(key: Long, value: Byte): Byte {
        val previous: Byte? = backing.put(key, value)
        if (previous != value) changed()
        return previous ?: defRetValue
    }

    override fun remove(key: Long): Byte {
        val previous: Byte? = backing.remove(key)
        if (previous != null) changed()
        return previous ?: defRetValue
    }

    override fun clear() {
        backing.clear()
        changed()
    }

    private fun changed() {
        writes?.incrementAndGet()
    }

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
            override fun remove() {
                source.remove()
                changed()
            }
        }
    }
}
