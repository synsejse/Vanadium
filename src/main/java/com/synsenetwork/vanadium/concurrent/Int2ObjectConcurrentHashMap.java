package com.synsenetwork.vanadium.concurrent;

import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.IntSet;
import it.unimi.dsi.fastutil.objects.ObjectCollection;
import it.unimi.dsi.fastutil.objects.ObjectSet;
import org.jetbrains.annotations.NotNull;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe {@link Int2ObjectMap} backed by a {@code ConcurrentHashMap}, used to replace
 * vanilla's internal int-to-object maps under parallel ticking via mixins.
 */
public class Int2ObjectConcurrentHashMap<V> implements Int2ObjectMap<V> {

    private final Map<Integer, V> backing = new ConcurrentHashMap<>();
    private V defaultReturn = null;

    public Int2ObjectConcurrentHashMap() {}

    @Override
    public V get(int key) {
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
    public void putAll(@NotNull Map<? extends Integer, ? extends V> m) {
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
    public ObjectSet<Entry<V>> int2ObjectEntrySet() {
        return FastUtilViews.entrySetIntWrap(backing);
    }

    @Override
    public @NotNull IntSet keySet() {
        return FastUtilViews.wrapIntSet(backing.keySet());
    }

    @Override
    public @NotNull ObjectCollection<V> values() {
        return FastUtilViews.wrap(backing.values());
    }

    @Override
    public boolean containsKey(int key) {
        return backing.containsKey(key);
    }

    @Override
    public V put(int key, V value) {
        V out = backing.put(key, value);
        return out == null ? defaultReturn : out;
    }

    @Override
    public V put(Integer key, V value) {
        V out = backing.put(key, value);
        return out == null ? defaultReturn : out;
    }

    @Override
    public V remove(int key) {
        V out = backing.remove(key);
        return out == null ? defaultReturn : out;
    }

    @Override
    public void clear() {
        backing.clear();
    }
}
