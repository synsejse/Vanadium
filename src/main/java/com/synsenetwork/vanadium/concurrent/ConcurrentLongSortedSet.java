package com.synsenetwork.vanadium.concurrent;

import it.unimi.dsi.fastutil.longs.*;
import org.jetbrains.annotations.NotNull;

import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.NavigableSet;
import java.util.NoSuchElementException;
import java.util.concurrent.ConcurrentSkipListSet;

/**
 * Thread-safe {@link LongSortedSet} backed by a {@code ConcurrentSkipListSet}, used to replace
 * vanilla's internal sorted long sets under parallel ticking via mixins.
 * Range sets are live, bounded views; iterators are weakly consistent and support removal.
 * Minecraft's section queries only iterate forward and tolerate concurrently removed sections.
 */
public class ConcurrentLongSortedSet extends AbstractLongSortedSet {

    private final NavigableSet<Long> back;

    public ConcurrentLongSortedSet() {
        this(new ConcurrentSkipListSet<>());
    }

    private ConcurrentLongSortedSet(NavigableSet<Long> back) {
        this.back = back;
    }

    /**
     * Positions the cursor after {@code fromElement}, matching fastutil's iterator contract.
     */
    @Override
    public LongBidirectionalIterator iterator(long fromElement) {
        return new SetIterator(forwardFrom(back.higher(fromElement)), back.floor(fromElement));
    }

    /**
     * Traverses directly without copying the range.
     */
    @Override
    public @NotNull LongBidirectionalIterator iterator() {
        return new SetIterator(back.iterator(), null);
    }

    private Iterator<Long> forwardFrom(Long first) {
        return first == null ? Collections.emptyIterator() : back.tailSet(first, true).iterator();
    }

    /** Forward traversal uses the skip-list iterator; reverse traversal repositions it by key. */
    private final class SetIterator implements LongBidirectionalIterator {
        private Iterator<Long> forward;
        private Long previous;
        private Long lastReturned;
        private boolean movedForward;

        private SetIterator(Iterator<Long> forward, Long previous) {
            this.forward = forward;
            this.previous = previous;
        }

        @Override
        public boolean hasNext() {
            return forward.hasNext();
        }

        @Override
        public long nextLong() {
            lastReturned = forward.next();
            previous = lastReturned;
            movedForward = true;
            return lastReturned;
        }

        @Override
        public boolean hasPrevious() {
            return previous != null;
        }

        @Override
        public long previousLong() {
            if (previous == null) {
                throw new NoSuchElementException();
            }
            lastReturned = previous;
            previous = back.lower(lastReturned);
            forward = forwardFrom(lastReturned);
            movedForward = false;
            return lastReturned;
        }

        @Override
        public void remove() {
            if (lastReturned == null) {
                throw new IllegalStateException();
            }
            if (movedForward) {
                forward.remove();
                previous = back.lower(lastReturned);
            } else {
                back.remove(lastReturned);
                forward = forwardFrom(back.higher(lastReturned));
            }
            lastReturned = null;
        }
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
        return new ConcurrentLongSortedSet(back.subSet(fromElement, true, toElement, false));
    }

    @Override
    public LongSortedSet headSet(long toElement) {
        return new ConcurrentLongSortedSet(back.headSet(toElement, false));
    }

    @Override
    public LongSortedSet tailSet(long fromElement) {
        return new ConcurrentLongSortedSet(back.tailSet(fromElement, true));
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
