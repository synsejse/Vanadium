package com.synsenetwork.vanadium.tracking;

import net.minecraft.util.math.ChunkPos;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class AreaMapTest {
    @Test
    void movesAndResizesMatchFullSquareRebuilds() {
        for (int radius : new int[]{0, 1, 4}) {
            for (int dx : new int[]{-12, -2, -1, 0, 1, 2, 12}) {
                for (int dz : new int[]{-12, -1, 0, 1, 12}) {
                    for (int nextRadius : new int[]{0, 1, 4}) {
                        AreaMap<Object> map = new AreaMap<>();
                        Object object = new Object();
                        Bounds old = new Bounds(-3, -2, radius);
                        Bounds next = new Bounds(-3 + dx, -2 + dz, nextRadius);
                        map.add(object, old.x, old.z, old.radius);
                        map.update(object, next.x, next.z, next.radius);
                        assertCoverage(map, Map.of(object, next), -20, 20);
                        map.remove(object);
                        assertCoverage(map, Map.of(), -20, 20);
                    }
                }
            }
        }
    }

    @Test
    void randomizedAddMoveResizeAndRemovePreserveIdentityCoverage() {
        Random random = new Random(12345);
        AreaMap<Object> map = new AreaMap<>();
        Map<Object, Bounds> expected = new IdentityHashMap<>();
        // Equal-valued objects must still have independent membership.
        Object[] objects = {new String("same"), new String("same"), new Object(), new Object()};
        for (int step = 0; step < 1000; step++) {
            Object object = objects[random.nextInt(objects.length)];
            if (expected.containsKey(object) && random.nextInt(4) == 0) {
                map.remove(object);
                expected.remove(object);
            } else {
                Bounds bounds = new Bounds(random.nextInt(25) - 12, random.nextInt(25) - 12,
                        random.nextInt(5));
                if (expected.containsKey(object)) {
                    map.update(object, bounds.x, bounds.z, bounds.radius);
                } else {
                    map.add(object, bounds.x, bounds.z, bounds.radius);
                }
                expected.put(object, bounds);
            }
            assertCoverage(map, expected, -17, 17);
            for (Object candidate : objects) assertEquals(expected.containsKey(candidate), map.contains(candidate));
        }
        for (Object object : objects) map.remove(object);
        assertCoverage(map, Map.of(), -17, 17);
    }

    @Test
    void worldEdgeCoordinatesRemainDistinct() {
        for (int x : new int[]{-1_875_000, 1_875_000}) {
            AreaMap<Object> map = new AreaMap<>();
            Object object = new Object();
            map.add(object, x, -x, 1);
            map.update(object, x + 1, -x - 1, 1);
            for (int dx = -2; dx <= 3; dx++) {
                for (int dz = -3; dz <= 2; dz++) {
                    boolean expected = dx >= 0 && dx <= 2 && dz >= -2 && dz <= 0;
                    assertEquals(expected, map.objectsAt(ChunkPos.toLong(x + dx, -x + dz)).contains(object));
                }
            }
        }
    }

    private static void assertCoverage(AreaMap<Object> map, Map<Object, Bounds> expected, int min, int max) {
        // Rebuild the reference index from complete squares, independently of the incremental algorithm.
        Map<Long, IdentityHashMap<Object, Boolean>> coverage = new HashMap<>();
        expected.forEach((object, bounds) -> {
            for (int x = bounds.x - bounds.radius; x <= bounds.x + bounds.radius; x++) {
                for (int z = bounds.z - bounds.radius; z <= bounds.z + bounds.radius; z++) {
                    coverage.computeIfAbsent(ChunkPos.toLong(x, z), key -> new IdentityHashMap<>()).put(object, true);
                }
            }
        });
        for (int x = min; x <= max; x++) {
            for (int z = min; z <= max; z++) {
                long key = ChunkPos.toLong(x, z);
                var actual = map.objectsAt(key);
                var wanted = coverage.get(key);
                assertEquals(wanted == null ? 0 : wanted.size(), actual.size(), "coverage at " + x + "," + z);
                if (wanted != null) {
                    for (Object object : wanted.keySet()) assertTrue(actual.contains(object));
                }
            }
        }
    }

    private record Bounds(int x, int z, int radius) {}
}
