package com.synsenetwork.vanadium.concurrent;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class ConcurrentWrappersTest {

    // ---- Int2ObjectConcurrentHashMap ----------------------------------------

    @Test
    void int2ObjectMap_putReturnsPreviousValue() {
        var map = new Int2ObjectConcurrentHashMap<String>();
        assertNull(map.put(1, "a"),                   "first put: key absent → default (null)");
        assertEquals("a", map.put(1, "b"),            "second put: key present → old value");
        assertEquals("b", map.get(1),                 "get returns current value");
    }

    @Test
    void int2ObjectMap_defaultReturnValue() {
        var map = new Int2ObjectConcurrentHashMap<String>();
        map.defaultReturnValue("MISSING");
        assertEquals("MISSING", map.get(99),          "absent key → defaultReturnValue");
        assertEquals("MISSING", map.put(5, "x"),      "first put → defaultReturnValue");
        assertEquals("MISSING", map.remove(99),       "remove absent key → defaultReturnValue");
    }

    @Test
    void int2ObjectMap_sizeContainsKeyRemove() {
        var map = new Int2ObjectConcurrentHashMap<String>();
        map.put(10, "hello");
        assertEquals(1, map.size());
        assertTrue(map.containsKey(10));
        assertEquals("hello", map.remove(10));
        assertFalse(map.containsKey(10));
        assertEquals(0, map.size());
    }

    // ---- Long2ObjectConcurrentHashMap ----------------------------------------

    @Test
    void long2ObjectMap_putReturnsPreviousValue() {
        var map = new Long2ObjectConcurrentHashMap<String>();
        assertNull(map.put(1L, "a"),                  "first put: key absent → default (null)");
        assertEquals("a", map.put(1L, "b"),           "second put: key present → old value");
        assertEquals("b", map.get(1L));
    }

    @Test
    void long2ObjectMap_defaultReturnValue() {
        var map = new Long2ObjectConcurrentHashMap<String>();
        map.defaultReturnValue("N/A");
        assertEquals("N/A", map.get(42L));
        assertEquals("N/A", map.put(7L, "v"));
        assertEquals("N/A", map.remove(999L));
    }

    @Test
    void long2ObjectMap_sizeContainsKeyRemove() {
        var map = new Long2ObjectConcurrentHashMap<Integer>();
        map.put(100L, (Integer) 42);
        assertEquals(1, map.size());
        assertTrue(map.containsKey(100L));
        assertEquals(42, map.remove(100L));
        assertEquals(0, map.size());
    }

    // ---- Long2ByteConcurrentHashMap ------------------------------------------

    @Test
    void long2ByteMap_putReturnsPreviousValue() {
        var map = new Long2ByteConcurrentHashMap();
        assertEquals((byte) 0, map.put(1L, (byte) 5), "first put: key absent → default (0)");
        assertEquals((byte) 5, map.put(1L, (byte) 9), "second put: key present → old value");
        assertEquals((byte) 9, map.get(1L));
    }

    @Test
    void long2ByteMap_defaultReturnValueAndRemove() {
        var map = new Long2ByteConcurrentHashMap();
        map.defaultReturnValue((byte) -1);
        assertEquals((byte) -1, map.get(77L));
        map.put(77L, (byte) 3);
        assertEquals((byte) 3, map.remove(77L));
        assertEquals((byte) -1, map.remove(77L));
    }

    // ---- Long2ObjectOpenConcurrentHashMap ------------------------------------

    @Test
    void long2ObjectOpenMap_putReturnsPreviousValue() {
        var map = new Long2ObjectOpenConcurrentHashMap<String>();
        assertNull(map.put(1L, "a"),                  "first put: key absent → default (null)");
        assertEquals("a", map.put(1L, "b"),           "second put: key present → old value");
        assertEquals("b", map.get(1L));
    }

    @Test
    void long2ObjectOpenMap_defaultReturnAndRemove() {
        var map = new Long2ObjectOpenConcurrentHashMap<String>();
        map.defaultReturnValue("?");
        assertEquals("?", map.get(0L));
        map.put(0L, "zero");
        assertEquals("zero", map.remove(0L));
        assertEquals("?", map.remove(0L));
    }

    @Test
    void long2ObjectOpenMap_cloneThrows() {
        var map = new Long2ObjectOpenConcurrentHashMap<String>();
        assertThrows(UnsupportedOperationException.class, map::clone);
    }

    // ---- ConcurrentShortHashSet ----------------------------------------------

    @Test
    void shortHashSet_addContainsRemove() {
        var set = new ConcurrentShortHashSet();
        assertTrue(set.add((short) 7));
        assertFalse(set.add((short) 7),   "duplicate → false");
        assertTrue(set.contains((short) 7));
        assertEquals(1, set.size());
        assertTrue(set.remove((short) 7));
        assertFalse(set.contains((short) 7));
    }

    @Test
    void shortHashSet_toShortArrayRoundTrip() {
        var set = new ConcurrentShortHashSet();
        set.add((short) 1);
        set.add((short) 2);
        set.add((short) 3);
        short[] arr = set.toShortArray();
        assertEquals(3, arr.length);
        // values are present (order not guaranteed)
        long sum = 0;
        for (short s : arr) sum += s;
        assertEquals(6, sum);
    }

    // ---- ConcurrentLongSortedSet ---------------------------------------------

    @Test
    void longSortedSet_addContainsRemove() {
        var set = new ConcurrentLongSortedSet();
        assertTrue(set.add(42L));
        assertFalse(set.add(42L),         "duplicate → false");
        assertTrue(set.contains(42L));
        assertEquals(1, set.size());
        assertTrue(set.remove(42L));
        assertFalse(set.contains(42L));
    }

    @Test
    void longSortedSet_toLongArrayRoundTrip() {
        var set = new ConcurrentLongSortedSet();
        set.add(10L);
        set.add(20L);
        set.add(30L);
        long[] arr = set.toLongArray();
        assertEquals(3, arr.length);
        // ConcurrentSkipListSet is sorted ascending
        assertEquals(10L, arr[0]);
        assertEquals(20L, arr[1]);
        assertEquals(30L, arr[2]);
    }

    @Test
    void longSortedSet_firstLast() {
        var set = new ConcurrentLongSortedSet();
        set.add(5L);
        set.add(1L);
        set.add(9L);
        assertEquals(1L, set.firstLong());
        assertEquals(9L, set.lastLong());
    }

    @Test
    void longSortedSet_iteratorThrows() {
        var set = new ConcurrentLongSortedSet();
        assertThrows(UnsupportedOperationException.class, set::iterator);
    }

    // ---- ConcurrentLongLinkedOpenHashSet -------------------------------------

    @Test
    void longLinkedOpenHashSet_addContainsRemove() {
        var set = new ConcurrentLongLinkedOpenHashSet();
        assertTrue(set.add(100L));
        assertFalse(set.add(100L),        "duplicate → false");
        assertTrue(set.contains(100L));
        assertEquals(1, set.size());
        assertTrue(set.remove(100L));
        assertFalse(set.contains(100L));
    }

    @Test
    void longLinkedOpenHashSet_toLongArrayRoundTrip() {
        var set = new ConcurrentLongLinkedOpenHashSet();
        set.add(3L);
        set.add(1L);
        set.add(2L);
        long[] arr = set.toLongArray();
        assertEquals(3, arr.length);
        // ConcurrentSkipListSet is sorted ascending
        assertEquals(1L, arr[0]);
        assertEquals(2L, arr[1]);
        assertEquals(3L, arr[2]);
    }

    @Test
    void longLinkedOpenHashSet_cloneIsAnIndependentCopy() {
        var set = new ConcurrentLongLinkedOpenHashSet();
        set.add(1L);
        set.add(2L);
        var copy = set.clone();
        assertEquals(2, copy.size());
        assertTrue(copy.contains(1L));
        set.add(3L);
        assertEquals(2, copy.size()); // copy is a snapshot, not a live view
    }

    // ---- Long2ObjectConcurrentHashMap atomic compute family ------------------

    @Test
    void long2ObjectMap_computeIfAbsentIsAtomic() throws InterruptedException {
        // Vanilla's SectionedEntityCache.getTrackingSection relies on this: under contention the
        // mapping function must run at most once per key, or duplicate sections would be created.
        var map = new Long2ObjectConcurrentHashMap<String>();
        AtomicInteger factoryCalls = new AtomicInteger();

        int threads = 16;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        var results = new java.util.concurrent.ConcurrentLinkedQueue<String>();
        for (int t = 0; t < threads; t++) {
            pool.submit(() -> {
                ready.countDown();
                try {
                    go.await();
                } catch (InterruptedException ignored) {
                }
                results.add(map.computeIfAbsent(42L, k -> {
                    factoryCalls.incrementAndGet();
                    return "v" + k;
                }));
            });
        }
        assertTrue(ready.await(5, TimeUnit.SECONDS));
        go.countDown(); // release all threads at once to maximise the race
        pool.shutdown();
        assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));

        assertEquals(1, factoryCalls.get(), "mapping function ran more than once → not atomic");
        assertEquals("v42", map.get(42L));
        assertTrue(results.stream().allMatch("v42"::equals), "all callers must see the same value");
    }

    @Test
    void long2ObjectMap_computeFamilyDelegatesCorrectly() {
        var map = new Long2ObjectConcurrentHashMap<String>();
        assertEquals("v1", map.computeIfAbsent(1L, k -> "v" + k));
        assertEquals("v1", map.computeIfAbsent(1L, k -> "should-not-run"));
        assertEquals("v1!", map.computeIfPresent(1L, (k, v) -> v + "!"));
        assertEquals("x", map.compute(2L, (k, v) -> "x"));
        assertNull(map.putIfAbsent(3L, "a")); // key absent → returns the default (null) and inserts "a"
        assertEquals("a", map.get(3L));
        assertEquals("ab", map.merge(3L, "b", (oldV, newV) -> oldV + newV));
    }

    // ---- Int2ObjectConcurrentHashMap atomic compute family -------------------

    @Test
    void int2ObjectMap_computeIfAbsentIsAtomic() throws InterruptedException {
        var map = new Int2ObjectConcurrentHashMap<String>();
        AtomicInteger factoryCalls = new AtomicInteger();

        int threads = 16;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        for (int t = 0; t < threads; t++) {
            pool.submit(() -> {
                ready.countDown();
                try {
                    go.await();
                } catch (InterruptedException ignored) {
                }
                map.computeIfAbsent(7, k -> {
                    factoryCalls.incrementAndGet();
                    return "v" + k;
                });
            });
        }
        assertTrue(ready.await(5, TimeUnit.SECONDS));
        go.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));

        assertEquals(1, factoryCalls.get(), "mapping function ran more than once → not atomic");
        assertEquals("v7", map.get(7));
    }

    // ---- Long2ObjectOpenConcurrentHashMap ------------------------------------

    @Test
    void long2ObjectOpenMap_computeIfAbsentHitsBackingAndIsAtomic() {
        // This subclass *extends* Long2ObjectOpenHashMap but delegates everything to a ConcurrentHashMap.
        // The un-overridden computeIfAbsent(long, Long2ObjectFunction) would run against the unused
        // superclass open-hash storage, so the value would never reach the backing get() reads.
        var map = new Long2ObjectOpenConcurrentHashMap<String>();
        AtomicInteger factoryCalls = new AtomicInteger();

        String first = map.computeIfAbsent(5L, k -> {
            factoryCalls.incrementAndGet();
            return "v" + k;
        });
        assertEquals("v5", first);
        assertEquals("v5", map.get(5L), "value must land in the concurrent backing, not superclass storage");

        String second = map.computeIfAbsent(5L, k -> {
            factoryCalls.incrementAndGet();
            return "should-not-run";
        });
        assertEquals("v5", second);
        assertEquals(1, factoryCalls.get(), "second computeIfAbsent recomputed → it was not reading the backing");
    }
}
