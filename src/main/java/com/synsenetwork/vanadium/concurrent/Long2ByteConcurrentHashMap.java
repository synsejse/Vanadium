package com.synsenetwork.vanadium.concurrent;

import it.unimi.dsi.fastutil.bytes.ByteCollection;
import it.unimi.dsi.fastutil.longs.Long2ByteMap;
import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.objects.ObjectSet;
import org.jetbrains.annotations.NotNull;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe {@link Long2ByteMap} backed by a {@code ConcurrentHashMap}, used to replace
 * vanilla's internal long-to-byte maps under parallel ticking via mixins.
 */
public class Long2ByteConcurrentHashMap implements Long2ByteMap {

    private final Map<Long, Byte> backing;
    private byte defaultReturn = 0;

    public Long2ByteConcurrentHashMap() {
        backing = new ConcurrentHashMap<>();
    }

    public Long2ByteConcurrentHashMap(int initialCapacity, float loadFactor) {
        backing = new ConcurrentHashMap<>(initialCapacity, loadFactor);
    }

    @Override
    public byte get(long key) {
        Byte out = backing.get(key);
        return out == null ? defaultReturn : out;
    }

    @Override
    public boolean isEmpty() {
        return backing.isEmpty();
    }

    @Override
    public boolean containsValue(byte value) {
        return backing.containsValue(value);
    }

    @Override
    public void putAll(@NotNull Map<? extends Long, ? extends Byte> m) {
        backing.putAll(m);
    }

    @Override
    public int size() {
        return backing.size();
    }

    @Override
    public void defaultReturnValue(byte rv) {
        defaultReturn = rv;
    }

    @Override
    public byte defaultReturnValue() {
        return defaultReturn;
    }

    @Override
    public ObjectSet<Entry> long2ByteEntrySet() {
        return FastUtilViews.entrySetLongByteWrap(backing);
    }

    @Override
    public @NotNull LongSet keySet() {
        return FastUtilViews.wrapLongSet(backing.keySet());
    }

    @Override
    public @NotNull ByteCollection values() {
        return FastUtilViews.wrapBytes(backing.values());
    }

    @Override
    public boolean containsKey(long key) {
        return backing.containsKey(key);
    }

    @Override
    public byte put(long key, byte value) {
        Byte out = backing.put(key, value);
        return out == null ? defaultReturn : out;
    }

    @Override
    public Byte put(Long key, Byte value) {
        Byte out = backing.put(key, value);
        return out == null ? defaultReturn : out;
    }

    @Override
    public byte remove(long key) {
        Byte out = backing.remove(key);
        return out == null ? defaultReturn : out;
    }

    @Override
    public void clear() {
        backing.clear();
    }
}
