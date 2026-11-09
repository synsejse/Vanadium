package com.synsenetwork.vanadium.collections

import it.unimi.dsi.fastutil.longs.Long2ByteMaps
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConcurrentMapsTest {
    @Test
    fun int2ObjectPutGetRemoveAndDefaults() {
        val map = ConcurrentIntObjectMap<String>()
        assertNull(map.put(1, "a"), "first put: key absent → default (null)")
        assertEquals("a", map.put(1, "b"), "second put: key present → old value")
        assertEquals("b", map.get(1))
        assertTrue(map.containsKey(1))
        assertEquals("b", map.remove(1))
        assertEquals(0, map.size)
        map.defaultReturnValue("MISSING")
        assertEquals("MISSING", map.get(99))
        assertEquals("MISSING", map.put(5, "x"))
        assertEquals("MISSING", map.remove(99))
    }

    @Test
    fun long2ObjectPutGetRemoveAndDefaults() {
        val map = ConcurrentLongObjectMap<String>()
        assertNull(map.put(1L, "a"))
        assertEquals("a", map.put(1L, "b"))
        assertEquals("b", map.get(1L))
        assertTrue(map.containsKey(1L))
        assertEquals("b", map.remove(1L))
        assertEquals(0, map.size)
        map.defaultReturnValue("N/A")
        assertEquals("N/A", map.get(42L))
        assertEquals("N/A", map.put(7L, "v"))
        assertEquals("N/A", map.remove(999L))
    }

    @Test
    fun long2BytePutGetRemoveAndDefaults() {
        val map = ConcurrentLongByteMap()
        assertEquals(0, map.put(1L, 5))
        assertEquals(5, map.put(1L, 9))
        assertEquals(9, map.get(1L))
        map.defaultReturnValue(-1)
        assertEquals(-1, map.get(77L))
        map.put(77L, 3)
        assertEquals(3, map.remove(77L))
        assertEquals(-1, map.remove(77L))
    }

    @Test
    fun viewsExposeOriginalKeysAndWriteThrough() {
        val keys = setOf(-5L, 0L, 1L shl 40, Long.MIN_VALUE, Long.MAX_VALUE)
        val objects = ConcurrentLongObjectMap<String>()
        for (key in keys) objects.put(key, "v$key")
        assertEquals(keys, objects.keys.toSet())
        assertTrue(objects.keys.contains(1L shl 40))
        for (entry in objects.long2ObjectEntrySet()) {
            assertEquals("v${entry.longKey}", entry.value)
            entry.setValue("w${entry.longKey}")
        }
        for (key in keys) assertEquals("w$key", objects.get(key), "entry setValue must write through")
        val iterator = objects.long2ObjectEntrySet().iterator()
        iterator.next()
        iterator.remove()
        assertEquals(keys.size - 1, objects.size)

        val bytes = ConcurrentLongByteMap()
        for (key in keys) bytes.put(key, 2)
        val visited = HashSet<Long>()
        Long2ByteMaps.fastForEach(bytes) { visited.add(it.longKey) }
        assertEquals(keys, visited)
        val direct = HashSet<Long>()
        bytes.forEachEntry { key, value ->
            assertEquals(2, value)
            direct.add(key)
        }
        assertEquals(keys, direct)
    }

    @Test
    fun long2ByteMatchesFastutilUnderChurn() {
        val random = java.util.Random(7)
        val map = ConcurrentLongByteMap()
        val reference = it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap()
        map.defaultReturnValue(33)
        reference.defaultReturnValue(33)
        repeat(200_000) {
            val key = random.nextInt(4096).toLong() * 0x1_0000_0001L - 2048 // Negative and packed-looking keys.
            when (random.nextInt(3)) {
                0 -> assertEquals(reference.remove(key), map.remove(key))
                else -> {
                    val value = random.nextInt(-128, 128).toByte()
                    assertEquals(reference.put(key, value), map.put(key, value))
                }
            }
            if (it % 997 == 0) assertEquals(reference.get(key), map.get(key))
        }
        assertEquals(reference.size, map.size)
        for (entry in reference.long2ByteEntrySet()) {
            assertTrue(map.containsKey(entry.longKey))
            assertEquals(entry.byteValue, map.get(entry.longKey))
        }
        assertEquals(reference.keys, map.keys.toSet())
        map.keys.forEach(java.util.function.LongConsumer { assertTrue(reference.containsKey(it)) })
        map.clear()
        assertEquals(0, map.size)
        assertEquals(33, map.get(1L))
    }

    @Test
    fun long2ByteReadersSeeStableKeysWhileWritersChurn() {
        val map = ConcurrentLongByteMap()
        val stable = LongArray(256) { it * 31L - 4000 }
        for (key in stable) map.put(key, (key and 0x3F).toByte())
        val stop = java.util.concurrent.atomic.AtomicBoolean()
        val failure = java.util.concurrent.atomic.AtomicReference<String?>()
        val readers = List(4) {
            Thread {
                while (!stop.get()) for (key in stable) {
                    if (map.get(key) != (key and 0x3F).toByte()) failure.compareAndSet(null, "lost $key")
                }
            }.apply { start() }
        }
        // Churn other keys through growth, tombstones and rebuilds.
        for (round in 0 until 50) for (i in 0 until 2000) {
            val key = 1_000_000L + i
            if (round % 2 == 0) map.put(key, 1) else map.remove(key)
        }
        stop.set(true)
        readers.forEach { it.join(5000) }
        assertNull(failure.get())
        assertEquals(stable.size, map.size)
    }

    @Test
    fun long2ObjectComputeFamily() {
        val map = ConcurrentLongObjectMap<String>()
        assertEquals("v1", map.computeIfAbsent(1L) { k: Long -> "v$k" })
        assertEquals("v1", map.computeIfAbsent(1L) { _: Long -> "should-not-run" })
        assertEquals("v1!", map.computeIfPresent(1L) { _, v -> "$v!" })
        assertEquals("x", map.compute(2L) { _, _ -> "x" })
        assertNull(map.putIfAbsent(3L, "a"))
        assertEquals("a", map.get(3L))
        assertEquals("ab", map.merge(3L, "b") { old, new -> old + new })
    }

    /** Vanilla's section storage relies on this: under contention the factory runs at most once per key. */
    @Test
    fun computeIfAbsentIsAtomic() {
        val longs = ConcurrentLongObjectMap<String>()
        val ints = ConcurrentIntObjectMap<String>()
        val factoryCalls = AtomicInteger()
        val results = ConcurrentLinkedQueue<String>()
        race(16) {
            results.add(longs.computeIfAbsent(42L) { k: Long -> factoryCalls.incrementAndGet(); "v$k" })
            ints.computeIfAbsent(7) { k: Int -> factoryCalls.incrementAndGet(); "v$k" }
        }
        assertEquals(2, factoryCalls.get(), "a mapping function ran more than once → not atomic")
        assertEquals("v42", longs.get(42L))
        assertEquals("v7", ints.get(7))
        assertTrue(results.all { it == "v42" }, "all callers must see the same value")
    }

    private fun race(threads: Int, action: () -> Unit) {
        val pool = Executors.newFixedThreadPool(threads)
        val ready = CountDownLatch(threads)
        val go = CountDownLatch(1)
        repeat(threads) {
            pool.submit {
                ready.countDown()
                go.await()
                action()
            }
        }
        assertTrue(ready.await(5, TimeUnit.SECONDS))
        go.countDown() // release all threads at once to maximise the race
        pool.shutdown()
        assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS))
    }

    @Test
    fun linkedSetBasicsAndSnapshotClone() {
        val set = ConcurrentLongSet()
        assertTrue(set.add(100L))
        assertFalse(set.add(100L), "duplicate → false")
        assertTrue(set.contains(100L))
        assertEquals(1, set.size)
        assertTrue(set.remove(100L))
        assertFalse(set.contains(100L))
        for (value in longArrayOf(3, 1, 2)) set.add(value)
        assertEquals(listOf(1L, 2L, 3L), set.toLongArray().toList(), "ascending order")
        val copy = set.clone()
        set.add(4L)
        assertEquals(3, copy.size, "clone is a snapshot, not a live view")
        assertEquals(1L, set.removeFirstLong())
        assertEquals(4L, set.removeLastLong())
    }

    /**
     * Vanilla's SectionStorage.flushAll saves dirty POI chunks with forEach, then clears the set. Methods
     * the superclass implements over its own (unused) storage must see the concurrent contents.
     */
    @Test
    fun linkedSetForEachAndSpliteratorSeeBackingContents() {
        val set = ConcurrentLongSet()
        for (value in longArrayOf(5, -2, 9)) set.add(value)
        val visited = ArrayList<Long>()
        set.forEach(java.util.function.LongConsumer { visited.add(it) })
        assertEquals(listOf(-2L, 5L, 9L), visited)
        val streamed = ArrayList<Long>()
        set.spliterator().forEachRemaining(java.util.function.LongConsumer { streamed.add(it) })
        assertEquals(listOf(-2L, 5L, 9L), streamed)
        assertEquals(12L, set.longStream().sum())
    }
}
