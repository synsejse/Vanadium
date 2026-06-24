package com.synsenetwork.vanadium.concurrent;

import it.unimi.dsi.fastutil.longs.*;
import org.jetbrains.annotations.NotNull;

import java.util.Collection;
import java.util.concurrent.ConcurrentSkipListSet;

/**
 * Thread-safe {@link LongSortedSet} backed by a {@code ConcurrentSkipListSet}, used to replace
 * vanilla's internal sorted long sets under parallel ticking via mixins.
 */
public class ConcurrentLongSortedSet implements LongSortedSet {

    private final ConcurrentSkipListSet<Long> back = new ConcurrentSkipListSet<>();

    /**
     * Bidirectional iteration over a {@code ConcurrentSkipListSet} is not supported because
     * the underlying skip-list iterator is forward-only and does not expose a stable snapshot
     * safe for backwards traversal under concurrent mutation.
     */
    @Override
    public LongBidirectionalIterator iterator(long fromElement) {
        throw new UnsupportedOperationException("Bidirectional iteration is not supported on ConcurrentLongSortedSet");
    }

    /**
     * Bidirectional iteration over a {@code ConcurrentSkipListSet} is not supported.
     */
    @Override
    public @NotNull LongBidirectionalIterator iterator() {
        throw new UnsupportedOperationException("Bidirectional iteration is not supported on ConcurrentLongSortedSet");
    }

    @Override
    public int size() {
        return back.size();
    }

    @Override
    public boolean isEmpty() {
        return back.isEmpty();
    }

    @NotNull
    @Override
    public Object @NotNull [] toArray() {
        return back.toArray();
    }

    @NotNull
    @Override
    public <T> T @NotNull [] toArray(@NotNull T @NotNull [] ts) {
        return back.toArray(ts);
    }

    @Override
    public boolean containsAll(@NotNull Collection<?> collection) {
        return back.containsAll(collection);
    }

    @Override
    public boolean addAll(@NotNull Collection<? extends Long> collection) {
        return back.addAll(collection);
    }

    @Override
    public boolean removeAll(@NotNull Collection<?> collection) {
        return back.removeAll(collection);
    }

    @Override
    public boolean retainAll(@NotNull Collection<?> collection) {
        return back.retainAll(collection);
    }

    @Override
    public void clear() {
        back.clear();
    }

    @Override
    public boolean add(long key) {
        return back.add(key);
    }

    @Override
    public boolean contains(long key) {
        return back.contains(key);
    }

    @Override
    public long[] toLongArray() {
        return back.stream().mapToLong(Long::longValue).toArray();
    }

    @Override
    public long[] toArray(long[] a) {
        long[] src = toLongArray();
        if (a.length >= src.length) {
            System.arraycopy(src, 0, a, 0, src.length);
            return a;
        }
        return src;
    }

    @Override
    public boolean addAll(LongCollection c) {
        return back.addAll(c);
    }

    @Override
    public boolean containsAll(LongCollection c) {
        return back.containsAll(c);
    }

    @Override
    public boolean removeAll(LongCollection c) {
        return back.removeAll(c);
    }

    @Override
    public boolean retainAll(LongCollection c) {
        return back.retainAll(c);
    }

    @Override
    public boolean remove(long k) {
        return back.remove(k);
    }

    @Override
    public LongSortedSet subSet(long fromElement, long toElement) {
        return new LongAVLTreeSet(back.subSet(fromElement, toElement));
    }

    @Override
    public LongSortedSet headSet(long toElement) {
        return new LongAVLTreeSet(back.headSet(toElement));
    }

    @Override
    public LongSortedSet tailSet(long fromElement) {
        return new LongAVLTreeSet(back.tailSet(fromElement));
    }

    /**
     * Returns {@code null} per the {@link java.util.SortedSet} contract, indicating natural
     * (ascending) ordering — which is what {@link ConcurrentSkipListSet} uses.
     */
    @Override
    public LongComparator comparator() {
        return null;
    }

    @Override
    public long firstLong() {
        return back.first();
    }

    @Override
    public long lastLong() {
        return back.last();
    }
}
