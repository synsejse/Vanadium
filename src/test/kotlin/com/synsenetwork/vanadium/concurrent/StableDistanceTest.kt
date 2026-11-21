package com.synsenetwork.vanadium.concurrent

import com.synsenetwork.vanadium.tick.TickThread
import java.util.LinkedList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.function.ToDoubleFunction
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class StableDistanceTest {
    private data class Item(val id: Int, val value: Double)

    @Test
    fun matchesTimSortStabilityAndSpecialDoublesAcrossSizes() {
        val random = Random(42)
        val special = doubleArrayOf(-0.0, 0.0, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, 1.5, 1.5, 0.0)
        val sizes = intArrayOf(0, 1, 2, 7, 24, 25, 64, 65, 257, 600)
        for (size in sizes) {
            val input = ArrayList<Item>(size)
            for (i in 0 until size) {
                val value = when {
                    i < special.size && size >= special.size -> special[i]
                    i % 5 == 0 -> (random.nextInt(4)).toDouble() // Force frequent ties to verify stability.
                    else -> random.nextDouble(-100.0, 100.0)
                }
                input.add(Item(i, value))
            }
            val expected = ArrayList(input)
            expected.sortWith(Comparator.comparingDouble(Item::value))

            val actual = ArrayList(input)
            StableDistance.sortBy(actual, ToDoubleFunction(Item::value))
            assertEquals(expected, actual, "ArrayList mismatch at size $size")

            val linked = LinkedList(input)
            StableDistance.sortBy(linked, ToDoubleFunction(Item::value))
            assertEquals<List<Item>>(expected, linked, "LinkedList mismatch at size $size")
        }
    }

    @Test
    fun readsEachKeyExactlyOnceAndHandlesNestedCallsOnTickThread() {
        val error = AtomicReference<Throwable>()
        val worker = object : TickThread("StableDistanceTest-Worker") {
            override fun run() {
                try {
                    val calls = AtomicInteger()
                    val outer = MutableList(40) { Item(it, (40 - it).toDouble()) }
                    StableDistance.sortBy(outer) { item ->
                        calls.incrementAndGet()
                        // Trigger a nested sort while the thread's primary scratch is busy.
                        if (item.id == 20) {
                            val inner = MutableList(30) { Item(it, (it % 7).toDouble()) }
                            val expectedInner = ArrayList(inner).apply { sortWith(Comparator.comparingDouble(Item::value)) }
                            StableDistance.sortBy(inner, ToDoubleFunction(Item::value))
                            assertEquals(expectedInner, inner)
                        }
                        item.value
                    }
                    assertEquals(40, calls.get(), "each element's key must be read exactly once")
                    assertEquals((39 downTo 0).map { Item(it, (40 - it).toDouble()) }, outer)
                    assertNotNull(sortScratch, "TickThread should cache its sort scratch")
                } catch (t: Throwable) {
                    error.set(t)
                }
            }
        }
        worker.start()
        worker.join(5_000)
        error.get()?.let { throw it }
    }
}
