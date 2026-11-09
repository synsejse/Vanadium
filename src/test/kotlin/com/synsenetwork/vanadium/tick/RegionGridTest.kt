package com.synsenetwork.vanadium.tick

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RegionGridTest {
    private class Task(val x: Int, val z: Int, val seq: Int) : Runnable {
        override fun run() {}
    }

    private fun fill(grid: RegionGrid, chunks: List<Pair<Int, Int>>, perChunk: (Int, Int) -> Int): List<Task> {
        val tasks = ArrayList<Task>()
        var seq = 0
        // Interleave chunks so enqueue order is not grouped by chunk.
        val most = chunks.maxOf { (x, z) -> perChunk(x, z) }
        for (round in 0 until most) for ((x, z) in chunks) {
            if (round < perChunk(x, z)) Task(x, z, seq++).also { tasks.add(it); grid.enqueue(x, z, it) }
        }
        return tasks
    }

    private fun square(from: Int, until: Int) = (from until until).flatMap { x -> (from until until).map { z -> x to z } }

    @Test
    fun heavyWorkSplitsToSingleChunksAndLightWorkStaysMerged() {
        val grid = RegionGrid()
        fill(grid, square(0, 32)) { _, _ -> 1 }
        assertEquals(1024, grid.plan(300, 2).size, "1024 tasks over 300 participants split to single chunks")
        grid.endTick()
        fill(grid, square(0, 32)) { _, _ -> 1 }
        val merged = grid.plan(1, 2)
        assertEquals(4, merged.size, "one participant: a 256-task target leaves whole 16x16 tiles")
        assertTrue(merged.all { it.maxX - it.minX == 15 && it.maxZ - it.minZ == 15 })
        grid.endTick()
    }

    @Test
    fun everyTaskRunsOnceInEnqueueOrderAndBoxesAreDisjoint() {
        val grid = RegionGrid()
        val random = Random(3)
        val chunks = (0 until 300).map { random.nextInt(-40, 40) to random.nextInt(-40, 40) }.distinct()
        val tasks = fill(grid, chunks) { x, z -> 1 + ((x * 31 + z * 17) and 7) }
        val regions = grid.plan(17, 2)
        val seen = HashSet<Int>()
        for (region in regions) {
            var last = -1
            for (task in region.tasks) {
                task as Task
                assertTrue(task.seq > last, "enqueue order within a region")
                last = task.seq
                assertTrue(seen.add(task.seq), "task planned twice")
                assertTrue(task.x in region.minX..region.maxX && task.z in region.minZ..region.maxZ, "chunk outside box")
            }
        }
        assertEquals(tasks.size, seen.size)
        for (a in regions) for (b in regions) if (a !== b) {
            val overlap = a.minX <= b.maxX && b.minX <= a.maxX && a.minZ <= b.maxZ && b.minZ <= a.maxZ
            assertTrue(!overlap, "region boxes overlap: $a $b")
        }
        assertTrue(regions.zipWithNext().all { (a, b) -> a.taskCount >= b.taskCount }, "largest first")
        grid.endTick()
    }

    @Test
    fun regionsThatMayRunTogetherKeepEveryChunkBeyondIsolation() {
        val random = Random(11)
        repeat(40) { round ->
            val grid = RegionGrid()
            val isolation = intArrayOf(1, 2, 4, 8, RegionGrid.MAX_ISOLATION)[round % 5]
            val spread = if (round % 2 == 0) 12 else 60 // dense and sparse layouts
            val chunks = (0 until 200).map { random.nextInt(-spread, spread) to random.nextInt(-spread, spread) }.distinct()
            fill(grid, chunks) { x, z -> 1 + ((x xor z) and 3) * (if (random.nextInt(10) == 0) 20 else 1) }
            val regions = grid.plan(1 + random.nextInt(48), isolation)
            val start = grid.neighborStart
            val list = grid.neighbors
            val conflicts = regions.indices.map { i -> (start[i] until start[i + 1]).map { list[it] }.toSet() }
            for (i in regions.indices) {
                assertTrue(i !in conflicts[i], "self conflict")
                for (j in conflicts[i]) {
                    assertTrue(i in conflicts[j], "conflicts are symmetric")
                    assertTrue(grid.colours[i] != grid.colours[j], "conflicting regions share a colour")
                }
            }
            val members = regions.map { r -> r.tasks.map { (it as Task).x to it.z }.toSet() }
            for (i in regions.indices) for (j in i + 1 until regions.size) {
                if (j in conflicts[i]) continue
                for ((ax, az) in members[i]) for ((bx, bz) in members[j]) {
                    val distance = maxOf(Math.abs(ax - bx), Math.abs(az - bz))
                    assertTrue(distance > isolation, "round $round: chunks $ax,$az and $bx,$bz may run together at isolation $isolation")
                }
            }
            grid.endTick()
        }
    }

    @Test
    fun cheapWorkMergesOnceCostIsKnown() {
        val grid = RegionGrid()
        fill(grid, square(0, 16)) { _, _ -> 1 }
        assertEquals(256, grid.plan(100, 2).size)
        grid.recordWave(256L) // About a nanosecond per task: far below the smallest worthwhile region.
        grid.endTick()
        fill(grid, square(0, 16)) { _, _ -> 1 }
        assertEquals(1, grid.plan(100, 2).size)
        grid.endTick()
    }

    @Test
    fun idleChunksAreEvicted() {
        val grid = RegionGrid()
        grid.beginTick()
        grid.enqueue(0, 0) {}
        grid.enqueue(-1, -1) {}
        grid.plan(4, 2)
        grid.endTick()
        assertEquals(2, grid.bucketCount)
        repeat(RegionGrid.IDLE_EVICT_TICKS + 2) {
            grid.beginTick()
            grid.enqueue(0, 0) {}
            grid.endTick()
        }
        assertEquals(1, grid.bucketCount)
    }

    @Test
    fun stablePlansAreReusedUntilTheWorkMoves() {
        val grid = RegionGrid()
        fill(grid, square(0, 8)) { _, _ -> 2 }
        val first = grid.plan(5, 2).toList()
        grid.endTick()
        fill(grid, square(0, 8)) { _, _ -> 2 }
        val second = grid.plan(5, 2)
        assertTrue(first.size == second.size && first.indices.all { first[it] === second[it] }, "unchanged work reuses the plan")
        assertEquals(128, second.sumOf { it.taskCount })
        grid.endTick()
        fill(grid, square(0, 8) + (40 to 40)) { _, _ -> 2 }
        val moved = grid.plan(5, 2)
        assertTrue(moved.any { it.minX == 40 && it.minZ == 40 }, "a chunk outside the plan forces a replan")
        assertEquals(130, moved.sumOf { it.taskCount })
        grid.endTick()
        repeat(RegionGrid.MAX_REUSES + 1) {
            fill(grid, square(0, 8) + (40 to 40)) { _, _ -> 2 }
            grid.plan(5, 2)
            grid.endTick()
        }
        fill(grid, square(0, 8) + (40 to 40)) { _, _ -> 2 }
        assertEquals(130, grid.plan(5, 2).sumOf { it.taskCount }, "a rebuilt plan still covers every task")
        grid.endTick()
    }
}
