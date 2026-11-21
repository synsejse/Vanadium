package com.synsenetwork.vanadium.entity

import net.minecraft.core.BlockPos
import net.minecraft.world.phys.Vec3
import net.minecraft.world.phys.shapes.Shapes
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LineOfSightFastPathTest {
    @Test
    fun clipsFullCubeMatchesVanillaShapesBlockClip() {
        val random = Random(2632)
        var hits = 0
        var misses = 0
        val cube = Shapes.block()
        repeat(3000) { sample ->
            val bx = random.nextInt(-16, 17)
            val by = random.nextInt(-16, 17)
            val bz = random.nextInt(-16, 17)
            val from = when (sample % 5) {
                // Start strictly inside the cube
                0 -> Vec3(bx + random.nextDouble(0.05, 0.95), by + random.nextDouble(0.05, 0.95), bz + random.nextDouble(0.05, 0.95))
                // Start directly on a face or edge
                1 -> Vec3(bx + random.nextInt(0, 2).toDouble(), by + random.nextDouble(-0.5, 1.5), bz + random.nextDouble(-0.5, 1.5))
                else -> Vec3(bx + random.nextDouble(-2.5, 3.5), by + random.nextDouble(-2.5, 3.5), bz + random.nextDouble(-2.5, 3.5))
            }
            val to = Vec3(
                bx + random.nextDouble(-2.5, 3.5),
                by + random.nextDouble(-2.5, 3.5),
                bz + random.nextDouble(-2.5, 3.5),
            )
            val dx = to.x - from.x
            val dy = to.y - from.y
            val dz = to.z - from.z
            if (dx * dx + dy * dy + dz * dz < 1.0E-7) return@repeat
            val expected = cube.clip(from, to, BlockPos(bx, by, bz)) != null
            val actual = LineOfSightFastPath.clipsFullCube(bx, by, bz, from.x, from.y, from.z, dx, dy, dz)
            assertEquals(expected, actual, "clipsFullCube mismatch at ($bx,$by,$bz) from=$from to=$to")
            if (expected) hits++ else misses++
        }
        assertTrue(hits > 200 && misses > 200, "test must cover both hits ($hits) and misses ($misses)")
    }
}
