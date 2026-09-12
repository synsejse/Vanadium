package com.synsenetwork.vanadium.chunk;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class CacheKeyTest {
    @Test
    void nearbyChunksDoNotCollapseIntoXorHashBuckets() {
        Map<Integer, Integer> buckets = new HashMap<>();
        for (int x = -16; x < 16; x++) {
            for (int z = -16; z < 16; z++) {
                long pos = (x & 0xFFFFFFFFL) | ((z & 0xFFFFFFFFL) << 32);
                var key = new ParallelChunkManager.CacheKey(pos, 0);
                buckets.merge(key.hashCode(), 1, Integer::sum);
            }
        }
        // The default record hash yields only 32 buckets, each holding 32 nearby chunks.
        assertTrue(buckets.size() > 900, "nearby chunks should have well-distributed hashes");
        assertTrue(buckets.values().stream().allMatch(size -> size < 8));
    }

    @Test
    void positionAndStatusBothIdentifyCachedChunks() {
        var first = new ParallelChunkManager.CacheKey(Long.MIN_VALUE, 1);
        var equal = new ParallelChunkManager.CacheKey(Long.MIN_VALUE, 1);
        var otherStatus = new ParallelChunkManager.CacheKey(Long.MIN_VALUE, 2);
        var otherPosition = new ParallelChunkManager.CacheKey(Long.MAX_VALUE, 1);
        Map<ParallelChunkManager.CacheKey, String> cache = new HashMap<>();
        cache.put(first, "first");
        cache.put(otherStatus, "status");
        cache.put(otherPosition, "position");
        assertEquals(first.hashCode(), equal.hashCode());
        assertEquals("first", cache.get(equal));
        assertEquals("status", cache.get(otherStatus));
        assertEquals("position", cache.get(otherPosition));
    }
}
