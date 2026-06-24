package com.synsenetwork.vanadium.concurrent;

import java.io.Serial;

import java.util.Collection;
import java.util.Iterator;
import java.util.concurrent.ConcurrentSkipListSet;

import it.unimi.dsi.fastutil.longs.LongArrays;
import it.unimi.dsi.fastutil.longs.LongCollection;
import it.unimi.dsi.fastutil.longs.LongComparator;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongIterators;
import it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongListIterator;
import it.unimi.dsi.fastutil.longs.LongSortedSet;
import org.jetbrains.annotations.NotNull;

/**
 * Thread-safe {@link LongLinkedOpenHashSet} backed by a {@code ConcurrentSkipListSet}, used to
 * replace vanilla's internal long linked-open-hash sets under parallel ticking via mixins.
 */
public class ConcurrentLongLinkedOpenHashSet extends LongLinkedOpenHashSet {

    @Serial
    private static final long serialVersionUID = -5532128240738069111L;

    private final ConcurrentSkipListSet<Long> backing;

    public ConcurrentLongLinkedOpenHashSet() {
        backing = new ConcurrentSkipListSet<>();
    }

    public ConcurrentLongLinkedOpenHashSet(final int initial) {
        backing = new ConcurrentSkipListSet<>();
    }

    public ConcurrentLongLinkedOpenHashSet(final int initial, final float dnc) {
        this(initial);
    }

    public ConcurrentLongLinkedOpenHashSet(final LongCollection c) {
        this(c.size());
        addAll(c);
    }

    public ConcurrentLongLinkedOpenHashSet(final LongCollection c, final float f) {
        this(c.size(), f);
        addAll(c);
    }

    public ConcurrentLongLinkedOpenHashSet(final LongIterator i, final float f) {
        this(16, f);
        while (i.hasNext())
            add(i.nextLong());
    }

    public ConcurrentLongLinkedOpenHashSet(final LongIterator i) {
        this(i, DEFAULT_LOAD_FACTOR);
    }

    public ConcurrentLongLinkedOpenHashSet(final Iterator<?> i, final float f) {
        this(LongIterators.asLongIterator(i), f);
    }

    public ConcurrentLongLinkedOpenHashSet(final Iterator<?> i) {
        this(LongIterators.asLongIterator(i));
    }

    public ConcurrentLongLinkedOpenHashSet(final long[] a, final int offset, final int length, final float f) {
        this(Math.max(length, 0), f);
        LongArrays.ensureOffsetLength(a, offset, length);
        for (int i = 0; i < length; i++)
            add(a[offset + i]);
    }

    public ConcurrentLongLinkedOpenHashSet(final long[] a, final int offset, final int length) {
        this(a, offset, length, DEFAULT_LOAD_FACTOR);
    }

    public ConcurrentLongLinkedOpenHashSet(final long[] a, final float f) {
        this(a, 0, a.length, f);
    }

    public ConcurrentLongLinkedOpenHashSet(final long[] a) {
        this(a, DEFAULT_LOAD_FACTOR);
    }

    @Override
    public boolean add(final long k) {
        return backing.add(k);
    }

    @Override
    public boolean addAll(LongCollection c) {
        return addAll((Collection<Long>) c);
    }

    @Override
    public boolean addAll(Collection<? extends Long> c) {
        return backing.addAll(c);
    }

    @Override
    public boolean addAndMoveToFirst(final long k) {
        return backing.add(k);
    }

    @Override
    public boolean addAndMoveToLast(final long k) {
        return backing.add(k);
    }

    @Override
    public void clear() {
        backing.clear();
    }

    @Override
    public LongLinkedOpenHashSet clone() {
        // Snapshot copy via the weakly-consistent backing iterator (intentionally not super.clone()).
        return new ConcurrentLongLinkedOpenHashSet(backing.iterator());
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
    public boolean contains(final long k) {
        return backing.contains(k);
    }

    @Override
    public long firstLong() {
        return backing.first();
    }

    @Override
    public int hashCode() {
        return backing.hashCode();
    }

    @Override
    public LongSortedSet headSet(long to) {
        throw new UnsupportedOperationException("headSet is not supported on ConcurrentLongLinkedOpenHashSet");
    }

    @Override
    public boolean isEmpty() {
        return backing.isEmpty();
    }

    @Override
    public @NotNull LongListIterator iterator() {
        return FastUtilViews.wrap(backing.iterator());
    }

    /**
     * Positional iteration from a given element is not supported because the underlying
     * skip-list iterator provides no stable position-based API under concurrent mutation.
     */
    @Override
    public LongListIterator iterator(long from) {
        throw new UnsupportedOperationException("Positional iterator is not supported on ConcurrentLongLinkedOpenHashSet");
    }

    @Override
    public long lastLong() {
        return backing.last();
    }

    @Override
    public boolean remove(final long k) {
        return backing.remove(k);
    }

    @Override
    public long removeFirstLong() {
        long fl = firstLong();
        backing.remove(fl);
        return fl;
    }

    @Override
    public long removeLastLong() {
        long ll = lastLong();
        backing.remove(ll);
        return ll;
    }

    @Override
    public int size() {
        return backing.size();
    }

    @Override
    public LongSortedSet subSet(long from, long to) {
        throw new UnsupportedOperationException("subSet is not supported on ConcurrentLongLinkedOpenHashSet");
    }

    @Override
    public LongSortedSet tailSet(long from) {
        throw new UnsupportedOperationException("tailSet is not supported on ConcurrentLongLinkedOpenHashSet");
    }

    @Override
    public boolean trim() {
        return true;
    }

    @Override
    public boolean trim(final int n) {
        return true;
    }

    @Override
    public long[] toLongArray() {
        return backing.stream().mapToLong(Long::longValue).toArray();
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
}
