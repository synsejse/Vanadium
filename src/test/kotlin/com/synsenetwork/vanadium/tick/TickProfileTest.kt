package com.synsenetwork.vanadium.tick

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.function.LongConsumer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class TickProfileTest {
    @Test
    fun percentilesUseNearestRank() {
        val values = LongArray(100) { it + 1L }
        assertEquals(50, TickProfile.Samples.percentile(values, 50))
        assertEquals(95, TickProfile.Samples.percentile(values, 95))
        assertEquals(99, TickProfile.Samples.percentile(values, 99))
        assertEquals(7, TickProfile.Samples.percentile(longArrayOf(7), 99))
    }

    @Test
    fun captureCountsCellsTasksAndWaitWithoutChangingDispatch() {
        val pool = WorkerPool(2)
        try {
            val scheduler = TickScheduler(pool, 2)
            val profile = TickProfile()
            val calls = AtomicInteger()
            scheduler.profile = profile
            scheduler.setDimension("test:world")
            scheduler.begin(Stage.ENTITY)
            scheduler.enqueue(Stage.ENTITY, 0, 0, { calls.incrementAndGet() }, null)
            scheduler.enqueue(Stage.ENTITY, 0, 0, { calls.incrementAndGet() }, null)
            scheduler.enqueue(Stage.ENTITY, 4, 0, { calls.incrementAndGet() }, null)
            scheduler.run(Stage.ENTITY)
            assertEquals(3, calls.get())
            scheduler.finishWorld()
            val report = profile.report()
            for (expected in listOf("waves=1 cells=2 tasks=3; tasks/cell avg=1.5 max=2", "cell execution n=2",
                    "test:world / ENTITY preparation: n=1", "test:world / ENTITY execution: n=1", "test:world / world total: n=1")) {
                assertTrue(expected in report, report)
            }
            scheduler.profile = null
            scheduler.begin(Stage.ENTITY)
            scheduler.enqueue(Stage.ENTITY, 0, 0, { calls.incrementAndGet() }, null)
            scheduler.run(Stage.ENTITY)
            assertEquals(4, calls.get())
            assertEquals(report, profile.report())
        } finally {
            pool.close()
        }
    }

    @Test
    fun sampleStorageIsBoundedAndTruncationIsReported() {
        val profile = TickProfile()
        repeat(TickProfile.MAX_SAMPLES + 1) { profile.recordTick(1_000_000) }
        assertTrue(profile.isFull())
        assertTrue("percentiles limited to first 65536" in profile.report())
    }

    @Test
    fun waitCallbackRunsAfterCompletionEvenWhenATaskFails() {
        val pool = WorkerPool(2)
        val finished = AtomicInteger()
        val waited = AtomicLong(-1)
        try {
            assertFailsWith<RuntimeException> {
                pool.runWave(listOf(
                    Runnable {
                        finished.incrementAndGet()
                        throw IllegalStateException("fixture")
                    },
                    Runnable { finished.incrementAndGet() },
                ), LongConsumer { nanos ->
                    assertEquals(2, finished.get())
                    waited.set(nanos)
                })
            }
            assertTrue(waited.get() >= 0)
        } finally {
            pool.close()
        }
    }
}
