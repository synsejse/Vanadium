package com.synsenetwork.vanadium.concurrent;

import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MixedLongKeysTest {
    @Test void mixIsInvertible() {
        SplittableRandom random = new SplittableRandom(42);
        long[] edges = {0, 1, -1, Long.MIN_VALUE, Long.MAX_VALUE};
        for (long key : edges) assertEquals(key, MixedLongKeys.unmix(MixedLongKeys.mix(key)));
        for (int i = 0; i < 100_000; i++) {
            long key = random.nextLong();
            assertEquals(key, MixedLongKeys.unmix(MixedLongKeys.mix(key)));
        }
    }

    @Test void packedChunkKeysSpreadAcrossBins() {
        int radius = 32;
        int table = 8192;
        int[] bins = new int[table];
        for (int x = -radius; x < radius; x++) {
            for (int z = -radius; z < radius; z++) {
                long packed = (long) x & 0xFFFFFFFFL | ((long) z & 0xFFFFFFFFL) << 32;
                int h = Long.hashCode(MixedLongKeys.mix(packed));
                bins[(h ^ h >>> 16) & (table - 1)]++;
            }
        }
        int max = 0;
        for (int count : bins) max = Math.max(max, count);
        // ConcurrentHashMap converts a bin to a tree at eight entries.
        assertTrue(max < 8, "packed chunk keys clustered: " + max);
    }

    @Test void mapsExposeOriginalKeys() {
        var map = new Long2ObjectConcurrentHashMap<String>();
        map.put(-5L, "a");
        map.put(1L << 40, "b");
        assertEquals("a", map.get(-5L));
        assertTrue(map.keySet().contains(1L << 40));
        assertEquals(java.util.Set.of(-5L, 1L << 40), new java.util.HashSet<>(map.keySet()));
        map.long2ObjectEntrySet().forEach(entry -> assertEquals(map.get(entry.getLongKey()), entry.getValue()));
        var bytes = new Long2ByteConcurrentHashMap();
        bytes.defaultReturnValue((byte) 7);
        bytes.put(-3L, (byte) 2);
        assertEquals(2, bytes.get(-3L));
        assertEquals(7, bytes.get(99L));
        assertEquals(java.util.Set.of(-3L), new java.util.HashSet<>(bytes.keySet()));
    }
}
