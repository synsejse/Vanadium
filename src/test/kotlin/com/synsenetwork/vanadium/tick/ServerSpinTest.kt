package com.synsenetwork.vanadium.tick

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ServerSpinTest {
    private val window = 5_000_000L
    private val due = 1_000_000_000L

    @AfterTest
    fun disarm() = ServerSpin.disarm()

    @Test
    fun spinsOnlyInTheWindowBeforeTheArmedTick() {
        ServerSpin.arm(due, window)
        // Far from the tick: park, but only until the window opens.
        assertFalse(ServerSpin.spin(due, due - window - 1))
        assertEquals(30_000_000L - window, ServerSpin.parkNanos(due, 30_000_000L))
        assertEquals(0L, ServerSpin.parkNanos(due, window - 1))
        // Inside the window: one short slice per call, then back to the task loop.
        val start = System.nanoTime()
        assertTrue(ServerSpin.spin(due, due - 10_000L))
        assertTrue(System.nanoTime() - start < 1_000_000_000L)
        assertTrue(ServerSpin.spin(due, due), "a due tick needs no spin and no park")
    }

    @Test
    fun aWaitNoTickArmedNeverSpins() {
        // A paused server keeps waiting between iterations that fire no tick events: each wait is for a later tick.
        ServerSpin.arm(due, window)
        val paused = due + 50_000_000L
        assertFalse(ServerSpin.spin(paused, paused - 1))
        assertEquals(30_000_000L, ServerSpin.parkNanos(paused, 30_000_000L))
        // Disarmed (no parallel work, lean mode, server stopped): plain vanilla waiting.
        ServerSpin.arm(due, 0L)
        assertFalse(ServerSpin.spin(due, due - 1))
        assertEquals(30_000_000L, ServerSpin.parkNanos(due, 30_000_000L))
        ServerSpin.arm(due, window)
        ServerSpin.disarm()
        assertFalse(ServerSpin.spin(due, due - 1))
        assertEquals(0L, ServerSpin.armedWindowNanos)
    }

    @Test
    fun aWholeGapWindowNeverParks() {
        ServerSpin.arm(due, Long.MAX_VALUE)
        assertTrue(ServerSpin.spin(due, due - 40_000))
        assertEquals(0L, ServerSpin.parkNanos(due, 45_000_000L))
    }
}
