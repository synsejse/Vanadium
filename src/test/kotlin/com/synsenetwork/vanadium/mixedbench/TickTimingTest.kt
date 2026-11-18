package com.synsenetwork.vanadium.mixedbench

import java.lang.management.ManagementFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TickTimingTest {
    private val ms = 1_000_000L

    /** Ticks 101.. completing at [ends] (ms after a boundary at 1,000 ms), each taking [durations] ms of tickServer. */
    private fun log(ends: List<Long>, durations: List<Long> = ends.map { 10L }) = TickLog(capacity = 2).apply {
        ends.forEachIndexed { i, end -> add(101 + i, durations[i] * ms, (1_000 + end) * ms) }
    }

    @Test
    fun gapsSpanAndCountsStartAtTheWindowBoundary() {
        // The first gap (boundary to first completion) is the longest; over-thresholds are strictly greater.
        val timing = WindowTiming(1_000 * ms, log(listOf(120, 170, 220, 270), listOf(50, 51, 100, 101)))
        assertEquals(4, timing.ticks)
        assertEquals(270 * ms, timing.spanNanos)
        assertEquals(120 * ms, timing.maxGapNanos)
        assertEquals(101, timing.maxGapTick)
        assertEquals(104, timing.longestTick)
        assertEquals(3, timing.over50)
        assertEquals(1, timing.over100)
        val columns = timing.columns()
        assertEquals(101, columns["first_tick"])
        assertEquals(104, columns["last_tick"])
        assertEquals(4 * 1e9 / (270 * ms), columns["completion_tps"])
    }

    @Test
    fun rollingTpsUsesOnlyCompleteWindowsInsideTheBoundary() {
        // 25 ticks at 50 ms, except one 300 ms gap ending at the 10th tick.
        val ends = ArrayList<Long>()
        var t = 0L
        for (i in 1..25) {
            t += if (i == 10) 300 else 50
            ends.add(t)
        }
        val log = log(ends)
        // Complete 20-tick windows are j = 20..25; every one contains the 300 ms gap: 19 * 50 + 300 ms.
        assertEquals(20 * 1e9 / ((19 * 50 + 300) * ms), WindowTiming.minRollingTps(1_000 * ms, log, 20)!!)
        // A one-tick rolling window is the longest gap.
        assertEquals(1e9 / (300 * ms), WindowTiming.minRollingTps(1_000 * ms, log, 1)!!)
        // All 25 ticks: the first rolling window starts at the boundary itself.
        assertEquals(25 * 1e9 / (t * ms), WindowTiming.minRollingTps(1_000 * ms, log, 25)!!)
        assertNull(WindowTiming.minRollingTps(1_000 * ms, log, 26))
        val columns = WindowTiming(1_000 * ms, log).columns()
        assertEquals(columns["rolling_tps_min_20t"], WindowTiming.minRollingTps(1_000 * ms, log, 20))
        assertTrue("rolling_tps_min_100t" in columns && columns["rolling_tps_min_100t"] == null)
    }

    @Test
    fun emptyWindowsReportNoTicksRatherThanFailing() {
        val timing = WindowTiming(5 * ms, TickLog())
        assertEquals(0, timing.ticks)
        assertEquals(-1, timing.maxGapTick)
        assertEquals(0.0, timing.columns()["completion_tps"])
        assertNull(timing.columns()["rolling_tps_min_20t"])
    }

    @Test
    fun tickLogGrowsAndClearsWithoutLosingSamples() {
        val log = TickLog(capacity = 1)
        for (i in 0 until 1_000) log.add(i, i * 2L, i * 3L)
        assertEquals(1_000, log.size)
        assertEquals(999, log.ids[999])
        assertEquals(1_998L, log.durations[999])
        assertEquals(2_997L, log.ends[999])
        log.clear()
        assertEquals(0, log.size)
    }

    @Test
    fun uptimeClockMatchesTheJvmUptimeMilliseconds() {
        val clock = UptimeClock.calibrate()
        assertTrue(clock.uncertainty in 0 until 1_000_000, "uncertainty ${clock.uncertainty} ns")
        val runtime = ManagementFactory.getRuntimeMXBean()
        repeat(100) {
            val before = clock.now()
            val uptime = runtime.uptime
            val after = clock.now()
            // getUptime truncates to whole milliseconds of the same counter.
            val slack = clock.uncertainty + 1
            assertTrue(uptime * 1_000_000 <= after + slack && before - slack < (uptime + 1) * 1_000_000,
                "uptime $uptime ms outside [$before, $after] ns ± $slack")
        }
    }
}
