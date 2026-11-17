package com.synsenetwork.vanadium.navigation

import it.unimi.dsi.fastutil.HashCommon
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import net.minecraft.core.BlockPos
import net.minecraft.world.level.pathfinder.PathType

class PathTypeSlotsTest {
    private val types = PathType.entries

    private fun index(key: Long) = HashCommon.mix(key).toInt() and (PathTypeSlots.SIZE - 1)

    @Test
    fun storesEveryTypeForPositionsOnBothSidesOfZero() {
        val slots = PathTypeSlots.create()
        val random = Random(3)
        val keys = listOf(BlockPos.ZERO, BlockPos(-1, -64, -1), BlockPos(30_000_000, 2047, -30_000_000), BlockPos(-30_000_000, -2048, 30_000_000))
            .map { it.asLong() } + List(200) { BlockPos(random.nextInt(-5000, 5000), random.nextInt(-64, 320), random.nextInt(-5000, 5000)).asLong() }
        for (key in keys) {
            for (type in types) {
                PathTypeSlots.put(slots, key, type)
                assertSame(type, PathTypeSlots.get(slots, key), "key $key type $type")
            }
        }
        assertNull(PathTypeSlots.get(PathTypeSlots.create(), BlockPos.ZERO.asLong()), "an empty slot is a miss, also for key 0")
    }

    @Test
    fun aSlotHoldsOneKeyAndInvalidationOnlyClearsItsOwn() {
        val slots = PathTypeSlots.create()
        val first = BlockPos(12, 70, -3).asLong()
        // Another position with the same slot index: the slot keeps only the latest key.
        val random = Random(9)
        var second: Long
        do second = BlockPos(random.nextInt(-100_000, 100_000), random.nextInt(-64, 320), random.nextInt(-100_000, 100_000)).asLong()
        while (second == first || index(second) != index(first))
        PathTypeSlots.put(slots, first, PathType.WATER)
        PathTypeSlots.put(slots, second, PathType.LAVA)
        assertNull(PathTypeSlots.get(slots, first))
        assertSame(PathType.LAVA, PathTypeSlots.get(slots, second))
        PathTypeSlots.invalidate(slots, first)
        assertSame(PathType.LAVA, PathTypeSlots.get(slots, second), "invalidating another key cleared the slot")
        PathTypeSlots.invalidate(slots, second)
        assertNull(PathTypeSlots.get(slots, second))
        PathTypeSlots.invalidate(slots, second) // Already empty.
        assertNull(PathTypeSlots.get(slots, second))
    }

    /** Racing writers on a few shared slots: a reader sees each key's own type or a miss, never another key's. */
    @Test
    fun concurrentReadersNeverSeeAnotherKeysType() {
        val slots = PathTypeSlots.create()
        // Eight keys in each of eight slots.
        val random = Random(5)
        val bySlot = HashMap<Int, MutableList<Long>>()
        val slotsUsed = ArrayList<Int>()
        while (bySlot.values.sumOf { it.size } < 64) {
            val key = BlockPos(random.nextInt(-100_000, 100_000), random.nextInt(-64, 320), random.nextInt(-100_000, 100_000)).asLong()
            val slot = index(key)
            if (slot !in bySlot && slotsUsed.size < 8) slotsUsed.add(slot)
            val keysInSlot = if (slot in slotsUsed) bySlot.getOrPut(slot) { ArrayList() } else continue
            if (keysInSlot.size < 8 && key !in keysInSlot) keysInSlot.add(key)
        }
        val keys = bySlot.values.flatten().toLongArray()
        fun typeOf(key: Long) = types[Math.floorMod(key, types.size.toLong()).toInt()]
        val threads = 6
        val pool = Executors.newFixedThreadPool(threads)
        val start = CountDownLatch(1)
        val failure = AtomicReference<String?>()
        try {
            val tasks = List(threads) { thread ->
                pool.submit {
                    check(start.await(10, TimeUnit.SECONDS)) { "start gate timed out" }
                    val random = Random(thread)
                    repeat(200_000) {
                        val key = keys[random.nextInt(keys.size)]
                        when (random.nextInt(3)) {
                            0 -> PathTypeSlots.put(slots, key, typeOf(key))
                            1 -> PathTypeSlots.invalidate(slots, key)
                            else -> {
                                val seen = PathTypeSlots.get(slots, key)
                                if (seen != null && seen != typeOf(key)) failure.compareAndSet(null, "key $key read $seen, wrote ${typeOf(key)}")
                            }
                        }
                    }
                }
            }
            start.countDown()
            for (task in tasks) task.get(60, TimeUnit.SECONDS)
        } finally {
            pool.shutdownNow()
        }
        assertNull(failure.get())
        for (key in keys) {
            PathTypeSlots.put(slots, key, typeOf(key))
            assertSame(typeOf(key), PathTypeSlots.get(slots, key))
        }
        assertEquals(PathTypeSlots.SIZE, slots.length())
    }
}
