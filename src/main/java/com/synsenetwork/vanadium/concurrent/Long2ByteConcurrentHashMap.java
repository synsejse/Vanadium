package com.synsenetwork.vanadium.concurrent;

import it.unimi.dsi.fastutil.bytes.ByteCollection;
import it.unimi.dsi.fastutil.longs.Long2ByteMap;
import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.objects.ObjectSet;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.jetbrains.annotations.NotNull;

/**
 * Thread-safe {@link Long2ByteMap} backed by a {@code ConcurrentHashMap}, used to replace
 * vanilla's internal long-to-byte maps under parallel ticking via mixins.
 */
public class Long2ByteConcurrentHashMap implements Long2ByteMap {

    private final Map<Long, Byte> backing;
    /** Keys are stored mixed; see {@link MixedLongKeys}. */
    private final Map<Long, Byte> unmixed;
    private byte defaultReturn = 0;

    public Long2ByteConcurrentHashMap() {
        backing = new ConcurrentHashMap<>();
        unmixed = MixedLongKeys.unmixedView(backing);
    }

    public Long2ByteConcurrentHashMap(int initialCapacity, float loadFactor) {
        backing = new ConcurrentHashMap<>(initialCapacity, loadFactor);
        unmixed = MixedLongKeys.unmixedView(backing);
    }

    @Override
    public byte get(long key) {
        Byte out = backing.get(MixedLongKeys.mix(key));
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
        m.forEach((k, v) -> backing.put(MixedLongKeys.mix(k), v));
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
        return FastUtilViews.entrySetLongByteWrap(unmixed);
    }

    @Override
    public @NotNull LongSet keySet() {
        return FastUtilViews.wrapLongSet(unmixed.keySet());
    }

    @Override
    public @NotNull ByteCollection values() {
        return FastUtilViews.wrapBytes(backing.values());
    }

    @Override
    public boolean containsKey(long key) {
        return backing.containsKey(MixedLongKeys.mix(key));
    }

    @Override
    public byte put(long key, byte value) {
        Byte out = backing.put(MixedLongKeys.mix(key), value);
        return out == null ? defaultReturn : out;
    }

    @Override
    public Byte put(Long key, Byte value) {
        Byte out = backing.put(MixedLongKeys.mix(key), value);
        return out == null ? defaultReturn : out;
    }

    @Override
    public byte remove(long key) {
        Byte out = backing.remove(MixedLongKeys.mix(key));
        return out == null ? defaultReturn : out;
    }

    /** Visits every mapping with original keys, without entry views or boxing of the visitor arguments. */
    public void forEachEntry(EntryConsumer consumer) {
        backing.forEach((key, value) -> consumer.accept(MixedLongKeys.unmix(key), value));
    }

    @FunctionalInterface
    public interface EntryConsumer {
        void accept(long key, byte value);
    }

    @Override
    public void clear() {
        backing.clear();
    }
}
