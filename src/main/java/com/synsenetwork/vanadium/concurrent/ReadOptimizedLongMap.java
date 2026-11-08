package com.synsenetwork.vanadium.concurrent;

import it.unimi.dsi.fastutil.HashCommon;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;

/**
 * A long-keyed map for read-mostly data shared by many threads: lookups take no lock, allocate
 * nothing and never write shared memory; writers serialize on the map's monitor.
 *
 * <p>Open addressing with linear probing over immutable entries. A slot only ever goes from empty
 * to an entry, or from an entry to another entry or a tombstone, so a concurrent reader's probe
 * chain stays intact; it observes either the old or the new mapping for a key being written.
 * Resizing builds a new table and publishes it, leaving the old one untouched for in-flight readers.
 */
public final class ReadOptimizedLongMap<V> {
    private static final VarHandle SLOTS = MethodHandles.arrayElementVarHandle(Object[].class);
    private static final Object TOMBSTONE = new Object();

    private record Entry(long key, Object value) {}

    private volatile Object[] table = new Object[64];
    private int size;
    private int used; // entries plus tombstones, guarded by this

    @SuppressWarnings("unchecked")
    public V get(long key) {
        Object[] slots = table;
        int mask = slots.length - 1;
        for (int i = index(key, mask); ; i = (i + 1) & mask) {
            Object slot = SLOTS.getAcquire(slots, i);
            if (slot == null) return null;
            if (slot instanceof Entry entry && entry.key == key) return (V) entry.value;
        }
    }

    public synchronized void put(long key, V value) {
        Object[] slots = table;
        int mask = slots.length - 1;
        int insert = -1;
        for (int i = index(key, mask); ; i = (i + 1) & mask) {
            Object slot = slots[i];
            if (slot == null) {
                if (insert < 0) {
                    insert = i;
                    used++;
                }
                break;
            }
            if (slot == TOMBSTONE) {
                if (insert < 0) insert = i;
            } else if (((Entry) slot).key == key) {
                SLOTS.setRelease(slots, i, new Entry(key, value));
                return;
            }
        }
        SLOTS.setRelease(slots, insert, new Entry(key, value));
        size++;
        if (used * 2 > slots.length) rebuild(slots.length * (size * 4 > slots.length ? 2 : 1));
    }

    /** Removes the mapping only while it still maps to {@code expected} (identity). */
    public synchronized boolean remove(long key, V expected) {
        Object[] slots = table;
        int mask = slots.length - 1;
        for (int i = index(key, mask); ; i = (i + 1) & mask) {
            Object slot = slots[i];
            if (slot == null) return false;
            if (slot instanceof Entry entry && entry.key == key) {
                if (entry.value != expected) return false;
                SLOTS.setRelease(slots, i, TOMBSTONE);
                size--;
                return true;
            }
        }
    }

    public synchronized int size() {
        return size;
    }

    public synchronized void clear() {
        table = new Object[64];
        size = 0;
        used = 0;
    }

    private void rebuild(int capacity) {
        Object[] old = table;
        Object[] slots = new Object[Math.max(64, capacity)];
        int mask = slots.length - 1;
        for (Object slot : old) {
            if (slot instanceof Entry entry) {
                int i = index(entry.key, mask);
                while (slots[i] != null) i = (i + 1) & mask;
                slots[i] = entry;
            }
        }
        used = size;
        table = slots; // Volatile publication of the fully built table.
    }

    private static int index(long key, int mask) {
        return (int) HashCommon.mix(key) & mask;
    }
}
