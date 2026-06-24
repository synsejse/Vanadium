package com.synsenetwork.vanadium.concurrent;

import it.unimi.dsi.fastutil.longs.Long2ObjectFunction;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.objects.ObjectCollection;
import it.unimi.dsi.fastutil.objects.ObjectSet;
import org.jetbrains.annotations.NotNull;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;
import java.util.function.LongFunction;

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
    public void putAll(@NotNull Map<? extends Long, ? extends V> m) {
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
    public @NotNull LongSet keySet() {
        return FastUtilViews.wrapLongSet(backing.keySet());
    }

    @Override
    public @NotNull ObjectCollection<V> values() {
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
    public V putIfAbsent(long key, V value) {
        V out = backing.putIfAbsent(key, value);
        return out == null ? defaultReturn : out;
    }

    @Override
    public V computeIfAbsent(long key, LongFunction<? extends V> mappingFunction) {
        return backing.computeIfAbsent(key, mappingFunction::apply);
    }

    @Override
    public V computeIfAbsent(long key, Long2ObjectFunction<? extends V> mappingFunction) {
        V out = backing.computeIfAbsent(key, k -> {
            long unboxed = k;
            return mappingFunction.containsKey(unboxed) ? mappingFunction.get(unboxed) : null;
        });
        return out == null ? defaultReturn : out;
    }

    @Override
    public V computeIfAbsentPartial(long key, Long2ObjectFunction<? extends V> mappingFunction) {
        return computeIfAbsent(key, mappingFunction);
    }

    @Override
    public V computeIfPresent(long key, BiFunction<? super Long, ? super V, ? extends V> remappingFunction) {
        return backing.computeIfPresent(key, remappingFunction);
    }

    @Override
    public V compute(long key, BiFunction<? super Long, ? super V, ? extends V> remappingFunction) {
        return backing.compute(key, remappingFunction);
    }

    @Override
    public V merge(long key, V value, BiFunction<? super V, ? super V, ? extends V> remappingFunction) {
        return backing.merge(key, value, remappingFunction);
    }

    @Override
    public void clear() {
        backing.clear();
    }
}
