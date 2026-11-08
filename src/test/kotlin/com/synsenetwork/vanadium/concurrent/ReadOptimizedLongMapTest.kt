package com.synsenetwork.vanadium.concurrent

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ReadOptimizedLongMapTest {
    private class Value(val key: Long)

    @Test
    fun putGetRemoveAndReuseTombstones() {
        val map = ReadOptimizedLongMap<Value>()
        for (key in -500L until 500L) map.put(key, Value(key))
        assertEquals(1000, map.size())
        for (key in -500L until 500L) assertEquals(key, map[key]!!.key)
        assertNull(map[10_000])

        val replaced = Value(7)
        map.put(7, replaced)
        assertSame(replaced, map[7])
        assertFalse(map.remove(7, Value(7)), "removal must match the current value by identity")
        assertTrue(map.remove(7, replaced))
        assertNull(map[7])

        for (key in -500L until 500L) map[key]?.let { map.remove(key, it) }
        assertEquals(0, map.size())
        repeat(50) { round -> // Tombstone churn must not exhaust the table.
            for (key in 0L until 200L) map.put(key * 31 + round, Value(key * 31 + round))
            for (key in 0L until 200L) assertTrue(map.remove(key * 31 + round, map[key * 31 + round]!!))
        }
        assertEquals(0, map.size())
        map.put(Long.MIN_VALUE, Value(Long.MIN_VALUE))
        assertEquals(Long.MIN_VALUE, map[Long.MIN_VALUE]!!.key)
    }

    @Test
    fun concurrentReadersSeeOnlyMatchingValuesOrMisses() {
        val map = ReadOptimizedLongMap<Value>()
        val stop = AtomicBoolean()
        val failure = AtomicReference<Throwable?>()
        val done = CountDownLatch(4)
        repeat(4) {
            Thread.ofPlatform().daemon().start {
                try {
                    var probes = 0L
                    while (!stop.get() || probes < 100_000) {
                        val key = probes++ % 4096
                        val value = map[key]
                        if (value != null && value.key != key) throw AssertionError("torn read for $key")
                    }
                } catch (t: Throwable) {
                    failure.compareAndSet(null, t)
                } finally {
                    done.countDown()
                }
            }
        }
        repeat(200) { round ->
            for (key in (round % 2).toLong() until 4096L step 2) map.put(key, Value(key))
            for (key in (round % 2).toLong() until 4096L step 4) map[key]?.let { map.remove(key, it) }
        }
        stop.set(true)
        assertTrue(done.await(30, TimeUnit.SECONDS), "readers did not finish")
        assertNull(failure.get(), "reader failed: ${failure.get()}")
    }
}
