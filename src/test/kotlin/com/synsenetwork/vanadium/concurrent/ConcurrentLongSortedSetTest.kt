package com.synsenetwork.vanadium.concurrent

import it.unimi.dsi.fastutil.longs.LongAVLTreeSet
import java.util.Random
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ConcurrentLongSortedSetTest {
    private fun populated() = ConcurrentLongSortedSet().apply {
        for (value in longArrayOf(Long.MIN_VALUE, -10, -1, 0, 1, 10, Long.MAX_VALUE)) add(value)
    }

    @Test
    fun rangeBoundsAndOrderingMatchSortedSetContract() {
        val set = populated()
        assertContentEquals(longArrayOf(-10, -1, 0, 1), set.subSet(-10, 10).toLongArray())
        assertContentEquals(longArrayOf(Long.MIN_VALUE, -10, -1), set.headSet(0).toLongArray())
        assertContentEquals(longArrayOf(10, Long.MAX_VALUE), set.tailSet(10).toLongArray())
        assertTrue(set.subSet(1, 1).isEmpty())
        assertFailsWith<IllegalArgumentException> { set.subSet(10, -10) }
        val expected = LongAVLTreeSet(longArrayOf(-10, -1, 0, 1))
        val range = set.subSet(-10, 10)
        assertEquals<Set<Long>>(expected, range)
        assertEquals<Set<Long>>(range, expected)
        assertEquals(expected.hashCode(), range.hashCode())
    }

    @Test
    fun rangesAreBoundedLiveViews() {
        val set = populated()
        val range = set.subSet(-10, 10)
        set.add(2)
        assertTrue(range.contains(2))
        range.remove(-1)
        assertFalse(set.contains(-1))
        range.add(3)
        assertTrue(set.contains(3))
        assertFailsWith<IllegalArgumentException> { range.add(10) }
        assertFailsWith<IllegalArgumentException> { range.add(-11) }
        range.tailSet(0).headSet(3).clear()
        assertContentEquals(longArrayOf(Long.MIN_VALUE, -10, 3, 10, Long.MAX_VALUE), set.toLongArray())
    }

    @Test
    fun iteratorRemovesFromBackingSetAndStopsAtUpperBound() {
        val set = populated()
        val iterator = set.subSet(-1, 1).iterator()
        assertEquals(-1, iterator.nextLong())
        iterator.remove()
        assertFalse(set.contains(-1))
        assertEquals(0, iterator.nextLong())
        assertFalse(iterator.hasNext())
    }

    @Test
    fun positionedIteratorStartsStrictlyAfterItsArgument() {
        val range = populated().subSet(-10, 10)
        assertEquals(0, range.iterator(-1).nextLong())
        assertEquals(-10, range.iterator(Long.MIN_VALUE).nextLong())
        assertFalse(range.iterator(Long.MAX_VALUE).hasNext())
        assertFalse(range.subSet(0, 0).iterator().hasNext())
    }

    @Test
    fun iteratorCanChangeDirectionAndRemoveFromEitherSide() {
        val set = populated()
        val iterator = set.subSet(-10, 10).iterator(-1)
        assertEquals(-1, iterator.previousLong())
        assertEquals(-1, iterator.nextLong())
        assertEquals(0, iterator.nextLong())
        assertEquals(0, iterator.previousLong())
        iterator.remove()
        assertFalse(set.contains(0))
        assertFailsWith<IllegalStateException> { iterator.remove() }
        assertEquals(1, iterator.nextLong())
        iterator.remove()
        assertFalse(set.contains(1))
        assertFalse(iterator.hasNext())
        assertEquals(-1, iterator.previousLong())
        assertEquals(-10, iterator.previousLong())
        assertFalse(iterator.hasPrevious())
        assertFailsWith<NoSuchElementException> { iterator.previousLong() }
        assertEquals(-10, iterator.nextLong())
    }

    @Test
    fun positionedIteratorClipsToRangeForReverseTraversal() {
        val range = populated().subSet(-10, 10)
        val iterator = range.iterator(Long.MAX_VALUE)
        assertFalse(iterator.hasNext())
        for (expected in longArrayOf(1, 0, -1, -10)) assertEquals(expected, iterator.previousLong())
        assertFalse(iterator.hasPrevious())
        assertFalse(range.iterator(Long.MIN_VALUE).hasPrevious())
        assertFalse(range.subSet(0, 0).iterator().hasPrevious())
    }

    @Test
    fun cursorOperationsMatchFastutilSortedSet() {
        val random = Random(12345)
        repeat(20) {
            val actual = ConcurrentLongSortedSet()
            val expected = LongAVLTreeSet()
            for (key in -20L..20L) {
                actual.add(key)
                expected.add(key)
            }
            val start = (random.nextInt(61) - 30).toLong()
            val a = actual.subSet(-10, 10).iterator(start)
            val e = expected.subSet(-10, 10).iterator(start)
            var canRemove = false
            repeat(300) {
                assertEquals(e.hasNext(), a.hasNext())
                assertEquals(e.hasPrevious(), a.hasPrevious())
                when (random.nextInt(3)) {
                    0 -> if (e.hasNext()) {
                        assertEquals(e.nextLong(), a.nextLong())
                        canRemove = true
                    }
                    1 -> if (e.hasPrevious()) {
                        assertEquals(e.previousLong(), a.previousLong())
                        canRemove = true
                    }
                    else -> if (canRemove) {
                        e.remove()
                        a.remove()
                        canRemove = false
                        assertContentEquals(expected.toLongArray(), actual.toLongArray())
                    }
                }
            }
        }
    }

    @Test
    fun forwardIterationToleratesConcurrentRangeMutation() {
        val set = ConcurrentLongSortedSet()
        for (i in -100L until 100L) set.add(i)
        val range = set.subSet(-50, 50)
        val iterator = range.iterator()
        val start = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val writer = executor.submit {
                assertTrue(start.await(5, TimeUnit.SECONDS))
                repeat(100) {
                    for (i in -100L until 100L) {
                        set.remove(i)
                        set.add(i)
                    }
                }
            }
            start.countDown()
            var previous = Long.MIN_VALUE
            while (iterator.hasNext()) {
                val value = iterator.nextLong()
                assertTrue(value in -50 until 50)
                assertTrue(value > previous, "iteration must remain ordered without duplicates")
                previous = value
            }
            writer.get(5, TimeUnit.SECONDS)
            assertEquals(100, range.size)
        } finally {
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
        }
    }
}
