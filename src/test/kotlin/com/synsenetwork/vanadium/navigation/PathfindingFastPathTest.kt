package com.synsenetwork.vanadium.navigation

import net.minecraft.world.level.pathfinder.Node
import net.minecraft.world.level.pathfinder.PathType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

class PathfindingFastPathTest {
    @Test
    fun walkScratchBorrowsReusesAndHandlesReentrancy() {
        val first = PathfindingFastPath.borrowWalkScratch()
        assertTrue(first.inUse)
        val gen1 = first.gen
        // Reentrant borrow on the same thread gets a distinct unpooled instance.
        val nested = PathfindingFastPath.borrowWalkScratch()
        assertNotSame(first, nested)
        assertFalse(nested.inUse)
        PathfindingFastPath.releaseWalkScratch(nested)
        assertTrue(first.inUse, "releasing a nested scratch must not release the outer thread-local scratch")

        PathfindingFastPath.releaseWalkScratch(first)
        assertFalse(first.inUse)
        val reused = PathfindingFastPath.borrowWalkScratch()
        assertSame(first, reused)
        assertEquals(gen1 + 1, reused.gen)
        PathfindingFastPath.releaseWalkScratch(reused)
    }

    @Test
    fun walkScratchGenerationWrapClearsStamps() {
        val scratch = PathfindingFastPath.WalkScratch()
        scratch.floorGen[0] = -1
        scratch.typeGen[0] = -1
        scratch.nodeGen[0] = -1
        scratch.gen = -1
        scratch.bumpGen()
        assertEquals(1, scratch.gen)
        assertEquals(0, scratch.floorGen[0])
        assertEquals(0, scratch.typeGen[0])
        assertEquals(0, scratch.nodeGen[0])
        assertEquals(0, scratch.nodeCount)
        assertEquals(0, scratch.poolUsed)
    }

    @Test
    fun nodeTableHandlesDuplicatesGrowthAndReset() {
        val scratch = PathfindingFastPath.WalkScratch()
        scratch.bumpGen()

        // Insert > 1024 distinct coordinates to exercise open-addressed table growth and pool growth.
        val created = ArrayList<Node>(1500)
        for (i in 0 until 1500) {
            val x = (i % 40) - 20
            val y = 64 + (i / 40) % 4
            val z = (i / 160) - 5
            val node = scratch.getOrCreateNode(x, y, z)
            assertEquals(x, node.x)
            assertEquals(y, node.y)
            assertEquals(z, node.z)
            assertSame(node, scratch.getOrCreateNode(x, y, z), "lookup must return identical Node for ($x,$y,$z)")
            created.add(node)
        }
        assertEquals(1500, scratch.nodeCount)
        assertTrue(scratch.nodeGen.size >= 4096, "table should have doubled past initial 2048")
        // Verify all earlier nodes remain retrievable after rehash.
        for (node in created) {
            assertSame(node, scratch.getOrCreateNode(node.x, node.y, node.z))
        }

        // Next generation clears all prior entries in O(1).
        scratch.bumpGen()
        assertEquals(0, scratch.nodeCount)
        assertEquals(0, scratch.poolUsed)
        // With NodeAccess implemented (test stub), pooled slots reset in place without allocating.
        val resetCount = intArrayOf(0)
        val stub = object : Node(0, 0, 0), NodeAccess {
            var rx = 0; var ry = 0; var rz = 0; var rhash = 0
            override fun `vanadium$reset`(x: Int, y: Int, z: Int, hash: Int) {
                rx = x; ry = y; rz = z; rhash = hash
                this.heapIdx = -1; this.closed = false; this.type = PathType.BLOCKED
                resetCount[0]++
            }
        }
        scratch.nodePool[0] = stub
        val reused = scratch.getOrCreateNode(12, 70, -5)
        assertSame(stub, reused)
        assertEquals(1, resetCount[0])
        assertEquals(12, stub.rx)
        assertEquals(70, stub.ry)
        assertEquals(-5, stub.rz)
        assertEquals(Node.createHash(12, 70, -5), stub.rhash)
    }
}
