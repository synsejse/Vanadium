package com.synsenetwork.vanadium.tracking

import net.minecraft.world.level.ChunkPos
import java.util.IdentityHashMap
import java.util.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AreaMapTest {
    private data class Bounds(val x: Int, val z: Int, val radius: Int)

    @Test
    fun movesAndResizesMatchFullSquareRebuilds() {
        for (radius in intArrayOf(0, 1, 4)) for (dx in intArrayOf(-12, -2, -1, 0, 1, 2, 12))
            for (dz in intArrayOf(-12, -1, 0, 1, 12)) for (nextRadius in intArrayOf(0, 1, 4)) {
                val map = AreaMap<Any>()
                val obj = Any()
                val next = Bounds(-3 + dx, -2 + dz, nextRadius)
                map.add(obj, -3, -2, radius)
                map.update(obj, next.x, next.z, next.radius)
                assertCoverage(map, mapOf(obj to next), -20, 20)
                map.remove(obj)
                assertCoverage(map, emptyMap(), -20, 20)
            }
    }

    @Test
    fun randomizedAddMoveResizeAndRemovePreserveIdentityCoverage() {
        val random = Random(12345)
        val map = AreaMap<Any>()
        val expected = IdentityHashMap<Any, Bounds>()
        // Equal-valued objects must still have independent membership.
        val objects = arrayOf<Any>(String(charArrayOf('s')), String(charArrayOf('s')), Any(), Any())
        repeat(1000) {
            val obj = objects[random.nextInt(objects.size)]
            if (obj in expected && random.nextInt(4) == 0) {
                map.remove(obj)
                expected.remove(obj)
            } else {
                val bounds = Bounds(random.nextInt(25) - 12, random.nextInt(25) - 12, random.nextInt(5))
                if (obj in expected) map.update(obj, bounds.x, bounds.z, bounds.radius)
                else map.add(obj, bounds.x, bounds.z, bounds.radius)
                expected[obj] = bounds
            }
            assertCoverage(map, expected, -17, 17)
            for (candidate in objects) assertEquals(candidate in expected, candidate in map)
        }
        for (obj in objects) map.remove(obj)
        assertCoverage(map, emptyMap(), -17, 17)
    }

    @Test
    fun worldEdgeCoordinatesRemainDistinct() {
        for (x in intArrayOf(-1_875_000, 1_875_000)) {
            val map = AreaMap<Any>()
            val obj = Any()
            map.add(obj, x, -x, 1)
            map.update(obj, x + 1, -x - 1, 1)
            for (dx in -2..3) for (dz in -3..2) {
                val expected = dx in 0..2 && dz in -2..0
                assertEquals(expected, obj in map.objectsAt(ChunkPos.pack(x + dx, -x + dz)))
            }
        }
    }

    /** Rebuilds the reference index from complete squares, independently of the incremental algorithm. */
    private fun assertCoverage(map: AreaMap<Any>, expected: Map<Any, Bounds>, min: Int, max: Int) {
        val coverage = HashMap<Long, MutableSet<Any>>()
        for ((obj, b) in expected) {
            for (x in b.x - b.radius..b.x + b.radius) for (z in b.z - b.radius..b.z + b.radius) {
                coverage.getOrPut(ChunkPos.pack(x, z)) { java.util.Collections.newSetFromMap(IdentityHashMap()) }.add(obj)
            }
        }
        for (x in min..max) for (z in min..max) {
            val actual = map.objectsAt(ChunkPos.pack(x, z))
            val wanted = coverage[ChunkPos.pack(x, z)].orEmpty()
            assertEquals(wanted.size, actual.size, "coverage at $x,$z")
            for (obj in wanted) assertTrue(obj in actual)
        }
    }
}
