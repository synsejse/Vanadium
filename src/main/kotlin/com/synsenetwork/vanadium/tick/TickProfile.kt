package com.synsenetwork.vanadium.tick

import java.util.EnumMap
import java.util.Locale
import kotlin.math.ceil

/** Bounded samples aggregated on the server thread after each completion barrier. */
class TickProfile {
    private val ticks = Samples()
    private val stages = EnumMap<Stage, StageStats>(Stage::class.java).apply { Stage.entries.forEach { put(it, StageStats()) } }
    private val phases = LinkedHashMap<String, Samples>()

    fun recordTick(nanos: Long) = ticks.add(nanos)

    fun isFull(): Boolean = ticks.size == MAX_SAMPLES

    fun recordStage(stage: Stage, nanos: Long) = stats(stage).durations.add(nanos)

    fun recordPhase(dimension: String, phase: String, nanos: Long) =
        phases.getOrPut("$dimension / $phase") { Samples() }.add(nanos)

    fun recordCells(stage: Stage, durations: LongArray) {
        val cells = stats(stage).cellDurations
        for (duration in durations) cells.add(duration)
    }

    fun recordWait(stage: Stage, nanos: Long) {
        stats(stage).waitNanos += nanos
    }

    fun recordWave(stage: Stage, cells: List<Cell>) {
        if (cells.isEmpty()) return
        val stats = stats(stage)
        stats.waves++
        stats.cells += cells.size
        for (cell in cells) {
            stats.tasks += cell.taskCount
            stats.maxTasksPerCell = maxOf(stats.maxTasksPerCell, cell.taskCount)
        }
    }

    fun report(): String = buildString {
        append("Tick work (idle time excluded): ").append(ticks.summary())
        append("\nQueued stage execution across all worlds; collection and serial fallbacks excluded.")
        for ((stage, stats) in stages) {
            append(String.format(Locale.ROOT,
                "\n%s: %s; caller tail wait %.2fms total; waves=%d cells=%d tasks=%d; tasks/cell avg=%.1f max=%d",
                stage, stats.durations.summary(), stats.waitNanos / 1e6, stats.waves, stats.cells, stats.tasks,
                if (stats.cells == 0L) 0.0 else stats.tasks.toDouble() / stats.cells, stats.maxTasksPerCell))
            append("; cell execution ").append(stats.cellDurations.summary())
        }
        phases.forEach { (phase, samples) -> append('\n').append(phase).append(": ").append(samples.summary()) }
        append("\nPreparation includes collection and inline fallbacks; world totals include waves. Do not add overlapping totals.")
        append("\nCells count executions, not unique positions. Timings include profiling overhead.")
    }

    private fun stats(stage: Stage): StageStats = stages.getValue(stage)

    private class StageStats {
        val durations = Samples()
        val cellDurations = Samples()
        var waitNanos = 0L
        var waves = 0L
        var cells = 0L
        var tasks = 0L
        var maxTasksPerCell = 0
    }

    /** Stores the first [MAX_SAMPLES] durations; totals continue so truncation is explicit. */
    internal class Samples {
        private var values = LongArray(256)
        var size = 0
            private set
        private var count = 0L
        private var total = 0L

        fun add(nanos: Long) {
            count++
            total += nanos
            if (size == MAX_SAMPLES) return
            if (size == values.size) values = values.copyOf(minOf(MAX_SAMPLES, size * 2))
            values[size++] = nanos
        }

        fun summary(): String {
            if (size == 0) return "no samples"
            val sorted = values.copyOf(size).apply { sort() }
            return String.format(Locale.ROOT, "n=%d avg=%.3fms p50=%.3fms p95=%.3fms p99=%.3fms%s",
                count, total / (count * 1e6), percentile(sorted, 50) / 1e6,
                percentile(sorted, 95) / 1e6, percentile(sorted, 99) / 1e6,
                if (count > size) " (percentiles limited to first $size)" else "")
        }

        companion object {
            fun percentile(sorted: LongArray, percent: Int): Long = sorted[ceil(sorted.size * percent / 100.0).toInt() - 1]
        }
    }

    companion object {
        const val MAX_SAMPLES = 65_536
    }
}
