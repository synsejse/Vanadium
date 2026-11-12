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
    fun fixedSpinIgnoresTicks() {
        val policy = SpinPolicy(long, long, fixed = true)
        policy.tickEnded(45_000_000L, sprinting = false)
        assertEquals(long, policy.workerSpinNanos)
        policy.tickStarted()
        assertEquals(long, policy.workerSpinNanos)
    }
}
