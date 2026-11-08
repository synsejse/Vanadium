package com.synsenetwork.vanadium.concurrent;

import it.unimi.dsi.fastutil.longs.Long2ObjectFunction;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.objects.ObjectCollection;
import it.unimi.dsi.fastutil.objects.ObjectSet;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;
import java.util.function.LongFunction;
import org.jetbrains.annotations.NotNull;

/**
 * Thread-safe {@link Long2ObjectMap} backed by a {@code ConcurrentHashMap}, used to replace
 * vanilla's internal long-to-object maps under parallel ticking via mixins.
 *
 * <p>Packed section and chunk positions fold badly under {@code Long.hashCode}: low X bits never
 * reach the bin index of a normal-sized table, so neighbouring sections share bins and the map
 * degrades into tree bins. Keys are therefore stored through fastutil's bijective
 * {@link MixedLongKeys#mix(long)}, and every boundary that exposes keys inverts it.
 */
public class Long2ObjectConcurrentHashMap<V> implements Long2ObjectMap<V> {

    private final Map<Long, V> backing = new ConcurrentHashMap<>();
    /** The map as callers see it: original keys. */
    private final Map<Long, V> unmixed = MixedLongKeys.unmixedView(backing);
    private V defaultReturn = null;

    private static long mix(long key) {
        return MixedLongKeys.mix(key);
    }

    public Long2ObjectConcurrentHashMap() {}

    @Override
    public V get(long key) {
        V out = backing.get(mix(key));
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
        m.forEach((k, v) -> backing.put(mix(k), v));
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
        return FastUtilViews.entrySetLongWrap(unmixed);
    }

    @Override
    public @NotNull LongSet keySet() {
        return FastUtilViews.wrapLongSet(unmixed.keySet());
    }

    @Override
    public @NotNull ObjectCollection<V> values() {
        return FastUtilViews.wrap(backing.values());
    }

    @Override
    public boolean containsKey(long key) {
        return backing.containsKey(mix(key));
    }

    @Override
    public V put(long key, V value) {
        V out = backing.put(mix(key), value);
        return out == null ? defaultReturn : out;
    }

    @Override
    public V put(Long key, V value) {
        V out = backing.put(mix(key), value);
        return out == null ? defaultReturn : out;
    }

    @Override
    public V remove(long key) {
        V out = backing.remove(mix(key));
        return out == null ? defaultReturn : out;
    }

    @Override
    public V putIfAbsent(long key, V value) {
        V out = backing.putIfAbsent(mix(key), value);
        return out == null ? defaultReturn : out;
    }

    @Override
    public V computeIfAbsent(long key, LongFunction<? extends V> mappingFunction) {
        return backing.computeIfAbsent(mix(key), k -> mappingFunction.apply(key));
    }

    @Override
    public V computeIfAbsent(long key, Long2ObjectFunction<? extends V> mappingFunction) {
        V out = backing.computeIfAbsent(mix(key), k -> {
            long unboxed = key;
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
        return backing.computeIfPresent(mix(key), (k, v) -> remappingFunction.apply(key, v));
    }

    @Override
    public V compute(long key, BiFunction<? super Long, ? super V, ? extends V> remappingFunction) {
        return backing.compute(mix(key), (k, v) -> remappingFunction.apply(key, v));
    }

    @Override
    public V merge(long key, V value, BiFunction<? super V, ? super V, ? extends V> remappingFunction) {
        return backing.merge(mix(key), value, remappingFunction);
    }

    @Override
    public void clear() {
        backing.clear();
    }
}
