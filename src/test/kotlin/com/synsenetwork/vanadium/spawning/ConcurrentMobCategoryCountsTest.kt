package com.synsenetwork.vanadium.spawning

import com.synsenetwork.vanadium.tick.TickThread
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import net.minecraft.world.entity.MobCategory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ConcurrentMobCategoryCountsTest {
    @Test
    fun supportsIncrementMergeAndEntryIteration() {
        val counts = ConcurrentMobCategoryCounts()
        assertEquals(0, counts.size)
        assertFalse(counts.containsKey(MobCategory.MONSTER))
        assertEquals(0, counts.getInt(MobCategory.MONSTER))

        assertEquals(1, counts.increment(MobCategory.MONSTER))
        assertEquals(2, counts.increment(MobCategory.MONSTER))
        assertEquals(1, counts.increment(MobCategory.CREATURE))
        assertEquals(2, counts.size)
        assertTrue(counts.containsKey(MobCategory.MONSTER))
        assertEquals(2, counts.getCount(MobCategory.MONSTER))
        assertEquals(1, counts.getInt(MobCategory.CREATURE))

        counts.mergeInt(MobCategory.MONSTER, 5, Int::plus)
        counts.mergeInt(MobCategory.AMBIENT, 3, Int::plus)
        assertEquals(7, counts.getInt(MobCategory.MONSTER))
        assertEquals(3, counts.getInt(MobCategory.AMBIENT))

        val entries = counts.object2IntEntrySet().associate { it.key to it.intValue }
        assertEquals(mapOf(MobCategory.MONSTER to 7, MobCategory.CREATURE to 1, MobCategory.AMBIENT to 3), entries)

        assertEquals(3, counts.removeInt(MobCategory.AMBIENT))
        assertFalse(counts.containsKey(MobCategory.AMBIENT))
        assertEquals(2, counts.size)
    }

    @Test
    fun handlesConcurrentIncrementsWithoutLostUpdates() {
        val counts = ConcurrentMobCategoryCounts()
        val threads = 8
        val perThread = 2_000
        val barrier = CyclicBarrier(threads)
        val pool = Executors.newFixedThreadPool(threads)
        repeat(threads) {
            pool.execute {
                barrier.await(10, TimeUnit.SECONDS)
                repeat(perThread) {
                    counts.increment(MobCategory.MONSTER)
                    counts.mergeInt(MobCategory.CREATURE, 2, Int::plus)
                }
            }
        }
        pool.shutdown()
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS))
        assertEquals(threads * perThread, counts.getCount(MobCategory.MONSTER))
        assertEquals(threads * perThread * 2, counts.getCount(MobCategory.CREATURE))
    }

    @Test
    fun cachesSpawnChargePerTickThread() {
        val error = AtomicReference<Throwable>()
        val worker = object : TickThread("SpawnChargeCacheTest-Worker") {
            override fun run() {
                try {
                    val first = ConcurrentMobCategoryCounts.chargeCache()
                    val second = ConcurrentMobCategoryCounts.chargeCache()
                    assertSame(first, second)
                    assertNotNull(spawnChargeCache)
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
