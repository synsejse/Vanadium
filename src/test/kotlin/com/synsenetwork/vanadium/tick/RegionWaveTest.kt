package com.synsenetwork.vanadium.tick

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class RegionWaveTest {
    @Test
    fun independentRegionRunsWhileAConflictingRegionIsHeld() {
        for (claiming in listOf(false, true)) {
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val active = AtomicInteger()
            val completed = AtomicInteger()
            val conflicting = Runnable {
                assertEquals(0, active.getAndIncrement(), "conflicting regions overlapped")
                try {
                    entered.countDown()
                    assertTrue(release.await(5, TimeUnit.SECONDS), "independent work did not release the gate")
                    completed.incrementAndGet()
                } finally {
                    active.decrementAndGet()
                }
            }
            val independent = Runnable {
                try {
                    assertTrue(entered.await(5, TimeUnit.SECONDS), "conflicting work did not start")
                    completed.incrementAndGet()
                } finally {
                    release.countDown()
                }
            }
            WorkerPool(2).use { pool ->
                val work = listOf(conflicting, conflicting, independent)
                val start = intArrayOf(0, 1, 2, 2)
                val neighbors = intArrayOf(1, 0)
                if (claiming) {
                    val wave = ClaimWave(work, start, neighbors)
                    pool.runWave(wave.participants(3))
                    assertNull(wave.failure())
                } else {
                    val wave = DataflowWave(work, start, neighbors, intArrayOf(0, 1, 0))
                    pool.runWave(wave.participants(3))
                    assertNull(wave.failure())
                }
            }
            assertEquals(3, completed.get())
        }
    }

    @Test
    fun failureStillReleasesConflictsAndCompletesTheWave() {
        for (claiming in listOf(false, true)) {
            val boom = IllegalStateException("region failed")
            val completed = AtomicInteger()
            val work = listOf(Runnable { throw boom }, Runnable { completed.incrementAndGet() }, Runnable { completed.incrementAndGet() })
            val start = intArrayOf(0, 1, 3, 4)
            val neighbors = intArrayOf(1, 0, 2, 1)
            WorkerPool(2).use { pool ->
                if (claiming) {
                    val wave = ClaimWave(work, start, neighbors)
                    pool.runWave(wave.participants(3))
                    assertSame(boom, wave.failure())
                } else {
                    val wave = DataflowWave(work, start, neighbors, intArrayOf(0, 1, 0))
                    pool.runWave(wave.participants(3))
                    assertSame(boom, wave.failure())
                }
            }
            assertEquals(2, completed.get(), "failure left another region unfinished")
        }
    }
}
