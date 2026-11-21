package com.synsenetwork.vanadium.entity

import net.minecraft.core.BlockPos
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

class InsideBlockFastPathTest {
    @Test
    fun clipUnitBoxMatchesVanillaAabbClip() {
        val random = Random(263)
        var hits = 0
        var misses = 0
        repeat(2000) {
            val bx = random.nextInt(-20, 21)
            val by = random.nextInt(-20, 21)
            val bz = random.nextInt(-20, 21)
            val start = Vec3(
                bx + random.nextDouble(-2.0, 3.0),
                by + random.nextDouble(-2.0, 3.0),
                bz + random.nextDouble(-2.0, 3.0),
            )
            val end = Vec3(
                bx + random.nextDouble(-2.0, 3.0),
                by + random.nextDouble(-2.0, 3.0),
                bz + random.nextDouble(-2.0, 3.0),
            )
            val expected = AABB.clip(
                bx.toDouble(), by.toDouble(), bz.toDouble(),
                (bx + 1).toDouble(), (by + 1).toDouble(), (bz + 1).toDouble(),
                start, end,
            )
            val t = InsideBlockFastPath.clipUnitBox(
                bx, by, bz,
                start.x, start.y, start.z,
                end.x - start.x, end.y - start.y, end.z - start.z,
            )
            if (expected.isEmpty) {
                assertTrue(t.isNaN(), "expected miss at ($bx,$by,$bz) from $start to $end, got t=$t")
                misses++
            } else {
                assertFalse(t.isNaN(), "expected hit at ($bx,$by,$bz) from $start to $end")
                val actual = Vec3(
                    start.x + t * (end.x - start.x),
                    start.y + t * (end.y - start.y),
                    start.z + t * (end.z - start.z),
                )
                assertEquals(expected.get(), actual, "hit point mismatch at ($bx,$by,$bz)")
                hits++
            }
        }
        assertTrue(hits > 100 && misses > 100, "test must cover both hits ($hits) and misses ($misses)")
    }

    @Test
    fun scratchVisitedSetHandlesDuplicatesSpillAndWrap() {
        val scratch = InsideBlockFastPath.Scratch()
        scratch.resetVisited()
        // Fill past VISITED_MAX_LOAD to exercise overflow spill into LongOpenHashSet.
        for (i in 0 until 400) {
            val key = BlockPos.asLong(i - 200, (i % 16) - 8, i * 3 - 500)
            assertTrue(scratch.addVisited(key), "first insert of $i failed")
            assertFalse(scratch.addVisited(key), "duplicate insert of $i succeeded")
        }
        // Reset clears both table and overflow set.
        scratch.resetVisited()
        val sample = BlockPos.asLong(0, 0, 0)
        assertTrue(scratch.addVisited(sample))
        assertFalse(scratch.addVisited(sample))

        // Generation wrap from -1 resets visitedGen to 0 and advances to 1.
        scratch.visitedGen[0] = -1
        scratch.gen = -1
        scratch.resetVisited()
        assertEquals(1, scratch.gen)
        assertEquals(0, scratch.visitedGen[0])
    }

    @Test
    fun scratchBorrowReusesAndIsolatesReentrancy() {
        val outer = InsideBlockFastPath.borrowScratch()
        assertTrue(outer.inUse)
        val inner = InsideBlockFastPath.borrowScratch()
        assertNotSame(outer, inner)
        assertFalse(inner.inUse)
        InsideBlockFastPath.releaseScratch(inner)
        assertTrue(outer.inUse)
        InsideBlockFastPath.releaseScratch(outer)
        assertFalse(outer.inUse)
        val reused = InsideBlockFastPath.borrowScratch()
        assertSame(outer, reused)
        InsideBlockFastPath.releaseScratch(reused)
    }
}
