package com.synsenetwork.vanadium.tick

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicIntegerArray
import java.util.concurrent.atomic.AtomicReference

/**
 * Runs every active cell of a stage in one wave. A cell may start once none of its eight neighbors is
 * running, so adjacent cells never overlap, and a free cell never waits for unrelated work.
 *
 * Claim protocol (all state accesses are volatile and therefore sequentially consistent): a participant
 * moves a cell from PENDING to TRYING, then inspects its active neighbors. A RUNNING neighbor makes it
 * back off. For a TRYING neighbor the higher-index cell backs off and the lower-index cell waits for
 * that neighbor to resolve. Two adjacent claimants each write their own state before reading the
 * other's, so at least one observes the other: both can never pass. Waits only point to higher
 * indices, so they cannot form a cycle.
 *
 * Claim attempts first read the neighbors and write nothing when one is busy, so idle participants do not
 * bounce the state lines that running cells and other scanners read. A participant whose scan finds no
 * free cell waits for the completion count to change instead of rescanning: a claim only backs off for a
 * RUNNING neighbor or a lower-index TRYING one, and every such chain ends at a cell that runs, so a
 * failed scan always has a completion coming.
 *
 * Cells are tried in list order (callers sort them largest-first); tasks within a cell still run in
 * insertion order. Adjacent cells run in an order that varies between ticks.
 *
 * @param cells active cells of the stage, in claim priority order
 * @param work the runnable for each cell (the cell itself or a diagnostic/profiling wrapper), index-aligned
 */
internal class NeighborhoodWave(cells: List<Cell>, private val work: List<Runnable>, grid: CellGrid) {
    private val count = cells.size

    /** Active neighbor indices, eight slots per cell, -1 for none. */
    private val neighbors = IntArray(count * 8)
    private val states = AtomicIntegerArray(count * STRIDE)
    private val remaining = AtomicInteger(count)
    private val firstPending = AtomicInteger()
    private val failure = AtomicReference<Throwable?>()

    init {
        cells.forEachIndexed { i, cell -> cell.waveIndex = i }
        cells.forEachIndexed { i, cell ->
            for (k in 0 until 8) {
                val neighbor = grid.cellAt(cell.cellX + OFFSET_X[k], cell.cellZ + OFFSET_Z[k])
                neighbors[i * 8 + k] = if (neighbor != null && neighbor.hasTasks) neighbor.waveIndex else -1
            }
        }
    }

    /** One loop per participant; each returns once every cell has finished. */
    fun participants(participants: Int): List<Runnable> = List(participants) { Runnable(::participate) }

    /** The first cell failure, rethrown by the caller after the wave. */
    fun failure(): Throwable? = failure.get()

    private fun participate() {
        while (true) {
            val seen = remaining.get()
            if (seen == 0) return
            if (claimAndRunOne()) continue
            // Nothing claimable: only a completion can unblock a cell, so wait for one instead of rescanning.
            while (remaining.get() == seen) Thread.onSpinWait()
        }
    }

    private fun claimAndRunOne(): Boolean {
        var start = firstPending.get()
        for (i in start until count) {
            val state = states.get(i * STRIDE)
            if (state == DONE) {
                if (i == start) {
                    // Advance the shared low-water mark over the finished prefix.
                    firstPending.compareAndSet(i, i + 1)
                    start = i + 1
                }
                continue
            }
            if (state != PENDING || blocked(i) || !tryClaim(i)) continue
            try {
                work[i].run()
            } catch (t: Throwable) {
                failure.compareAndSet(null, t)
            } finally {
                states.set(i * STRIDE, DONE)
                remaining.decrementAndGet()
            }
            return true
        }
        return false
    }

    /** Read-only precheck: a claim attempt that would back off writes nothing. */
    private fun blocked(cell: Int): Boolean {
        for (k in 0 until 8) {
            val neighbor = neighbors[cell * 8 + k]
            if (neighbor < 0) continue
            val state = states.get(neighbor * STRIDE)
            if (state == RUNNING || state == TRYING) return true
        }
        return false
    }

    private fun tryClaim(cell: Int): Boolean {
        if (!states.compareAndSet(cell * STRIDE, PENDING, TRYING)) return false
        for (k in 0 until 8) {
            val neighbor = neighbors[cell * 8 + k]
            if (neighbor < 0) continue
            while (true) {
                val state = states.get(neighbor * STRIDE)
                if (state == RUNNING || state == TRYING && neighbor < cell) {
                    states.set(cell * STRIDE, PENDING)
                    return false
                }
                if (state != TRYING) break
                Thread.onSpinWait() // A higher-index claimant resolves without waiting on us.
            }
        }
        states.set(cell * STRIDE, RUNNING)
        return true
    }

    private companion object {
        const val PENDING = 0
        const val TRYING = 1
        const val RUNNING = 2
        const val DONE = 3

        /** One state per 64-byte line: claims on one cell do not invalidate a neighbor's line. */
        const val STRIDE = 16
        val OFFSET_X = intArrayOf(-1, 0, 1, -1, 1, -1, 0, 1)
        val OFFSET_Z = intArrayOf(-1, -1, -1, 0, 0, 1, 1, 1)
    }
}
