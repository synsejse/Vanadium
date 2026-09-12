package com.synsenetwork.vanadium.concurrent;

import it.unimi.dsi.fastutil.longs.LongAVLTreeSet;
import it.unimi.dsi.fastutil.longs.LongSortedSet;
import org.junit.jupiter.api.Test;

import java.util.NoSuchElementException;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class ConcurrentLongSortedSetTest {
    private ConcurrentLongSortedSet populated() {
        var set = new ConcurrentLongSortedSet();
        for (long value : new long[]{Long.MIN_VALUE, -10, -1, 0, 1, 10, Long.MAX_VALUE}) {
            set.add(value);
        }
        return set;
    }

    @Test
    void rangeBoundsAndOrderingMatchSortedSetContract() {
        var set = populated();
        assertArrayEquals(new long[]{-10, -1, 0, 1}, set.subSet(-10, 10).toLongArray());
        assertArrayEquals(new long[]{Long.MIN_VALUE, -10, -1}, set.headSet(0).toLongArray());
        assertArrayEquals(new long[]{10, Long.MAX_VALUE}, set.tailSet(10).toLongArray());
        assertTrue(set.subSet(1, 1).isEmpty());
        assertThrows(IllegalArgumentException.class, () -> set.subSet(10, -10));
        var expected = new LongAVLTreeSet(new long[]{-10, -1, 0, 1});
        var range = set.subSet(-10, 10);
        assertEquals(expected, range);
        assertEquals(range, expected);
        assertEquals(expected.hashCode(), range.hashCode());
    }

    @Test
    void rangesAreBoundedLiveViews() {
        var set = populated();
        LongSortedSet range = set.subSet(-10, 10);
        set.add(2);
        assertTrue(range.contains(2));
        range.remove(-1);
        assertFalse(set.contains(-1));
        range.add(3);
        assertTrue(set.contains(3));
        assertThrows(IllegalArgumentException.class, () -> range.add(10));
        assertThrows(IllegalArgumentException.class, () -> range.add(-11));
        range.tailSet(0).headSet(3).clear();
        assertArrayEquals(new long[]{Long.MIN_VALUE, -10, 3, 10, Long.MAX_VALUE}, set.toLongArray());
    }

    @Test
    void iteratorRemovesFromBackingSetAndStopsAtUpperBound() {
        var set = populated();
        var iterator = set.subSet(-1, 1).iterator();
        assertEquals(-1, iterator.nextLong());
        iterator.remove();
        assertFalse(set.contains(-1));
        assertEquals(0, iterator.nextLong());
        assertFalse(iterator.hasNext());
    }

    @Test
    void positionedIteratorStartsStrictlyAfterItsArgument() {
        var range = populated().subSet(-10, 10);
        assertEquals(0, range.iterator(-1).nextLong());
        assertEquals(-10, range.iterator(Long.MIN_VALUE).nextLong());
        assertFalse(range.iterator(Long.MAX_VALUE).hasNext());
        assertFalse(range.subSet(0, 0).iterator().hasNext());
    }

    @Test
    void iteratorCanChangeDirectionAndRemoveFromEitherSide() {
        var set = populated();
        var iterator = set.subSet(-10, 10).iterator(-1);
        assertEquals(-1, iterator.previousLong());
        assertEquals(-1, iterator.nextLong());
        assertEquals(0, iterator.nextLong());
        assertEquals(0, iterator.previousLong());
        iterator.remove();
        assertFalse(set.contains(0));
        assertThrows(IllegalStateException.class, iterator::remove);
        assertEquals(1, iterator.nextLong());
        iterator.remove();
        assertFalse(set.contains(1));
        assertFalse(iterator.hasNext());
        assertEquals(-1, iterator.previousLong());
        assertEquals(-10, iterator.previousLong());
        assertFalse(iterator.hasPrevious());
        assertThrows(NoSuchElementException.class, iterator::previousLong);
        assertEquals(-10, iterator.nextLong());
    }

    @Test
    void positionedIteratorClipsToRangeForReverseTraversal() {
        var range = populated().subSet(-10, 10);
        var iterator = range.iterator(Long.MAX_VALUE);
        assertFalse(iterator.hasNext());
        assertEquals(1, iterator.previousLong());
        assertEquals(0, iterator.previousLong());
        assertEquals(-1, iterator.previousLong());
        assertEquals(-10, iterator.previousLong());
        assertFalse(iterator.hasPrevious());
        assertFalse(range.iterator(Long.MIN_VALUE).hasPrevious());
        assertFalse(range.subSet(0, 0).iterator().hasPrevious());
    }

    @Test
    void cursorOperationsMatchFastutilSortedSet() {
        Random random = new Random(12345);
        for (int trial = 0; trial < 20; trial++) {
            var actual = new ConcurrentLongSortedSet();
            var expected = new LongAVLTreeSet();
            for (int key = -20; key <= 20; key++) {
                actual.add(key);
                expected.add(key);
            }
            int start = random.nextInt(61) - 30;
            var a = actual.subSet(-10, 10).iterator(start);
            var e = expected.subSet(-10, 10).iterator(start);
            boolean canRemove = false;
            for (int step = 0; step < 300; step++) {
                assertEquals(e.hasNext(), a.hasNext());
                assertEquals(e.hasPrevious(), a.hasPrevious());
                switch (random.nextInt(3)) {
                    case 0 -> {
                        if (e.hasNext()) {
                            assertEquals(e.nextLong(), a.nextLong());
                            canRemove = true;
                        }
                    }
                    case 1 -> {
                        if (e.hasPrevious()) {
                            assertEquals(e.previousLong(), a.previousLong());
                            canRemove = true;
                        }
                    }
                    case 2 -> {
                        if (canRemove) {
                            e.remove();
                            a.remove();
                            canRemove = false;
                            assertArrayEquals(expected.toLongArray(), actual.toLongArray());
                        }
                    }
                }
            }
        }
    }

    @Test
    void forwardIterationToleratesConcurrentRangeMutation() throws Exception {
        var set = new ConcurrentLongSortedSet();
        for (int i = -100; i < 100; i++) {
            set.add(i);
        }
        var range = set.subSet(-50, 50);
        var iterator = range.iterator();
        CountDownLatch start = new CountDownLatch(1);
        var executor = Executors.newSingleThreadExecutor();
        try {
            var writer = executor.submit(() -> {
                assertTrue(start.await(5, TimeUnit.SECONDS));
                for (int repeat = 0; repeat < 100; repeat++) {
                    for (long i = -100; i < 100; i++) {
                        set.remove(i);
                        set.add(i);
                    }
                }
                return null;
            });
            start.countDown();
            long previous = Long.MIN_VALUE;
            while (iterator.hasNext()) {
                long value = iterator.nextLong();
                assertTrue(value >= -50 && value < 50);
                assertTrue(value > previous, "iteration must remain ordered without duplicates");
                previous = value;
            }
            writer.get(5, TimeUnit.SECONDS);
            assertEquals(100, range.size());
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }
}
