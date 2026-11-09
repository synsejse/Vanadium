package com.synsenetwork.vanadium.tick

import com.google.gson.JsonObject
import java.util.EnumMap
import kotlin.math.ceil

/** Bounded samples aggregated on the server thread after each completion barrier. */
class TickProfile {
    private val ticks = Samples()
    private val stages = EnumMap<Stage, StageStats>(Stage::class.java).apply { Stage.entries.forEach { put(it, StageStats()) } }
    private val preparations = EnumMap<Preparation, PreparationStats>(Preparation::class.java)
    private val phases = LinkedHashMap<String, Samples>()

    fun recordTick(nanos: Long) = ticks.add(nanos)

    fun isFull(): Boolean = ticks.size == MAX_SAMPLES

    fun recordStage(stage: Stage, nanos: Long) = stats(stage).durations.add(nanos)

    fun recordPhase(dimension: String, phase: String, nanos: Long) =
        phases.getOrPut("$dimension / $phase") { Samples() }.add(nanos)

    fun recordRegions(stage: Stage, durations: LongArray) {
        val regions = stats(stage).regionDurations
        for (duration in durations) regions.add(duration)
    }

    fun recordWait(stage: Stage, nanos: Long) {
        stats(stage).waitNanos += nanos
    }

    /** Wall time of one wave and the threads able to claim its regions; the denominator of utilization. */
    fun recordWaveCapacity(stage: Stage, nanos: Long, participants: Int) {
        stats(stage).capacityNanos += nanos * participants
    }

    fun recordPreparation(kind: Preparation, jobs: Int, nanos: Long) {
        val stats = preparations.getOrPut(kind) { PreparationStats() }
        stats.durations.add(nanos)
        stats.jobs += jobs
    }

    fun recordWave(stage: Stage, regions: List<Region>) {
        if (regions.isEmpty()) return
        val stats = stats(stage)
        stats.waves++
        stats.regions += regions.size
        for (region in regions) {
            stats.tasks += region.taskCount
            stats.maxTasksPerRegion = maxOf(stats.maxTasksPerRegion, region.taskCount)
        }
    }

    /** Shares of tick work spent in stage waves, in preparation waves, and on the server thread alone. */
    private class Split(val tick: Long, val waves: Long, val preparation: Long) {
        val serial: Long get() = maxOf(0, tick - waves - preparation)

        fun share(nanos: Long): Double = if (tick == 0L) 0.0 else nanos.toDouble() / tick
    }

    private fun tickSplit() = Split(ticks.totalNanos, stages.values.sumOf { it.durations.totalNanos },
        preparations.values.sumOf { it.durations.totalNanos })

    /** Complete capture data. Times are milliseconds; shares are fractions. */
    fun toJson(): JsonObject = JsonObject().apply {
        add("ticks", ticks.toJson())
        val split = tickSplit()
        add("split", JsonObject().apply {
            addProperty("stageWaves", split.share(split.waves))
            addProperty("preparation", split.share(split.preparation))
            addProperty("serial", split.share(split.serial))
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
                stats.utilization()?.let { addProperty("utilization", it) }
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

    private fun stats(stage: Stage): StageStats = stages.getValue(stage)

    private class StageStats {
        val durations = Samples()
        val regionDurations = Samples()
        var waitNanos = 0L
        var capacityNanos = 0L
        var waves = 0L
        var regions = 0L
        var tasks = 0L
        var maxTasksPerRegion = 0

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
            val sorted = sorted()
            addProperty("avgMs", totalNanos / (count * 1e6))
            addProperty("p50Ms", percentile(sorted, 50) / 1e6)
            addProperty("p95Ms", percentile(sorted, 95) / 1e6)
            addProperty("p99Ms", percentile(sorted, 99) / 1e6)
            addProperty("percentileSamples", size)
        }

        private fun sorted(): LongArray = values.copyOf(size).apply { sort() }

        companion object {
            fun percentile(sorted: LongArray, percent: Int): Long = sorted[ceil(sorted.size * percent / 100.0).toInt() - 1]
        }
    }

    companion object {
        const val MAX_SAMPLES = 65_536
    }
}
