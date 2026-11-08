package com.synsenetwork.vanadium.tick

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap

/**
 * The cells for one stage of one world. Cells and their task lists are reused across ticks; the cells
 * that receive work each tick are recorded in one reusable active list. Cells idle for
 * [IDLE_EVICT_TICKS] ticks are evicted. Server-thread only: workers read cells only during a wave.
 */
class CellGrid(private val cellSize: Int) {
    private val cells = Long2ObjectOpenHashMap<Cell>()
    private val active = ArrayList<Cell>()
    private var tick = 0L

    /** The cell owning the chunk at (chunkX, chunkZ), created on first use. */
    fun cellFor(chunkX: Int, chunkZ: Int): Cell {
        val cellX = Math.floorDiv(chunkX, cellSize)
        val cellZ = Math.floorDiv(chunkZ, cellSize)
        val key = pack(cellX, cellZ)
        // Plain get/put: a capturing computeIfAbsent lambda would allocate on every enqueue.
        return cells.get(key) ?: Cell(cellX, cellZ).also { cells.put(key, it) }
    }

    /** Queues a task into the cell owning (chunkX, chunkZ) and records the cell active this tick. */
    fun enqueue(chunkX: Int, chunkZ: Int, task: Runnable) {
        val cell = cellFor(chunkX, chunkZ)
        if (!cell.hasTasks()) active.add(cell)
        cell.markActive(tick)
        cell.add(task)
    }

    /** The existing cell at cell coordinates (cellX, cellZ), or null. */
    internal fun cellAt(cellX: Int, cellZ: Int): Cell? = cells.get(pack(cellX, cellZ))

    /** The cells with work this tick, in first-enqueue order. The scheduler may reorder it in place. */
    internal val activeCells: MutableList<Cell> get() = active

    val isEmpty: Boolean get() = active.isEmpty()

    /** Number of retained (live) cells. */
    internal val size: Int get() = cells.size

    /** Starts a new tick: ages and evicts idle cells, then clears per-tick state. */
    fun beginTick() {
        tick++
        cells.values.removeIf { tick - it.lastActiveTick > IDLE_EVICT_TICKS }
        resetActive()
    }

    /** Clears per-tick state after a stage's wave; keeps the cell objects for reuse. */
    fun endTick() = resetActive()

    private fun resetActive() {
        for (cell in active) cell.clearTasks()
        active.clear()
    }

    companion object {
        internal const val IDLE_EVICT_TICKS = 100

        private fun pack(x: Int, z: Int): Long = (x.toLong() shl 32) or (z.toLong() and 0xFFFFFFFFL)
    }
}
