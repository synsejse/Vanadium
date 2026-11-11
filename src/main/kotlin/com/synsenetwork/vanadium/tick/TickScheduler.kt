package com.synsenetwork.vanadium.tick

import com.synsenetwork.vanadium.concurrent.Interactions
import java.util.EnumMap
import java.util.function.LongConsumer

/**
 * Server-thread stage collection and dispatch. Worlds run sequentially, with a barrier per stage. Entities
 * run as one flat wave ([runFlat]). Every other stage's tasks are planned into regions that adapt to where
 * the work is ([RegionGrid]); regions within [isolation] chunks of each other never run at the same time.
 */
class TickScheduler(private val pool: WorkerPool, isolation: Int) {
    private val grids = EnumMap<Stage, RegionGrid>(Stage::class.java).apply {
        Stage.entries.forEach { if (it != Stage.ENTITY) put(it, RegionGrid()) }
    }
    private val preparationStarts = EnumMap<Stage, Long>(Stage::class.java)
    private var dimension = "unknown"
    private var worldStart = 0L

    /** Chunks between work that may run at the same time, 1 to [RegionGrid.MAX_ISOLATION]. Applies from the next wave. */
    var isolation: Int = isolation.coerceIn(1, RegionGrid.MAX_ISOLATION)
        set(value) {
            field = value.coerceIn(1, RegionGrid.MAX_ISOLATION)
        }

    var profile: TickProfile? = null
        set(value) {
            field = value
            preparationStarts.clear()
        }

    var diagnostics: WaveDiagnostics? = null

    val workerCount: Int get() = pool.workerCount

    val detailedDiagnostics: Boolean get() = diagnostics?.detailed == true

    /** Independent preparation jobs only: inputs stay stable and jobs must not enqueue into stage grids. */
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

    /** Starts a stage's tick: for a region stage, ages idle chunks and clears leftover work. */
    fun begin(stage: Stage) {
        if (profile != null) preparationStarts[stage] = System.nanoTime()
        grids[stage]?.beginTick()
    }

    /** Queues a tick task at its chunk. Pass null for the label unless detailed diagnostics are enabled. */
    fun enqueue(stage: Stage, chunkX: Int, chunkZ: Int, task: Runnable, label: String?) {
        grids.getValue(stage).enqueue(chunkX, chunkZ, if (label == null) task else WaveDiagnostics.NamedTask(label, task))
    }

    /** Runs the stage's queued work as one wave, then resets the stage. */
    fun run(stage: Stage) {
        val grid = grids.getValue(stage)
        val recording = profile
        val start = if (recording != null) System.nanoTime() else 0
        preparationStarts.remove(stage)?.let { recording?.recordPhase(dimension, "$stage preparation", start - it) }
        try {
            if (!grid.isEmpty) runWave(stage, grid, recording)
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

    /**
     * Runs [tasks] as one wave with no regions or isolation: every task may run beside any other, so each must
     * protect what it shares ([Interactions] for entities). Call after the stage's [begin].
     */
    fun runFlat(stage: Stage, tasks: List<Runnable>) {
        if (tasks.isEmpty()) return
        val recording = profile
        recording?.recordFlatWave(stage, tasks.size)
        val waitRecorder = recording?.let { LongConsumer { nanos -> it.recordWait(stage, nanos) } }
        val participants = participants(tasks.size)
        val event = WaveEvent()
        event.begin()
        val start = System.nanoTime()
        preparationStarts.remove(stage)?.let { recording?.recordPhase(dimension, "$stage preparation", start - it) }
        try {
            diagnostics?.watch(stage, dimension, 0).use { watch ->
                val wave = watch?.wrap(tasks) ?: tasks
                Interactions.runWave { pool.runWave(wave, waitRecorder) }
            }
        } finally {
            if (recording != null) {
                val elapsed = System.nanoTime() - start
                recording.recordWaveCapacity(stage, elapsed, participants)
                recording.recordStage(stage, elapsed)
                recording.recordPhase(dimension, "$stage execution", elapsed)
            }
            if (event.shouldCommit()) {
                event.stage = stage.name
                event.dimension = dimension
                event.tasks = tasks.size
                event.participants = participants
                event.commit()
            }
        }
    }

    private fun runWave(stage: Stage, grid: RegionGrid, recording: TickProfile?) {
        val regions = grid.plan(pool.workerCount + 1, isolation)
        recording?.recordWave(stage, regions)
        val waitRecorder = recording?.let { LongConsumer { nanos -> it.recordWait(stage, nanos) } }
        val participants = participants(regions.size)
        val event = WaveEvent()
        event.begin()
        val start = System.nanoTime()
        diagnostics?.watch(stage, dimension, isolation).use { watch ->
            var tasks: List<Runnable> = watch?.wrap(regions) ?: regions
            val durations = if (recording != null) LongArray(tasks.size) else null
            if (durations != null) tasks = tasks.mapIndexed { index, task ->
                Runnable {
                    val regionStart = System.nanoTime()
                    try {
                        task.run()
                    } finally {
                        durations[index] = System.nanoTime() - regionStart
                    }
                }
            }
            var busy = -1L
            try {
                if (regions.size == 1) {
                    tasks[0].run()
                    return
                }
                // Heavy regions pack best by dynamic claiming; cheap ones by dataflow, which never scans.
                val wave = if (grid.nanosPerRegion >= CLAIM_MIN_REGION_NANOS) {
                    ClaimWave(tasks, grid.neighborStart, grid.neighbors)
                } else {
                    DataflowWave(tasks, grid.neighborStart, grid.neighbors, grid.colours)
                }
                pool.runWave(wave.participants(participants), waitRecorder)
                busy = wave.busyNanos
                wave.failure?.let { throw RuntimeException("Vanadium worker task failed", it) }
            } finally {
                val wall = System.nanoTime() - start
                grid.recordWave(if (busy >= 0) busy else wall)
                if (durations != null) recording?.recordRegions(stage, durations)
                recording?.recordWaveCapacity(stage, wall, participants)
                if (event.shouldCommit()) {
                    event.stage = stage.name
                    event.dimension = dimension
                    event.regions = regions.size
                    event.tasks = grid.taskCount
                    event.participants = participants
                    event.isolation = isolation
                    event.commit()
                }
            }
        }
    }

    private companion object {
        /**
         * Average region work above which dynamic claiming beats dataflow ordering: measured on 26.3, heavy
         * entity regions finished about 6% sooner when claimed, cheap random-tick regions about 2x sooner in
         * dataflow order.
         */
        const val CLAIM_MIN_REGION_NANOS = 100_000.0
    }
}
