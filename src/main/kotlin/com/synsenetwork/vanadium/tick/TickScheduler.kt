package com.synsenetwork.vanadium.tick

import com.synsenetwork.vanadium.concurrent.Interactions
import com.synsenetwork.vanadium.config.DeveloperProperties
import com.synsenetwork.vanadium.diagnostics.PreparationEvent
import com.synsenetwork.vanadium.diagnostics.TickProfile
import com.synsenetwork.vanadium.diagnostics.WaveDiagnostics
import com.synsenetwork.vanadium.diagnostics.WaveEvent
import java.util.EnumMap
import java.util.function.LongConsumer
import kotlin.math.ceil
import kotlin.math.sqrt

/**
 * Server-thread stage collection and dispatch. Worlds run sequentially, with a barrier per stage. Entities
 * run as one flat wave ([runFlat]). Every other stage's tasks are planned into regions that adapt to where
 * the work is ([RegionGrid]); regions within [isolation] chunks of each other never run at the same time.
 *
 * Each wave involves only as many threads as its estimated work pays for ([participants]); estimates come
 * from the grids for region stages and from measured busy time per task for flat and preparation waves. A flat
 * wave whose longest task is known also gets no more threads than finish the rest of its work while that task
 * runs ([flatParticipants]).
 *
 * Natural spawning waves ([Stage.SPAWNING]) may also be bounded by an experimental [spawnParticipants] ceiling
 * (`-Dvanadium.spawnParticipants`, default `auto`: no bound). The bound lowers the participants the wave is offered
 * and records, never raises them, and does not guarantee that many threads find work; the learned estimates,
 * region plan, spawn-count preparation and every other stage are unchanged.
 *
 * @param participantCostNanos what involving one more thread costs a wave; 0 always uses every worker
 */
