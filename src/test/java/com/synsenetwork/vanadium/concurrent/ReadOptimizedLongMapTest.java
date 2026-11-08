package com.synsenetwork.vanadium.concurrent;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ReadOptimizedLongMapTest {
    private record Value(long key) {}

    @Test void putGetRemoveAndReuseTombstones() {
        ReadOptimizedLongMap<Value> map = new ReadOptimizedLongMap<>();
        for (long key = -500; key < 500; key++) map.put(key, new Value(key));
        assertEquals(1000, map.size());
        for (long key = -500; key < 500; key++) assertEquals(key, map.get(key).key());
        assertNull(map.get(10_000));

        Value replaced = new Value(7);
        map.put(7, replaced);
        assertSame(replaced, map.get(7));
        assertFalse(map.remove(7, new Value(7)), "removal must match the current value by identity");
        assertTrue(map.remove(7, replaced));
        assertNull(map.get(7));

        for (long key = -500; key < 500; key++) map.remove(key, map.get(key));
        assertEquals(0, map.size());
        for (int round = 0; round < 50; round++) { // Tombstone churn must not exhaust the table.
            for (long key = 0; key < 200; key++) map.put(key * 31 + round, new Value(key * 31 + round));
            for (long key = 0; key < 200; key++) assertTrue(map.remove(key * 31 + round, map.get(key * 31 + round)));
        }
        assertEquals(0, map.size());
        map.put(Long.MIN_VALUE, new Value(Long.MIN_VALUE));
        assertEquals(Long.MIN_VALUE, map.get(Long.MIN_VALUE).key());
    }

    @Test void concurrentReadersSeeOnlyMatchingValuesOrMisses() throws Exception {
        ReadOptimizedLongMap<Value> map = new ReadOptimizedLongMap<>();
        AtomicBoolean stop = new AtomicBoolean();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(4);
        for (int r = 0; r < 4; r++) {
            Thread.ofPlatform().daemon().start(() -> {
                try {
                    long probes = 0;
                    while (!stop.get() || probes < 100_000) {
                        long key = probes++ % 4096;
                        Value value = map.get(key);
                        if (value != null && value.key() != key) throw new AssertionError("torn read for " + key);
                    }
                } catch (Throwable t) {
                    failure.compareAndSet(null, t);
                } finally {
                    done.countDown();
                }
            });
        }
        for (int round = 0; round < 200; round++) {
            for (long key = round % 2; key < 4096; key += 2) map.put(key, new Value(key));
            for (long key = round % 2; key < 4096; key += 4) map.remove(key, map.get(key));
        }
        stop.set(true);
        assertTrue(done.await(30, TimeUnit.SECONDS), "readers did not finish");
        assertNull(failure.get(), () -> "reader failed: " + failure.get());
    }
}
