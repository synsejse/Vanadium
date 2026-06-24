package com.synsenetwork.vanadium.concurrent;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.objects.ObjectCollection;
import it.unimi.dsi.fastutil.objects.ObjectSet;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe {@link Long2ObjectMap} backed by a {@code ConcurrentHashMap}, used to replace
 * vanilla's internal long-to-object maps under parallel ticking via mixins.
 */
public class Long2ObjectConcurrentHashMap<V> implements Long2ObjectMap<V> {

    private final Map<Long, V> backing = new ConcurrentHashMap<>();
    private V defaultReturn = null;

    public Long2ObjectConcurrentHashMap() {}

    @Override
    public V get(long key) {
        V out = backing.get(key);
        return out == null ? defaultReturn : out;
    }

    @Override
    public boolean isEmpty() {
        return backing.isEmpty();
    }

    @Override
    public boolean containsValue(Object value) {
        return backing.containsValue(value);
    }

    @Override
    public void putAll(Map<? extends Long, ? extends V> m) {
        backing.putAll(m);
    }

    @Override
    public int size() {
        return backing.size();
    }

    @Override
    public void defaultReturnValue(V rv) {
        defaultReturn = rv;
    }

    @Override
    public V defaultReturnValue() {
        return defaultReturn;
    }

    @Override
    public ObjectSet<Entry<V>> long2ObjectEntrySet() {
        return FastUtilViews.entrySetLongWrap(backing);
    }

    @Override
    public LongSet keySet() {
        return FastUtilViews.wrapLongSet(backing.keySet());
    }

    @Override
    public ObjectCollection<V> values() {
        return FastUtilViews.wrap(backing.values());
    }

    @Override
    public boolean containsKey(long key) {
        return backing.containsKey(key);
    }

    @Override
    public V put(long key, V value) {
        V out = backing.put(key, value);
        return out == null ? defaultReturn : out;
    }

    @Override
    public V put(Long key, V value) {
        V out = backing.put(key, value);
        return out == null ? defaultReturn : out;
    }

    @Override
    public V remove(long key) {
        V out = backing.remove(key);
        return out == null ? defaultReturn : out;
    }

    @Override
    public void clear() {
        backing.clear();
    }
}
