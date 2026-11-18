package com.synsenetwork.vanadium.mixedbench

import java.lang.management.ManagementFactory

/**
 * `System.nanoTime()` as JVM uptime in nanoseconds: the clock of unified logging's `uptime`/`uptimenanos`
 * decorations (`-Xlog:...:uptimenanos`). Both count HotSpot's monotonic elapsed counter from VM start, which
 * on Linux is the same `CLOCK_MONOTONIC` as `nanoTime`, so one offset relates them. The offset is measured
 * once, at the edge where `RuntimeMXBean.getUptime()` (whole milliseconds of that counter) steps; [uncertainty]
 * is half the interval that edge was bracketed by.
 */
internal class UptimeClock(private val origin: Long, val uncertainty: Long) {
    fun now(): Long = System.nanoTime() - origin

    companion object {
        fun calibrate(): UptimeClock {
            val runtime = ManagementFactory.getRuntimeMXBean()
            var best: UptimeClock? = null
            // Several edges; keep the most tightly bracketed one (a preempted read only widens its own bracket).
            repeat(5) {
                var previousStart = System.nanoTime()
                var previous = runtime.uptime
                while (true) {
                    val start = System.nanoTime()
                    val uptime = runtime.uptime
                    val end = System.nanoTime()
                    if (uptime != previous) {
                        if (uptime == previous + 1) {
                            // The millisecond boundary was crossed after the previous read began and before this read ended.
                            val half = (end - previousStart) / 2
                            val clock = UptimeClock(previousStart + half - uptime * 1_000_000, half)
                            if (best.let { it == null || clock.uncertainty < it.uncertainty }) best = clock
                        }
                        break
                    }
                    previousStart = start
                    previous = uptime
                }
            }
            return best ?: error("JVM uptime did not advance by one millisecond during calibration")
        }
    }
}

/** Per-tick samples between two boundaries, buffered as primitives and written when the batch ends. */
internal class TickLog(capacity: Int = 1024) {
    var size = 0
        private set
    var ids = IntArray(capacity)
        private set
    /** Vanilla's `tickServer` duration (what MSPT tools show). */
    var durations = LongArray(capacity)
        private set
    /** [UptimeClock] time at which the tick completed (the mixed bench's END_SERVER_TICK hook). */
    var ends = LongArray(capacity)
        private set

    fun add(id: Int, durationNanos: Long, endNanos: Long) {
        if (size == ids.size) {
            ids = ids.copyOf(size * 2)
            durations = durations.copyOf(size * 2)
            ends = ends.copyOf(size * 2)
        }
        ids[size] = id
        durations[size] = durationNanos
        ends[size] = endNanos
        size++
    }

    fun clear() {
        size = 0
    }
}

/**
 * Completion-time statistics of one measured window of `n` ticks. Its boundary `t0` is the completion of the tick
 * just before the window (the last warmup tick, or the previous window's last tick), so consecutive windows tile
 * the timeline: completion `t(j)` for `j = 1..n` and `t(0) = t0`.
 *
 * - span: `t(n) - t(0)`; completion TPS is `n / span`.
 * - gap: `t(j) - t(j-1)` for `j = 1..n` (the first gap starts at the boundary); a gap contains the tick, the
 *   pacing wait and everything the server thread did between ticks.
 * - rolling TPS over `k` ticks: `k / (t(j) - t(j-k))` for every complete rolling window `j = k..n`; none crosses
 *   the window's boundary, and a window shorter than `k` ticks has none (null).
 * - over 50/100 ms: ticks whose `tickServer` duration is strictly greater.
 */
internal class WindowTiming(boundary: Long, log: TickLog) {
    val ticks = log.size
    /** First and last tick IDs (`MinecraftServer.tickCount`), or -1 for an empty window. */
    val firstTick = if (ticks == 0) -1 else log.ids[0]
    val lastTick = if (ticks == 0) -1 else log.ids[ticks - 1]
    val spanNanos = if (ticks == 0) 0L else log.ends[ticks - 1] - boundary
    val over50 = (0 until ticks).count { log.durations[it] > 50_000_000L }
    val over100 = (0 until ticks).count { log.durations[it] > 100_000_000L }
    var maxGapNanos = 0L
        private set
    /** Tick whose completion ends the longest gap, or -1 for an empty window. */
    var maxGapTick = -1
        private set
    /** Tick with the longest `tickServer` duration, or -1. */
    var longestTick = -1
        private set
    val rollingMin = ROLLING_TICKS.map { minRollingTps(boundary, log, it) }

    init {
        var longest = -1L
        for (i in 0 until ticks) {
            val gap = log.ends[i] - (if (i == 0) boundary else log.ends[i - 1])
            if (gap > maxGapNanos || maxGapTick == -1) {
                maxGapNanos = gap
                maxGapTick = log.ids[i]
            }
            if (log.durations[i] > longest) {
                longest = log.durations[i]
                longestTick = log.ids[i]
            }
        }
    }

    /** CSV/JSON columns; null where a window has no complete rolling window. */
    fun columns(): Map<String, Number?> = linkedMapOf<String, Number?>(
        "first_tick" to firstTick,
        "last_tick" to lastTick,
        "completion_span_ms" to spanNanos / 1e6,
        "completion_tps" to if (spanNanos > 0) ticks * 1e9 / spanNanos else 0.0,
        "ticks_over_50ms" to over50,
        "ticks_over_100ms" to over100,
        "tick_max_id" to longestTick,
        "max_gap_ms" to maxGapNanos / 1e6,
        "max_gap_tick" to maxGapTick,
    ).apply { ROLLING_TICKS.forEachIndexed { i, k -> put("rolling_tps_min_${k}t", rollingMin[i]) } }

    companion object {
        /** 20 ticks (one second at 20 TPS) and 100 ticks (vanilla's MSPT average). */
        val ROLLING_TICKS = intArrayOf(20, 100)

        fun minRollingTps(boundary: Long, log: TickLog, k: Int): Double? {
            require(k > 0)
            if (log.size < k) return null
            var longest = 0L
            for (j in k..log.size) {
                val start = if (j - k == 0) boundary else log.ends[j - k - 1]
                longest = maxOf(longest, log.ends[j - 1] - start)
            }
            return k * 1e9 / maxOf(1L, longest)
        }
    }
}
