package com.synsenetwork.vanadium.concurrent

import it.unimi.dsi.fastutil.HashCommon
import java.lang.invoke.MethodHandles
import java.lang.invoke.VarHandle

/**
 * A long-keyed map for read-mostly data shared by many threads: lookups take no lock, allocate nothing
 * and never write shared memory; writers serialize on the map's monitor.
 *
 * Open addressing with linear probing over immutable entries. A slot only goes from empty to an entry,
 * or from an entry to another entry or a tombstone, so a concurrent reader's probe chain stays intact
 * and observes either the old or the new mapping for a key being written. Resizing builds a new table
 * and publishes it, leaving the old one untouched for in-flight readers.
 */
class ReadOptimizedLongMap<V : Any> {
    private class Entry(@JvmField val key: Long, @JvmField val value: Any)

    @Volatile
    private var table = arrayOfNulls<Any>(MIN_CAPACITY)
    private var size = 0
    private var used = 0 // entries plus tombstones; guarded by this

    @Suppress("UNCHECKED_CAST")
    operator fun get(key: Long): V? {
        val slots = table
        val mask = slots.size - 1
        var i = index(key, mask)
        while (true) {
            val slot = SLOTS.getAcquire(slots, i) ?: return null
            if (slot is Entry && slot.key == key) return slot.value as V
            i = (i + 1) and mask
        }
    }

    @Synchronized
    fun put(key: Long, value: V) {
        val slots = table
        val mask = slots.size - 1
        var insert = -1
        var i = index(key, mask)
        while (true) {
            val slot = slots[i]
            if (slot == null) {
                if (insert < 0) {
                    insert = i
                    used++
                }
                break
            }
            if (slot === TOMBSTONE) {
                if (insert < 0) insert = i
            } else if ((slot as Entry).key == key) {
                SLOTS.setRelease(slots, i, Entry(key, value))
                return
            }
            i = (i + 1) and mask
        }
        SLOTS.setRelease(slots, insert, Entry(key, value))
        size++
        if (used * 2 > slots.size) rebuild(if (size * 4 > slots.size) slots.size * 2 else slots.size)
    }

    /** Removes the mapping only while it still maps to [expected] (identity). */
    @Synchronized
    fun remove(key: Long, expected: V): Boolean {
        val slots = table
        val mask = slots.size - 1
        var i = index(key, mask)
        while (true) {
            val slot = slots[i] ?: return false
            if (slot is Entry && slot.key == key) {
                if (slot.value !== expected) return false
                SLOTS.setRelease(slots, i, TOMBSTONE)
                size--
                return true
            }
            i = (i + 1) and mask
        }
    }

    /** Removes and returns the mapping for [key], if any. */
    @Synchronized
    fun remove(key: Long): V? {
        val slots = table
        val mask = slots.size - 1
        var i = index(key, mask)
        while (true) {
            val slot = slots[i] ?: return null
            if (slot is Entry && slot.key == key) {
                SLOTS.setRelease(slots, i, TOMBSTONE)
                size--
                @Suppress("UNCHECKED_CAST")
                return slot.value as V
            }
            i = (i + 1) and mask
        }
    }

    /** Visits a snapshot of the keys without locking; concurrent writes may or may not be seen. */
    fun forEachKey(action: java.util.function.LongConsumer) {
        val slots = table
        for (i in slots.indices) {
            val slot = SLOTS.getAcquire(slots, i)
            if (slot is Entry) action.accept(slot.key)
        }
    }

    @Synchronized
    fun size(): Int = size

    @Synchronized
    fun clear() {
        table = arrayOfNulls(MIN_CAPACITY)
        size = 0
        used = 0
    }

    private fun rebuild(capacity: Int) {
        val slots = arrayOfNulls<Any>(maxOf(MIN_CAPACITY, capacity))
        val mask = slots.size - 1
        for (slot in table) {
            if (slot !is Entry) continue
            var i = index(slot.key, mask)
            while (slots[i] != null) i = (i + 1) and mask
            slots[i] = slot
        }
        used = size
        table = slots // Volatile publication of the fully built table.
    }

    private companion object {
        const val MIN_CAPACITY = 64
        val TOMBSTONE = Any()
        val SLOTS: VarHandle = MethodHandles.arrayElementVarHandle(Array<Any?>::class.java)

        fun index(key: Long, mask: Int): Int = HashCommon.mix(key).toInt() and mask
    }
}
