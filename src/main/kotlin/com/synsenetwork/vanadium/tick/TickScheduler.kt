package com.synsenetwork.vanadium.tick

import java.util.EnumMap
import java.util.function.LongConsumer

/** Server-thread stage collection and dispatch. Worlds run sequentially, with a barrier per stage. */
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
    fun prepare(kind: Preparation, jobs: List<Runnable>) {
        val recording = profile
        val event = PreparationEvent()
        event.begin()
        val start = if (recording != null) System.nanoTime() else 0
        try {
            pool.runWave(jobs)
        } finally {
            recording?.recordPreparation(kind, jobs.size, System.nanoTime() - start)
            if (event.shouldCommit()) {
                event.kind = kind.name
                event.jobs = jobs.size
                event.participants = participants(jobs.size)
                event.commit()
            }
        }
    }

    private fun participants(tasks: Int): Int = minOf(pool.workerCount + 1, tasks)

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
        grids.getValue(stage).beginTick()
    }

    /** Queues a tick task into its cell. Pass null for the label unless detailed diagnostics are enabled. */
    fun enqueue(stage: Stage, chunkX: Int, chunkZ: Int, task: Runnable, label: String?) {
        grids.getValue(stage).enqueue(chunkX, chunkZ, if (label == null) task else WaveDiagnostics.NamedTask(label, task))
    }

    /** Runs the stage's queued work as one neighborhood-exclusion wave, then resets the stage. */
    fun run(stage: Stage) {
        val grid = grids.getValue(stage)
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
        val participants = participants(cells.size)
        val event = WaveEvent()
        event.begin()
        val start = if (recording != null) System.nanoTime() else 0
        diagnostics?.watch(stage, dimension, cellSize).use { watch ->
            var tasks: List<Runnable> = watch?.wrap(cells) ?: cells
            val durations = if (recording != null) LongArray(tasks.size) else null
            if (durations != null) tasks = tasks.mapIndexed { index, task ->
                Runnable {
                    val cellStart = System.nanoTime()
                    try {
                        task.run()
                    } finally {
                        durations[index] = System.nanoTime() - cellStart
                    }
                }
            }
            try {
                if (cells.size == 1) {
                    tasks[0].run()
                    return
                }
                val wave = NeighborhoodWave(cells, tasks, grid)
                pool.runWave(wave.participants(participants), waitRecorder)
                wave.failure()?.let { throw RuntimeException("Vanadium worker task failed", it) }
            } finally {
                if (durations != null) recording?.recordCells(stage, durations)
                recording?.recordWaveCapacity(stage, System.nanoTime() - start, participants)
                if (event.shouldCommit()) {
                    event.stage = stage.name
                    event.dimension = dimension
                    event.cells = cells.size
                    event.tasks = cells.sumOf { it.taskCount }
                    event.participants = participants
                    event.cellSize = cellSize
                    event.commit()
                }
            }
        }
    }

    private companion object {
        // Largest cells first to shorten the wave's tail.
        val LARGEST_FIRST = compareByDescending<Cell> { it.taskCount }
    }
}
