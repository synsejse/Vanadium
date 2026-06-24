package com.synsenetwork.vanadium.concurrent;

import it.unimi.dsi.fastutil.shorts.ShortCollection;
import it.unimi.dsi.fastutil.shorts.ShortIterator;
import it.unimi.dsi.fastutil.shorts.ShortSet;
import org.jetbrains.annotations.NotNull;

import java.util.Collection;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe {@link ShortSet} backed by a {@code ConcurrentHashMap.KeySetView}, used to replace
 * vanilla's internal short-keyed sets under parallel ticking via mixins.
 */
public class ConcurrentShortHashSet implements ShortSet {

    private final ConcurrentHashMap.KeySetView<Short, Boolean> backing = ConcurrentHashMap.newKeySet();

    @Override
    public int size() {
        return backing.size();
    }

    @Override
    public boolean isEmpty() {
        return backing.isEmpty();
    }

    @Override
    public @NotNull ShortIterator iterator() {
        return new FastUtilViews.WrappingShortIterator(backing.iterator());
    }

    @NotNull
    @Override
    public Object @NotNull [] toArray() {
        return backing.toArray();
    }

    @NotNull
    @Override
    public <T> T @NotNull [] toArray(@NotNull T @NotNull [] ts) {
        return backing.toArray(ts);
    }

    @Override
    public boolean containsAll(@NotNull Collection<?> collection) {
        return backing.containsAll(collection);
    }

    @Override
    public boolean addAll(@NotNull Collection<? extends Short> collection) {
        return backing.addAll(collection);
    }

    @Override
    public boolean removeAll(@NotNull Collection<?> collection) {
        return backing.removeAll(collection);
    }

    @Override
    public boolean retainAll(@NotNull Collection<?> collection) {
        return backing.retainAll(collection);
    }

    @Override
    public void clear() {
        backing.clear();
    }

    @Override
    public boolean add(short key) {
        return backing.add(key);
    }

    @Override
    public boolean contains(short key) {
        return backing.contains(key);
    }

    @Override
    public short[] toShortArray() {
        short[] result = new short[backing.size()];
        int i = 0;
        for (Short s : backing) {
            result[i++] = s;
        }
        return result;
    }

    @Override
    public short[] toArray(short[] a) {
        short[] src = toShortArray();
        if (a.length >= src.length) {
            System.arraycopy(src, 0, a, 0, src.length);
            return a;
        }
        return src;
    }

    @Override
    public boolean addAll(ShortCollection c) {
        return backing.addAll(c);
    }

    @Override
    public boolean containsAll(ShortCollection c) {
        return backing.containsAll(c);
    }

    @Override
    public boolean removeAll(ShortCollection c) {
        return backing.removeAll(c);
    }

    @Override
    public boolean retainAll(ShortCollection c) {
        return backing.retainAll(c);
    }

    @Override
    public boolean remove(short k) {
        return backing.remove(k);
    }
}
