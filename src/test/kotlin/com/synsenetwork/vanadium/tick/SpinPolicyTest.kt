package com.synsenetwork.vanadium.tick

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SpinPolicyTest {
    private val long = 2_000_000L
    private val short = 500_000L

    @Test
    fun spinsLongWithoutTickEvents() {
        val policy = SpinPolicy(long, short, fixed = false)
        assertEquals(long, policy.workerSpinNanos)
        assertTrue(policy.inTick && policy.underPressure)
    }

    @Test
    fun parksBetweenTicksWithSlackAndSpinsShortInsideThem() {
        val policy = SpinPolicy(long, short, fixed = false)
        policy.tickEnded(45_000_000L, sprinting = false)
        assertFalse(policy.underPressure)
        assertEquals(0L, policy.workerSpinNanos, "workers kept spinning through a sleep")
        policy.tickStarted()
        assertEquals(short, policy.workerSpinNanos)
        policy.tickEnded(long, sprinting = false) // Exactly a long spin of slack still parks.
        assertEquals(0L, policy.workerSpinNanos)
    }

    @Test
    fun spinsLongThroughEveryGapUnderPressure() {
        val policy = SpinPolicy(long, short, fixed = false)
        for ((slack, sprinting) in listOf(-5_000_000L to false, 0L to false, long - 1 to false, 45_000_000L to true)) {
            policy.tickEnded(45_000_000L, sprinting = false)
            policy.tickEnded(slack, sprinting)
            assertTrue(policy.underPressure, "slack $slack, sprinting $sprinting")
            assertEquals(long, policy.workerSpinNanos, "between ticks: slack $slack, sprinting $sprinting")
            policy.tickStarted()
            assertEquals(long, policy.workerSpinNanos, "inside a tick: slack $slack, sprinting $sprinting")
        }
        // One tick with slack is enough to relax again.
        policy.tickEnded(45_000_000L, sprinting = false)
        assertEquals(0L, policy.workerSpinNanos)
        policy.tickStarted()
        assertEquals(short, policy.workerSpinNanos)
    }

    @Test
    fun balancedWakesEarlyAndTreatsAGapShorterThanTheLeadAsPressure() {
        val lead = 5_000_000L
        val policy = SpinPolicy(long, short, fixed = false, leadNanos = lead)
        assertEquals(IdleWorkers.BALANCED, policy.mode)
        policy.tickEnded(45_000_000L, sprinting = false)
        assertEquals(SpinPolicy.Gap.WAKE_EARLY, policy.betweenTicks)
        assertEquals(0L, policy.workerSpinNanos)
        // Waking 5 ms early into a 4 ms gap means not parking at all.
        policy.tickEnded(lead - 1, sprinting = false)
        assertEquals(SpinPolicy.Gap.PRESSURE, policy.betweenTicks)
        assertEquals(long, policy.workerSpinNanos)
        policy.tickEnded(45_000_000L, sprinting = true)
        assertEquals(SpinPolicy.Gap.PRESSURE, policy.betweenTicks)
        // No lead: parking until the tick starts is all that is left.
        val noLead = SpinPolicy(long, short, fixed = false, leadNanos = 0L)
        noLead.tickEnded(45_000_000L, sprinting = false)
        assertEquals(SpinPolicy.Gap.PARK, noLead.betweenTicks)
    }

    @Test
    fun hotSpinsThroughGapsAndLeanParks() {
        val hot = 50_000_000L
        val policy = SpinPolicy(long, short, fixed = false, hotNanos = hot)
        policy.configure(IdleWorkers.HOT)
        policy.tickStarted()
        assertEquals(hot, policy.workerSpinNanos, "hot workers parked between waves of a tick")
        policy.tickEnded(45_000_000L, sprinting = false)
        assertEquals(SpinPolicy.Gap.SPIN_THROUGH, policy.betweenTicks)
        assertTrue(policy.spinUntil.get() - System.nanoTime() > 40_000_000L, "spin-through ends before the next tick")
        // A frozen or idle tick ran nothing in parallel: nothing to keep hot.
        policy.tickEnded(45_000_000L, sprinting = false, ranParallel = false)
        assertEquals(SpinPolicy.Gap.PARK, policy.betweenTicks)
        assertTrue(policy.spinUntil.get() - System.nanoTime() <= 0, "parking left a spin-through in place")
        policy.tickEnded(-1, sprinting = false)
        assertEquals(SpinPolicy.Gap.PRESSURE, policy.betweenTicks)
        assertEquals(hot, policy.workerSpinNanos)

        policy.tickEnded(45_000_000L, sprinting = false)
        policy.configure(IdleWorkers.LEAN)
        policy.tickStarted()
        assertEquals(short, policy.workerSpinNanos)
        policy.tickEnded(45_000_000L, sprinting = false)
        assertEquals(SpinPolicy.Gap.PARK, policy.betweenTicks)
        assertEquals(0L, policy.workerSpinNanos)
    }

    @Test
    fun serverSpinsBeforeTicksThatRanParallelWork() {
        val policy = SpinPolicy(long, short, fixed = false, serverLeadNanos = 5_000_000L)
        policy.tickEnded(45_000_000L, sprinting = false)
        assertEquals(5_000_000L, policy.serverWindowNanos)
        policy.tickEnded(45_000_000L, sprinting = false, ranParallel = false)
        assertEquals(0L, policy.serverWindowNanos, "an idle or frozen tick made the server spin")
        policy.configure(IdleWorkers.HOT)
        policy.tickEnded(45_000_000L, sprinting = false)
        assertEquals(Long.MAX_VALUE, policy.serverWindowNanos)
        policy.configure(IdleWorkers.LEAN)
        policy.tickEnded(45_000_000L, sprinting = false)
        assertEquals(0L, policy.serverWindowNanos)
    }

    @Test
    fun aForcedModeIgnoresConfiguration() {
        val policy = SpinPolicy(long, short, fixed = false, forced = IdleWorkers.LEAN)
        policy.configure(IdleWorkers.HOT)
        assertEquals(IdleWorkers.LEAN, policy.mode)
        policy.tickEnded(45_000_000L, sprinting = false)
        assertEquals(SpinPolicy.Gap.PARK, policy.betweenTicks)
    }

    @Test
    fun modesParseCaseInsensitively() {
        assertEquals(IdleWorkers.HOT, IdleWorkers.parse(" Hot "))
        assertEquals(IdleWorkers.BALANCED, IdleWorkers.parse("balanced"))
        assertEquals(null, IdleWorkers.parse("warm"))
        assertEquals(listOf("balanced", "hot", "lean"), IdleWorkers.names)
    }

    @Test
    fun fixedSpinIgnoresTicks() {
        val policy = SpinPolicy(long, long, fixed = true)
        policy.tickEnded(45_000_000L, sprinting = false)
        assertEquals(long, policy.workerSpinNanos)
        policy.tickStarted()
        assertEquals(long, policy.workerSpinNanos)
        policy.configure(IdleWorkers.HOT)
        policy.tickEnded(45_000_000L, sprinting = false)
        assertEquals(SpinPolicy.Gap.PRESSURE, policy.betweenTicks, "a fixed spin started a timed wake or a spin-through")
        assertEquals(long, policy.workerSpinNanos)
    }
}
