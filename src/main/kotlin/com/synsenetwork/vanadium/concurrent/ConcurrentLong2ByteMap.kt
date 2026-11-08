package com.synsenetwork.vanadium.concurrent

import it.unimi.dsi.fastutil.HashCommon
import it.unimi.dsi.fastutil.longs.AbstractLong2ByteMap
import it.unimi.dsi.fastutil.longs.Long2ByteMap
import it.unimi.dsi.fastutil.objects.AbstractObjectSet
import it.unimi.dsi.fastutil.objects.ObjectIterator
import it.unimi.dsi.fastutil.objects.ObjectSet
import java.lang.invoke.MethodHandles
import java.lang.invoke.VarHandle

/**
 * A [Long2ByteMap] for chunk and section levels that workers read on every entity tick while the
 * server thread propagates them: lookups take no lock, box nothing and never write shared memory;
 * writers serialize on the map.
 *
 * Open addressing with linear probing over primitive arrays. A slot's key is written once, before
 * the slot is published as FULL with release semantics; removal turns it into a tombstone that is
 * only reclaimed by a rebuild, so a reader that sees FULL always reads that slot's own key. Values
 * of a FULL slot are replaced in place (a racing reader sees the old or the new byte). A rebuild
 * publishes a new table and leaves the old one intact for in-flight readers.
 */
class ConcurrentLong2ByteMap : AbstractLong2ByteMap() {
    private class Table(capacity: Int) {
        val keys = LongArray(capacity)
        val values = ByteArray(capacity)
        val states = ByteArray(capacity)
        val mask = capacity - 1
    }

    @Volatile
    private var table = Table(MIN_CAPACITY)

    @Volatile
    private var count = 0
    private var used = 0 // FULL plus tombstones in the current table; guarded by this

    override val size: Int get() = count

    override fun isEmpty(): Boolean = count == 0

    override fun get(key: Long): Byte {
        val t = table
        var i = index(key, t.mask)
        while (true) {
            when (STATES.getAcquire(t.states, i) as Byte) {
                EMPTY -> return defRetValue
                FULL -> if (t.keys[i] == key) return VALUES.getAcquire(t.values, i) as Byte
            }
            i = (i + 1) and t.mask
        }
    }

    override fun containsKey(key: Long): Boolean {
        val t = table
        var i = index(key, t.mask)
        while (true) {
            when (STATES.getAcquire(t.states, i) as Byte) {
                EMPTY -> return false
                FULL -> if (t.keys[i] == key) return true
            }
            i = (i + 1) and t.mask
        }
    }

    override fun containsValue(value: Byte): Boolean {
        var found = false
        forEachEntry { _, v -> if (v == value) found = true }
        return found
    }

    @Synchronized
    override fun put(key: Long, value: Byte): Byte {
        val t = table
        var i = index(key, t.mask)
        while (true) {
            when (t.states[i]) {
                EMPTY -> break
                FULL -> if (t.keys[i] == key) {
                    val old = t.values[i]
                    VALUES.setRelease(t.values, i, value)
                    return old
                }
            }
            i = (i + 1) and t.mask
        }
        t.keys[i] = key
        t.values[i] = value
        STATES.setRelease(t.states, i, FULL)
        count++
        if (++used * 2 > t.keys.size) rebuild(if (count * 4 > t.keys.size) t.keys.size * 2 else t.keys.size)
        return defRetValue
    }

    @Synchronized
    override fun remove(key: Long): Byte {
        val t = table
        var i = index(key, t.mask)
        while (true) {
            when (t.states[i]) {
                EMPTY -> return defRetValue
                FULL -> if (t.keys[i] == key) {
                    STATES.setRelease(t.states, i, TOMBSTONE)
                    count--
                    return t.values[i]
                }
            }
            i = (i + 1) and t.mask
        }
    }

    @Synchronized
    override fun clear() {
        table = Table(MIN_CAPACITY)
        count = 0
        used = 0
    }

    /** Visits a snapshot of the mappings without locking or entry objects. */
    fun forEachEntry(consumer: EntryConsumer) {
        val t = table
        for (i in t.keys.indices) {
            if (STATES.getAcquire(t.states, i) as Byte == FULL) consumer.accept(t.keys[i], VALUES.getAcquire(t.values, i) as Byte)
        }
    }

    fun interface EntryConsumer {
        fun accept(key: Long, value: Byte)
    }

    override fun long2ByteEntrySet(): ObjectSet<Long2ByteMap.Entry> = entrySetView

    private val entrySetView = object : AbstractObjectSet<Long2ByteMap.Entry>() {
        override val size: Int get() = count

        override fun iterator(): ObjectIterator<Long2ByteMap.Entry> = object : ObjectIterator<Long2ByteMap.Entry> {
            private val t = table
            private var next = advance(0)
            private var last = -1

            private fun advance(from: Int): Int {
                var i = from
                while (i < t.keys.size && STATES.getAcquire(t.states, i) as Byte != FULL) i++
                return i
            }

            override fun hasNext(): Boolean = next < t.keys.size

            override fun next(): Long2ByteMap.Entry {
                if (!hasNext()) throw NoSuchElementException()
                last = next
                next = advance(next + 1)
                return BasicEntry(t.keys[last], VALUES.getAcquire(t.values, last) as Byte)
            }

            override fun remove() {
                check(last >= 0)
                this@ConcurrentLong2ByteMap.remove(t.keys[last])
                last = -1
            }
        }
    }

    private fun rebuild(capacity: Int) {
        val old = table
        val next = Table(maxOf(MIN_CAPACITY, capacity))
        for (i in old.keys.indices) {
            if (old.states[i] != FULL) continue
            var j = index(old.keys[i], next.mask)
            while (next.states[j] != EMPTY) j = (j + 1) and next.mask
            next.keys[j] = old.keys[i]
            next.values[j] = old.values[i]
            next.states[j] = FULL
        }
        used = count
        table = next // Volatile publication of the fully built table.
    }

    private companion object {
        const val MIN_CAPACITY = 64
        const val EMPTY: Byte = 0
        const val FULL: Byte = 1
        const val TOMBSTONE: Byte = 2
        val STATES: VarHandle = MethodHandles.arrayElementVarHandle(ByteArray::class.java)
        val VALUES: VarHandle = MethodHandles.arrayElementVarHandle(ByteArray::class.java)

        fun index(key: Long, mask: Int): Int = HashCommon.mix(key).toInt() and mask
    }
}
