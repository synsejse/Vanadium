package com.synsenetwork.vanadium.tick

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CellTest {
    @Test
    fun runsTasksInInsertionOrder() {
        val log = ArrayList<Int>()
        val cell = Cell(0, 0)
        for (i in 1..3) cell.add { log.add(i) }
        cell.run()
        assertEquals(listOf(1, 2, 3), log)
    }

    @Test
    fun hasTasksReflectsContent() {
        val cell = Cell(0, 0)
        assertFalse(cell.hasTasks)
        cell.add {}
        assertTrue(cell.hasTasks)
        assertEquals(1, cell.taskCount)
    }

    @Test
    fun clearTasksEmptiesButKeepsCellReusable() {
        val log = ArrayList<Int>()
        val cell = Cell(0, 0)
        cell.add { log.add(1) }
        cell.clearTasks()
        assertFalse(cell.hasTasks)
        cell.add { log.add(2) }
        cell.run()
        assertEquals(listOf(2), log)
    }

    @Test
    fun recordsLastActiveTick() {
        val cell = Cell(0, 0)
        cell.lastActiveTick = 42L
        assertEquals(42L, cell.lastActiveTick)
    }
}
