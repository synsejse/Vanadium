package com.synsenetwork.vanadium.navigation

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
        scratch.gen = -1
        scratch.bumpGen()
        assertEquals(1, scratch.gen)
        assertEquals(0, scratch.floorGen[0])
        assertEquals(0, scratch.typeGen[0])
    }
}
