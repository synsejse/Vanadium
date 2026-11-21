package com.synsenetwork.vanadium.concurrent

import com.synsenetwork.vanadium.tick.TickThread
import com.synsenetwork.vanadium.tick.WorkerPool
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame

class PerThreadRandomTest {
    @Test
    fun randomSourceCachesPerInstanceOnTickThreadsAndOwnerThread() {
        val first = PerThreadRandomSource(1234L)
        val second = PerThreadRandomSource(5678L)
        assertSame(first.current(), first.current())
        assertNotSame(first.current(), second.current())
        first.setSeed(42L)
        val ownerFirst = first.nextLong()
        first.setSeed(42L)
        assertEquals(ownerFirst, first.nextLong())

        val error = AtomicReference<Throwable>()
        val worker = object : TickThread("PerThreadRandomTest-Worker") {
            override fun run() {
                try {
                    val s1 = first.current()
                    assertSame(s1, first.current())
                    assertSame(first, randomOwner)
                    assertSame(s1, random)
                    s1.setSeed(99L)
                    val expected1 = first.nextLong()

                    val s2 = second.current()
                    assertNotSame(s1, s2)
                    assertSame(second, randomOwner)
                    s2.setSeed(77L)
                    val expected2 = second.nextLong()

                    // Switching back restores the first instance's stream instead of sharing one.
                    assertSame(s1, first.current())
                    first.setSeed(99L)
                    assertEquals(expected1, first.nextLong())
                    second.setSeed(77L)
                    assertEquals(expected2, second.nextLong())
                } catch (t: Throwable) {
                    error.set(t)
                }
            }
        }
        worker.start()
        worker.join(5_000)
        error.get()?.let { throw it }
    }

    @Test
    fun randValueIsolatesTickThreadsServerThreadAndOtherThreads() {
        PerThreadRandValue.setServerThread(Thread.currentThread())
        try {
            PerThreadRandValue.set(null, 111)
            assertEquals(111, PerThreadRandValue.get(null))

            val seen = ConcurrentHashMap<Thread, Int>()
            WorkerPool(4).use { pool ->
                pool.runWave(List(4) { i ->
                    Runnable {
                        val value = 1000 + i
                        PerThreadRandValue.set(null, value)
                        seen[Thread.currentThread()] = PerThreadRandValue.get(null)
                    }
                })
            }
            val expectedServer = seen[Thread.currentThread()] ?: 111
            assertEquals(expectedServer, PerThreadRandValue.get(null))
            for ((thread, value) in seen) {
                if (thread is TickThread) assertEquals(value, thread.randValue)
            }
        } finally {
            PerThreadRandValue.setServerThread(null)
        }
    }
}
