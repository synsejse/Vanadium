package com.synsenetwork.vanadium.coldexplosion

import com.synsenetwork.vanadium.live.TickLog
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BlastTimingTest {
    private val ms = 1_000_000L

    /** Ticks 41.. taking [durations] ms each and completing every 50 ms from 1,000 ms. */
    private fun series(vararg durations: Long) = TickSeries(TickLog(capacity = 2).apply {
        durations.forEachIndexed { i, d -> add(41 + i, d * ms, (1_000 + 50 * i) * ms) }
    })

    @Test
    fun ticksAreFoundByIdWithTheirGapsAndStatistics() {
        val ticks = series(10, 12, 300, 11, 9)
        assertEquals(300 * ms, ticks.nanos(43))
        assertEquals(50 * ms, ticks.gapNanos(43))
        // Median of 10, 12, 300 is 12; of four values the upper middle.
        assertEquals(12 * ms, ticks.median(41, 43))
        assertEquals(12 * ms, ticks.median(41, 44))
        assertEquals(300 * ms, ticks.max(42, 45))
        assertTrue(ticks.contains(45))
        assertFalse(ticks.contains(46))
        assertFailsWith<IllegalArgumentException> { ticks.nanos(40) }
        // The first tick has no recorded previous completion.
        assertFailsWith<IllegalArgumentException> { ticks.gapNanos(41) }
    }

    @Test
    fun anUptimeBelongsToTheTickWhoseGapEndsAtOrAfterIt() {
        val ticks = series(10, 10, 10)
        assertEquals(42, ticks.tickAt(1_000 * ms + 1))
        assertEquals(42, ticks.tickAt(1_050 * ms))
        assertEquals(43, ticks.tickAt(1_050 * ms + 1))
        assertEquals(-1, ticks.tickAt(1_000 * ms))
        assertEquals(-1, ticks.tickAt(1_200 * ms))
    }

    @Test
    fun classLoadLinesAreParsedOnTheUptimeClock() {
        val loads = ClassLoads.parse(sequenceOf(
            "[1000ns] java.lang.Object source: shared objects file",
            "not a class load line",
            "[2500ns] net.minecraft.world.level.ServerExplosion source: jar:file:/minecraft.jar",
            "[3000ns] net.caffeinemc.mods.lithium.common.explosion.DirectMappedExplosionBlockCache source: lithium",
        ))
        assertEquals(3, loads.size)
        assertEquals(2500L, loads.firstLoad("net.minecraft.world.level.ServerExplosion"))
        assertNull(loads.firstLoad("net.minecraft.world.level.Missing"))
        // After the start, up to and including the end.
        assertEquals(listOf(1, 2), loads.between(1000, 3000))
        assertEquals(emptyList(), loads.between(3000, 4000))
    }
}
