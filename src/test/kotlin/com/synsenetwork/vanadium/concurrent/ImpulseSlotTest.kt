package com.synsenetwork.vanadium.concurrent

import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ImpulseSlotTest {
    @Test
    fun tracksEmptyTransitionsAndClearsOnDrain() {
        val slot = ImpulseSlot()
        assertTrue(slot.isEmpty())
        assertFalse(slot.drain { _, _, _ -> error("empty slot drained") })

        assertTrue(slot.add(1.5, -2.0, 0.25))
        assertFalse(slot.isEmpty())
        assertFalse(slot.add(0.5, 3.0, -0.25))
        assertFalse(slot.isEmpty())

        var rx = 0.0
        var ry = 0.0
        var rz = 0.0
        assertTrue(slot.drain { dx, dy, dz ->
            // Reentrant add during callback must see an empty slot and return true.
            assertTrue(slot.isEmpty())
            assertTrue(slot.add(4.0, 5.0, 6.0))
            rx = dx; ry = dy; rz = dz
        })
        assertEquals(2.0, rx)
        assertEquals(1.0, ry)
        assertEquals(0.0, rz)

        assertFalse(slot.isEmpty())
        assertTrue(slot.drain { dx, dy, dz -> rx = dx; ry = dy; rz = dz })
        assertEquals(4.0, rx)
        assertEquals(5.0, ry)
        assertEquals(6.0, rz)
        assertTrue(slot.isEmpty())
    }

    @Test
    fun concurrentAddsAndDrainsLoseNoImpulsesAndCountEmptyTransitions() {
        val slot = ImpulseSlot()
        val writers = 6
        val pushesPerWriter = 10_000
        val barrier = CyclicBarrier(writers + 1)
        val firstFillers = AtomicInteger()
        val drains = AtomicInteger()
        var totalX = 0.0
        var totalY = 0.0
        var totalZ = 0.0
        val pool = Executors.newFixedThreadPool(writers + 1)
        try {
            val writerFutures = List(writers) { id ->
                pool.submit {
                    barrier.await(10, TimeUnit.SECONDS)
                    val dx = (id + 1).toDouble()
                    repeat(pushesPerWriter) {
                        if (slot.add(dx, 1.0, -0.5)) firstFillers.incrementAndGet()
                    }
                }
            }
            val drainer = pool.submit {
                barrier.await(10, TimeUnit.SECONDS)
                var spins = 0
                while (writerFutures.any { !it.isDone } || !slot.isEmpty()) {
                    if (slot.drain { dx, dy, dz ->
                            totalX += dx
                            totalY += dy
                            totalZ += dz
                            drains.incrementAndGet()
                        }) {
                        spins = 0
                    } else if (++spins and 15 == 0) {
                        Thread.yield()
                    }
                }
            }
            writerFutures.forEach { it.get(30, TimeUnit.SECONDS) }
            drainer.get(30, TimeUnit.SECONDS)
        } finally {
            pool.shutdownNow()
        }
        val expectedX = (1..writers).sum().toDouble() * pushesPerWriter
        val expectedY = writers.toDouble() * pushesPerWriter
        val expectedZ = -0.5 * writers * pushesPerWriter
        assertEquals(expectedX, totalX)
        assertEquals(expectedY, totalY)
        assertEquals(expectedZ, totalZ)
        assertTrue(slot.isEmpty())
        // Every empty-to-pending transition is paired 1:1 with a pending-to-empty drain.
        assertEquals(firstFillers.get(), drains.get())
    }
}
