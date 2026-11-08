package com.synsenetwork.vanadium.tick

import java.util.EnumMap
import java.util.function.LongConsumer

/**
 * Runs each [Stage] of a world tick as one [NeighborhoodWave] on a [WorkerPool]: a cell starts as soon
 * as none of its eight neighbors is running, so adjacent cells never overlap in time. Worlds are
 * processed one at a time, and stages are separated by full barriers. Server-thread only.
 */
class TickScheduler(private val pool: WorkerPool, cellSize: Int) {
    private val grids = EnumMap<Stage, CellGrid>(Stage::class.java)
    private val preparationStarts = EnumMap<Stage, Long>(Stage::class.java)
    private var dimension = "unknown"
    private var worldStart = 0L

    /** Cell width in chunks; changing it rebuilds the grids and applies from the next stage. */
    var cellSize: Int = cellSize
        set(value) {
            if (value != field) {
                field = value
                rebuildGrids()
            }
        }

    /** Opt-in profiling; it does not change dispatch. */
    var profile: TickProfile? = null
        set(value) {
            field = value
            preparationStarts.clear()
        }

    var diagnostics: WaveDiagnostics? = null

    init {
        rebuildGrids()
    }

    val workerCount: Int get() = pool.workerCount

    val detailedDiagnostics: Boolean get() = diagnostics?.detailed == true

    /** Independent preparation jobs only: inputs stay stable and jobs must not enqueue into cell grids. */
    fun prepare(jobs: List<Runnable>) = pool.runWave(jobs)

    fun setDimension(dimension: String) {
        this.dimension = dimension
        if (profile != null) worldStart = System.nanoTime()
    }

    fun finishWorld() {
        profile?.recordPhase(dimension, "world total", System.nanoTime() - worldStart)
    }

    private fun rebuildGrids() {
        for (stage in Stage.entries) grids[stage] = CellGrid(cellSize)
    }

    /** Starts a stage's tick: ages idle cells and clears leftover work. */
    fun begin(stage: Stage) {
        if (profile != null) preparationStarts[stage] = System.nanoTime()
        grid(stage).beginTick()
    }

    /** Queues a tick task into its cell. Pass null for the label unless detailed diagnostics are enabled. */
    fun enqueue(stage: Stage, chunkX: Int, chunkZ: Int, task: Runnable, label: String?) {
        grid(stage).enqueue(chunkX, chunkZ, if (label == null) task else WaveDiagnostics.NamedTask(label, task))
    }

    /** Runs the stage's queued work as one neighborhood-exclusion wave, then resets the stage. */
    fun run(stage: Stage) {
        val grid = grid(stage)
        val recording = profile
        val start = if (recording != null) System.nanoTime() else 0
        preparationStarts.remove(stage)?.let { recording?.recordPhase(dimension, "$stage preparation", start - it) }
        try {
            val cells = grid.activeCells
            if (cells.isNotEmpty()) runWave(stage, grid, cells, recording)
        } finally {
            if (recording != null) {
                val end = System.nanoTime()
                recording.recordStage(stage, end - start)
                recording.recordPhase(dimension, "$stage execution", end - start)
                preparationStarts[stage] = end
            }
            grid.endTick()
        }
    }

    private fun runWave(stage: Stage, grid: CellGrid, cells: MutableList<Cell>, recording: TickProfile?) {
        if (cells.size > 2) cells.sortWith(LARGEST_FIRST)
        recording?.recordWave(stage, cells)
        val waitRecorder = recording?.let { LongConsumer { nanos -> it.recordWait(stage, nanos) } }
        diagnostics?.watch(stage, dimension, cellSize).use { watch ->
            var tasks: List<Runnable> = watch?.wrap(cells) ?: cells
            val durations = if (recording != null) LongArray(tasks.size) else null
            if (durations != null) tasks = measured(tasks, durations)
            try {
                if (cells.size == 1) {
                    tasks[0].run()
                    return
                }
                val wave = NeighborhoodWave(cells, tasks, grid)
                pool.runWave(wave.participants(minOf(pool.workerCount + 1, cells.size)), waitRecorder)
                wave.failure()?.let { throw RuntimeException("Vanadium worker task failed", it) }
            } finally {
                if (durations != null) recording?.recordCells(stage, durations)
            }
        }
    }

    private fun grid(stage: Stage): CellGrid = grids.getValue(stage)

    private companion object {
        /** Largest-first order shortens a wave's tail when cell sizes differ (LPT scheduling). */
        val LARGEST_FIRST = compareByDescending<Cell> { it.taskCount }

        /** Opt-in profiling: each cell writes its duration into its own slot; the caller aggregates. */
        fun measured(tasks: List<Runnable>, durations: LongArray): List<Runnable> = tasks.mapIndexed { index, task ->
            Runnable {
                val cellStart = System.nanoTime()
                try {
                    task.run()
                } finally {
                    durations[index] = System.nanoTime() - cellStart
                }
            }
        }
    }
}
