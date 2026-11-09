package com.synsenetwork.vanadium.collections

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.function.LongUnaryOperator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Collections installed in place of other mods' concretely typed maps (see LithiumCompat). */
class CompatMapsTest {
    @Test
    fun openHashMapStandInNeverUsesSuperclassStorage() {
        val map: Long2ObjectOpenHashMap<String> = ConcurrentLongObjectOpenHashMap()
        assertNull(map.put(-5L, "a"))
        assertEquals("a", map.put(-5L, "b"))
        assertEquals("b", map.get(-5L))
        assertEquals("b", map.getOrDefault(-5L, "x"))
        assertEquals("x", map.getOrDefault(7L, "x"))
        assertTrue(map.containsKey(-5L) && map.containsValue("b"))
        assertEquals(setOf(-5L), map.keys.toSet())
        assertEquals(listOf("b"), map.values.toList())
        assertEquals(1, map.long2ObjectEntrySet().size)
        assertEquals(-5L, map.long2ObjectEntrySet().fastIterator().next().longKey)
        val seen = ArrayList<Long>()
        map.forEach { k, _ -> seen.add(k) }
        assertEquals(listOf(-5L), seen)
        assertEquals("c", map.computeIfAbsent(9L) { "c" })
        assertEquals(map, map.clone())
        map.defaultReturnValue("none")
        assertEquals("none", map.get(123L))
        assertEquals("b", map.remove(-5L))
        map.clear()
        assertTrue(map.isEmpty() && map.size == 0)
    }

    @Test
    fun openHashMapStandInKeepsConcurrentWrites() {
        val map = ConcurrentLongObjectOpenHashMap<Int>()
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(4)
        try {
            val jobs = List(4) { t ->
                pool.submit {
                    check(start.await(5, TimeUnit.SECONDS))
                    for (i in 0 until 2_000) map.put(t * 10_000L + i, i)
                }
            }
            start.countDown()
            jobs.forEach { it.get(10, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
        }
        assertEquals(8_000, map.size)
    }

    @Test
    fun listeningMapReportsLikeTheMapItReplaces() {
        val events = ArrayList<String>()
        val map = ListeningConcurrentLongObjectMap<String>({ k, v, added -> events.add("${if (added) "+" else "-"}$k=$v") },
            LongUnaryOperator { it })
        val a = "a"
        map.put(1L, a)
        map.put(1L, a) // Same instance: no change reported.
        map.put(1L, "b") // Replacement reports a removal and an addition, both with the new value.
        assertEquals("b", map.remove(1L))
        assertNull(map.remove(1L))
        assertEquals(listOf("+1=a", "-1=b", "+1=b", "-1=b"), events)
        events.clear()
        val source = Long2ObjectOpenHashMap<String>().apply { put(4L, "d") }
        map.putAllSilently(source)
        assertEquals("d", map.get(4L))
        assertTrue(events.isEmpty(), "silent copy reported entries")
        assertFailsWith<UnsupportedOperationException> { map.long2ObjectEntrySet().clear() }
        map.clear()
        assertEquals(listOf("-4=d"), events)
    }

    @Test
    fun listeningMapSerializesCallbacksWithinAGroupOnly() {
        val inCallback = CountDownLatch(1)
        val release = CountDownLatch(1)
        val overlapped = AtomicBoolean()
        val active = Collections.synchronizedSet(HashSet<Long>())
        // Keys group by key / 100: 1 and 2 share a group, 501 does not.
        val map = ListeningConcurrentLongObjectMap<String>({ key, _, _ ->
            val group = key / 100
            if (!active.add(group)) overlapped.set(true)
            if (key == 1L) {
                inCallback.countDown()
                check(release.await(5, TimeUnit.SECONDS))
            }
            active.remove(group)
        }, LongUnaryOperator { it / 100 })
        val pool = Executors.newFixedThreadPool(3)
        try {
            val first = pool.submit { map.put(1L, "x") }
            assertTrue(inCallback.await(5, TimeUnit.SECONDS))
            val sameGroup = pool.submit { map.put(2L, "y") }
            // A different stripe is not blocked by the held group (the stripes for 0 and 5 differ).
            pool.submit { map.put(501L, "z") }.get(5, TimeUnit.SECONDS)
            assertFalse(sameGroup.isDone, "same-group write ran while the first callback was active")
            release.countDown()
            first.get(5, TimeUnit.SECONDS)
            sameGroup.get(5, TimeUnit.SECONDS)
        } finally {
            release.countDown()
            pool.shutdownNow()
        }
        assertFalse(overlapped.get(), "two callbacks for one group overlapped")
        assertEquals(3, map.size)
    }

    @Test
    fun synchronizedReferenceMapBasics() {
        val map = SynchronizedLong2ReferenceOpenHashMap<String>()
        assertNull(map.put(3L, "a"))
        assertEquals("a", map.get(3L))
        assertEquals("a", map.putIfAbsent(3L, "b"))
        assertTrue(map.remove(3L, "a" as Any?))
        assertFalse(map.containsKey(3L))
    }
}
