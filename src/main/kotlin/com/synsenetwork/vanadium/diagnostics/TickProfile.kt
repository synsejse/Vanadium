package com.synsenetwork.vanadium.diagnostics

import com.google.gson.JsonObject
import com.synsenetwork.vanadium.tick.PreparationKind
import com.synsenetwork.vanadium.tick.Region
import com.synsenetwork.vanadium.tick.Stage
import java.util.EnumMap
import kotlin.math.ceil

/** Bounded samples aggregated on the server thread after each completion barrier. */
class TickProfile {
    private val ticks = Samples()
    private val stages = EnumMap<Stage, StageStats>(Stage::class.java).apply { Stage.entries.forEach { put(it, StageStats()) } }
    private val preparations = EnumMap<PreparationKind, PreparationStats>(PreparationKind::class.java)
    private val phases = LinkedHashMap<String, Samples>()

    fun recordTick(nanos: Long) = ticks.add(nanos)

    fun isFull(): Boolean = ticks.size == MAX_SAMPLES

    fun recordStage(stage: Stage, nanos: Long) = stages.getValue(stage).durations.add(nanos)

    fun recordPhase(dimension: String, phase: String, nanos: Long) =
        phases.getOrPut("$dimension / $phase") { Samples() }.add(nanos)

    fun recordRegions(stage: Stage, durations: LongArray) {
        val regions = stages.getValue(stage).regionDurations
        for (duration in durations) regions.add(duration)
    }

    fun recordWait(stage: Stage, nanos: Long) {
        stages.getValue(stage).waitNanos += nanos
    }

    /** Wall time of one wave and the threads able to claim its regions; the denominator of utilization. */
    fun recordWaveCapacity(stage: Stage, nanos: Long, participants: Int) {
        val stats = stages.getValue(stage)
        stats.capacityNanos += nanos * participants
        stats.participantWaves++
        stats.participants += participants
    }

    fun recordPreparation(kind: PreparationKind, jobs: Int, nanos: Long) {
        val stats = preparations.getOrPut(kind) { PreparationStats() }
        stats.durations.add(nanos)
        stats.jobs += jobs
    }

    /** A wave with no regions: every task ran on its own. */
    fun recordFlatWave(stage: Stage, tasks: Int) {
        val stats = stages.getValue(stage)
        stats.waves++
        stats.tasks += tasks
    }

    /**
     * The groups a flat wave's tasks were claimed in: how many, the longest one's time and how long after the wave's
     * start it started, and how many had not finished when the dispatching thread ran out of groups to claim (it
     * waited for those).
     */
    fun recordFlatGroups(stage: Stage, groups: Int, longestNanos: Long, longestStartNanos: Long, runningAtTail: Int) {
        val stats = stages.getValue(stage)
        stats.groups += groups
        stats.longestGroups.add(longestNanos)
        stats.longestGroupStarts.add(longestStartNanos)
        stats.groupsRunningAtTail += runningAtTail
    }

    fun recordWave(stage: Stage, regions: List<Region>) {
        if (regions.isEmpty()) return
        val stats = stages.getValue(stage)
        stats.waves++
        stats.regions += regions.size
        for (region in regions) {
            stats.tasks += region.taskCount
            stats.maxTasksPerRegion = maxOf(stats.maxTasksPerRegion, region.taskCount)
        }
    }