class TickScheduler internal constructor(
    private val pool: WorkerPool,
    isolation: Int,
    private val participantCostNanos: Double,
    /** Share of a flat wave's longest task within which the other threads should finish ([flatParticipants]); 0 never bounds. */
    private val criticalPathShare: Double = CRITICAL_PATH_SHARE,
    /**
     * Most participants of a spawning wave, the caller included, on top of [participants]; null adds no bound.
     * Experimental: 1 runs the spawning regions inline on the caller, in plan order.
     */
    val spawnParticipants: Int? = null,
) {
    constructor(pool: WorkerPool, isolation: Int, spawnParticipants: Int? = null) :
        this(pool, isolation, PARTICIPANT_COST_NANOS, CRITICAL_PATH_SHARE, spawnParticipants)

    init {
        require(spawnParticipants == null || spawnParticipants >= 1) {
            "a spawning wave has at least 1 participant (the caller), not $spawnParticipants"
        }
    }

    private val grids = EnumMap<Stage, RegionGrid>(Stage::class.java).apply {
        Stage.entries.forEach { if (it != Stage.ENTITY) put(it, RegionGrid()) }
    }
    private val preparationStarts = EnumMap<Stage, Long>(Stage::class.java)

    /** Smoothed busy time per task of flat waves by stage, and per job of preparations by kind; 0 before any. */
    private val flatNanosPerTask = DoubleArray(Stage.entries.size)
    private val preparationNanosPerJob = DoubleArray(PreparationKind.entries.size)
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

    /** The world being ticked, or the last one started, for the watchdog crash report. */
    internal val currentDimension: String get() = dimension

    /**
     * The stage last begun in [currentDimension], or null before its first, for the watchdog crash report. One plain
     * write per stage and world, which every stage's collection, preparations and wave follow: the watchdog thread
     * may read a stale value, which costs nothing until it fires.
     */
    internal var currentStage: Stage? = null
        private set

    /** [WorkerPool.latestWaveProgress], for the watchdog crash report. */
    internal fun latestWaveProgress(): String = pool.latestWaveProgress()

    /** [spawnParticipants] as results and benchmark arms record it: the limit, or `auto`. */
    val spawnParticipantsLabel: String get() = spawnParticipants?.toString() ?: "auto"

    val detailedDiagnostics: Boolean get() = diagnostics?.detailed == true

    /** Independent preparation jobs only: inputs stay stable and jobs must not enqueue into stage grids. */
    fun prepare(kind: PreparationKind, jobs: List<Runnable>) {
        val recording = profile
        val event = PreparationEvent()
        event.begin()
        val start = if (recording != null) System.nanoTime() else 0
        val participants = participants(jobs.size, preparationNanosPerJob[kind.ordinal] * jobs.size)
        try {
            learn(preparationNanosPerJob, kind.ordinal, pool.runWave(jobs, null, participants), jobs.size)
        } finally {
            recording?.recordPreparation(kind, jobs.size, System.nanoTime() - start)
            if (event.shouldCommit()) {
                event.kind = kind.name
                event.jobs = jobs.size
                event.participants = participants
                event.commit()
            }
        }
    }

    /**
     * Threads worth involving in a wave of [tasks] tasks and about [estimatedNanos] of work, the caller
     * included. With W of work over p participants, each costing c to involve (its share of the wake-up fan-out,
     * claim and barrier traffic, cold caches), the wave takes about W / p + c * p, least at p = sqrt(W / c):
     * each participant's slice is then sqrt(W * c), so larger waves get both more threads and larger slices.
     * Waves too small to pay for a second thread run inline on the caller. Unknown cost (0) uses every worker.
     * The pool gives the slots to its lowest-numbered workers.
     */
    internal fun participants(tasks: Int, estimatedNanos: Double): Int {
        val most = maxOf(1, minOf(pool.workerCount + 1, tasks))
        if (estimatedNanos <= 0.0 || participantCostNanos <= 0.0) return most
        return sqrt(estimatedNanos / participantCostNanos).toInt().coerceIn(1, most)
    }

    /**
     * Threads, the caller included, for a flat wave of [units] work items whose longest task is expected to take
     * [longestNanos] (0 when unknown): [participants], and no more than finish the rest of the wave's work within
     * [criticalPathShare] of that task's time. The wave cannot end before its longest task does; threads beyond
     * those would only add claim traffic and spin through the tail, the time the caller waits for that task.
     */
    fun flatParticipants(stage: Stage, units: Int, longestNanos: Double): Int =
        flatParticipants(units, flatNanosPerTask[stage.ordinal] * units, longestNanos)

    /** [flatParticipants] for a wave of about [estimated] work. */
    internal fun flatParticipants(units: Int, estimated: Double, longestNanos: Double): Int {
        val threads = participants(units, estimated)
        if (longestNanos <= 0.0 || estimated <= 0.0 || criticalPathShare <= 0.0) return threads
        // One thread runs the longest task; the others share the rest.
        val others = ceil(maxOf(0.0, estimated - longestNanos) / (longestNanos * criticalPathShare)).toInt()
        return minOf(threads, maxOf(1, 1 + others))
    }

    /** Feeds a wave's busy time into a per-task estimate. */
    private fun learn(estimates: DoubleArray, slot: Int, busyNanos: Long, tasks: Int) {
        if (busyNanos <= 0 || tasks == 0) return
        val sample = busyNanos.toDouble() / tasks
        val previous = estimates[slot]
        estimates[slot] = if (previous == 0.0) sample else previous + (sample - previous) * COST_SMOOTHING
    }

    fun setDimension(dimension: String) {
        this.dimension = dimension
        currentStage = null
        if (profile != null) worldStart = System.nanoTime()
    }

    fun finishWorld() {
        profile?.recordPhase(dimension, "world total", System.nanoTime() - worldStart)
    }

    /** Starts a stage's tick: for a region stage, ages idle chunks and clears leftover work. */
    fun begin(stage: Stage) {
        currentStage = stage
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
        recordPreparation(stage, start, recording)
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
     * protect what it shares ([Interactions] for entities). [units] is the work items the tasks run between them
     * (entities, when each task runs a group of them); costs are learned and reported per unit. Call after the
     * stage's [begin]. [participants] defaults to what the estimates pay for; [flatParticipants] also bounds it by
     * the wave's longest task. [homes], if given, says which participant runs which tasks ([WaveHomes]).
     */
    fun runFlat(stage: Stage, tasks: List<Runnable>, units: Int = tasks.size,
                participants: Int = participants(tasks.size, flatNanosPerTask[stage.ordinal] * units), homes: WaveHomes? = null) {
        if (tasks.isEmpty()) return
        val recording = profile
        recording?.recordFlatWave(stage, units)
        val start = System.nanoTime()
        recordPreparation(stage, start, recording)
        try {
            var busy = 0L
            runMeasured(stage, tasks = units, regions = 0, participants = participants, isolation = 0, recording = recording) { watch, waitRecorder ->
                val wave = if (watch == null) tasks else watch.wrap(tasks)
                Interactions.runWave({ busy = pool.runWave(wave, waitRecorder, participants, homes) }, pool)
            }
            learn(flatNanosPerTask, stage.ordinal, busy, units)
        } finally {
            if (recording != null) {
                val elapsed = System.nanoTime() - start
                recording.recordStage(stage, elapsed)
                recording.recordPhase(dimension, "$stage execution", elapsed)
            }
        }
    }

    private fun runWave(stage: Stage, grid: RegionGrid, recording: TickProfile?) {
        val regions = grid.plan(pool.workerCount + 1, isolation)
        recording?.recordWave(stage, regions)
        var participants = participants(regions.size, grid.estimatedNanos)
        val limit = spawnParticipants
        if (stage == Stage.SPAWNING && limit != null) participants = minOf(participants, limit)
        runMeasured(stage, grid.taskCount, regions.size, participants, isolation, recording) { watch, waitRecorder ->
            val start = System.nanoTime()
            var tasks: List<Runnable> = if (watch == null) regions else watch.wrap(regions)
            val durations = if (recording != null) LongArray(tasks.size) else null
            if (durations != null) {
                // Profiling only: each region records its own run time.
                val timed = ArrayList<Runnable>(tasks.size)
                for (index in tasks.indices) {
                    val task = tasks[index]
                    timed.add(Runnable {
                        val regionStart = System.nanoTime()
                        try {
                            task.run()
                        } finally {
                            durations[index] = System.nanoTime() - regionStart
                        }
                    })
                }
                tasks = timed
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
                val failure = wave.failure
                if (failure != null) throw RuntimeException("Vanadium worker task failed", failure)
            } finally {
                grid.recordWave(if (busy >= 0) busy else System.nanoTime() - start)
                if (recording != null && durations != null) recording.recordRegions(stage, durations)
            }
        }
    }

    /** Records the time since [stage]'s [begin] or last wave as its preparation phase. */
    private fun recordPreparation(stage: Stage, now: Long, recording: TickProfile?) {
        val preparedAt = preparationStarts.remove(stage)
        if (recording != null && preparedAt != null) recording.recordPhase(dimension, "$stage preparation", now - preparedAt)
    }

    /**
     * The bookkeeping every stage wave shares around [body]: the watchdog's watch (null unless wave diagnostics
     * are on), the caller's tail wait for the profile, the wave's capacity and its JFR event. A flat wave has no
     * [regions] and no [isolation] (0).
     */
    private inline fun runMeasured(stage: Stage, tasks: Int, regions: Int, participants: Int, isolation: Int, recording: TickProfile?,
                                   body: (watch: WaveDiagnostics.Watch?, waitRecorder: LongConsumer?) -> Unit) {
        val waitRecorder = if (recording != null) LongConsumer { nanos -> recording.recordWait(stage, nanos) } else null
        val event = WaveEvent()
        event.begin()
        val start = System.nanoTime()
        val watch = diagnostics?.watch(stage, dimension, isolation)
        try {
            body(watch, waitRecorder)
        } finally {
            watch?.close()
            recording?.recordWaveCapacity(stage, System.nanoTime() - start, participants)
            if (event.shouldCommit()) {
                event.stage = stage.name
                event.dimension = dimension
                event.regions = regions
                event.tasks = tasks
                event.participants = participants
                event.isolation = isolation
                event.commit()
            }
        }
    }

    companion object {
        /**
         * Average region work above which dynamic claiming beats dataflow ordering: measured on 26.3, heavy
         * entity regions finished about 6% sooner when claimed, cheap random-tick regions about 2x sooner in
         * dataflow order.
         */
        private const val CLAIM_MIN_REGION_NANOS = 100_000.0

        /**
         * Default cost of involving one more thread in a wave, in nanoseconds (`vanadium.participantCostNanos`, 0 to
         * 1,000,000; 0 always uses every worker). See [participants] and the wake-latency cases of WorkerPoolBench.
         */
        private val PARTICIPANT_COST_NANOS =
            DeveloperProperties.long("vanadium.participantCostNanos", 2_000, 0L..1_000_000L).toDouble()

        private const val COST_SMOOTHING = 0.2

        /**
         * Default [flatParticipants] share (`vanadium.criticalPathShare`): the other threads finish within this share
         * of the longest task's expected time, leaving room for its late start and for estimates that run short.
         * Valid values are above 0 and at most 1 (beyond 1 the bound would knowingly lengthen the wave past its longest
         * task), or exactly 0, which turns the bound off: the measured "cap disabled" comparison.
         */
        private val CRITICAL_PATH_SHARE = DeveloperProperties.double(
            "vanadium.criticalPathShare", 0.8, "0 (no bound) or a number above 0 and at most 1",
        ) { it >= 0.0 && it <= 1.0 }

        /** JVM property for [spawnParticipants], read at server start ([DeveloperProperties.participantLimit]). */
        const val SPAWN_PARTICIPANTS_PROPERTY = "vanadium.spawnParticipants"
    }
}

/** The sequential parts of a world tick, in vanilla order. */
enum class Stage {
    SCHEDULED_TICK,
    SPAWNING,
    CHUNK,
    TRACKING,
    ENTITY,
    BLOCK_ENTITY,
}

/** Independent jobs run through [TickScheduler.prepare], named for profiles and JFR events. */
enum class PreparationKind {
    /** Entity stage collection: chunk keys and tasks for large entity lists. */
    ENTITY_BATCH,

    /** Random-tick collection: ticking chunk keys and tasks. */
    CHUNK_BATCH,

    /** Natural spawn counts over entity slices. */
    SPAWN_COUNTS,

    /** Tracker movement scans before the area map is updated. */
    TRACKING_MOVES,

    /** Per-player tracking visibility diffs. */
    TRACKING_DIFFS,

    /** Chunk section serialization for a player's chunk batch. */
    CHUNK_PACKETS,
}
