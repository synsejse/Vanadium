package com.synsenetwork.vanadium.live

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

    /** A `System.nanoTime()` reading on this clock. */
    fun at(nanoTime: Long): Long = nanoTime - origin

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
    /** [UptimeClock] time at which the tick completed (the fixture's END_SERVER_TICK hook). */
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