    /** Complete capture data. Times are milliseconds; shares are fractions. */
    fun toJson(): JsonObject = JsonObject().apply {
        add("ticks", ticks.toJson())
        add("split", JsonObject().apply {
            val tickNanos = ticks.totalNanos
            if (tickNanos == 0L) {
                addProperty("stageWaves", 0.0)
                addProperty("preparation", 0.0)
                addProperty("serial", 0.0)
            } else {
                val stageNanos = stages.values.sumOf { it.durations.totalNanos }
                val preparationNanos = preparations.values.sumOf { it.durations.totalNanos }
                val serialNanos = maxOf(0, tickNanos - stageNanos - preparationNanos)
                addProperty("stageWaves", stageNanos.toDouble() / tickNanos)
                addProperty("preparation", preparationNanos.toDouble() / tickNanos)
                addProperty("serial", serialNanos.toDouble() / tickNanos)
            }
        })
        add("stages", JsonObject().apply {
            for ((stage, stats) in stages) add(stage.name, JsonObject().apply {
                add("execution", stats.durations.toJson())
                add("regionExecution", stats.regionDurations.toJson())
                addProperty("callerTailWaitMs", stats.waitNanos / 1e6)
                addProperty("waves", stats.waves)
                addProperty("regions", stats.regions)
                addProperty("tasks", stats.tasks)
                addProperty("maxTasksPerRegion", stats.maxTasksPerRegion)
                val utilization = stats.utilization()
                if (utilization != null) addProperty("utilization", utilization)
                if (stats.participantWaves > 0) addProperty("avgParticipants", stats.participants.toDouble() / stats.participantWaves)
                if (stats.longestGroups.count > 0) {
                    addProperty("groups", stats.groups)
                    // Per wave: the longest group, and the groups other threads were still running when the caller's tail wait began.
                    add("longestGroup", stats.longestGroups.toJson())
                    add("longestGroupStart", stats.longestGroupStarts.toJson())
                    addProperty("groupsRunningAtTailStart", stats.groupsRunningAtTail.toDouble() / stats.longestGroups.count)
                }
            })
        })
        add("preparations", JsonObject().apply {
            for ((kind, stats) in preparations) add(kind.name, JsonObject().apply {
                add("duration", stats.durations.toJson())
                addProperty("jobs", stats.jobs)
            })
        })
        add("phases", JsonObject().apply { phases.forEach { (phase, samples) -> add(phase, samples.toJson()) } })
    }

    private class StageStats {
        val durations = Samples()
        val regionDurations = Samples()
        var waitNanos = 0L
        var capacityNanos = 0L
        var waves = 0L
        var regions = 0L
        var tasks = 0L
        var maxTasksPerRegion = 0
        var groups = 0L
        val longestGroups = Samples()
        val longestGroupStarts = Samples()
        var groupsRunningAtTail = 0L
        var participantWaves = 0L
        var participants = 0L

        fun utilization(): Double? =
            if (capacityNanos == 0L) null else minOf(1.0, regionDurations.totalNanos.toDouble() / capacityNanos)
    }

    private class PreparationStats {
        val durations = Samples()
        var jobs = 0L
    }

    /** Stores the first [MAX_SAMPLES] durations; totals continue so truncation is explicit. */
    internal class Samples {
        private var values = LongArray(256)
        var size = 0
            private set
        var count = 0L
            private set
        var totalNanos = 0L
            private set

        fun add(nanos: Long) {
            count++
            totalNanos += nanos
            if (size == MAX_SAMPLES) return
            if (size == values.size) values = values.copyOf(minOf(MAX_SAMPLES, size * 2))
            values[size++] = nanos
        }

        fun toJson(): JsonObject = JsonObject().apply {
            addProperty("count", count)
            addProperty("totalMs", totalNanos / 1e6)
            if (size == 0) return@apply
            val sorted = values.copyOf(size)
            sorted.sort()
            addProperty("avgMs", totalNanos / (count * 1e6))
            addProperty("p50Ms", percentile(sorted, 50) / 1e6)
            addProperty("p95Ms", percentile(sorted, 95) / 1e6)
            addProperty("p99Ms", percentile(sorted, 99) / 1e6)
            addProperty("percentileSamples", size)
        }

        companion object {
            fun percentile(sorted: LongArray, percent: Int): Long = sorted[ceil(sorted.size * percent / 100.0).toInt() - 1]
        }
    }

    companion object {
        const val MAX_SAMPLES = 65_536
    }
}
