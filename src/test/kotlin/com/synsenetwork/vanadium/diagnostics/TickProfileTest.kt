package com.synsenetwork.vanadium.diagnostics

import com.synsenetwork.vanadium.tick.PreparationKind
import com.synsenetwork.vanadium.tick.Stage
import com.synsenetwork.vanadium.tick.TickScheduler
import com.synsenetwork.vanadium.tick.WorkerPool
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.function.LongConsumer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class TickProfileTest {
    @Test
    fun timeSharesHandleEmptyCapturesAndOverlappingTimings() {
        val empty = TickProfile().toJson().getAsJsonObject("split")
        for (name in listOf("stageWaves", "preparation", "serial")) {
            assertEquals(0.0, empty.get(name).asDouble, name)
        }

        val profile = TickProfile()
        profile.recordTick(100)
        profile.recordStage(Stage.ENTITY, 80)
        profile.recordPreparation(PreparationKind.SPAWN_COUNTS, 1, 30)
        val split = profile.toJson().getAsJsonObject("split")
        assertEquals(0.8, split.get("stageWaves").asDouble)
        assertEquals(0.3, split.get("preparation").asDouble)
        assertEquals(0.0, split.get("serial").asDouble, "overlapping measurements cannot make the remainder negative")
    }

    @Test
    fun percentilesUseNearestRank() {
        val values = LongArray(100) { it + 1L }
        assertEquals(50, TickProfile.Samples.percentile(values, 50))
        assertEquals(95, TickProfile.Samples.percentile(values, 95))
        assertEquals(99, TickProfile.Samples.percentile(values, 99))
        assertEquals(7, TickProfile.Samples.percentile(longArrayOf(7), 99))
    }

    @Test
    fun captureCountsRegionsTasksAndWaitWithoutChangingDispatch() {
        val pool = WorkerPool(2)
        try {
            val scheduler = TickScheduler(pool, 2)
            val profile = TickProfile()
            val calls = AtomicInteger()
            scheduler.profile = profile
            scheduler.setDimension("test:world")
            scheduler.begin(Stage.BLOCK_ENTITY)
            scheduler.enqueue(Stage.BLOCK_ENTITY, 0, 0, { calls.incrementAndGet() }, null)
            scheduler.enqueue(Stage.BLOCK_ENTITY, 0, 0, { calls.incrementAndGet() }, null)
            scheduler.enqueue(Stage.BLOCK_ENTITY, 4, 0, { calls.incrementAndGet() }, null)
            scheduler.run(Stage.BLOCK_ENTITY)
            assertEquals(3, calls.get())
            scheduler.prepare(PreparationKind.SPAWN_COUNTS, List(3) { Runnable { calls.incrementAndGet() } })
            assertEquals(6, calls.get())
            // Entities: one flat wave, no regions.
            scheduler.begin(Stage.ENTITY)
            scheduler.runFlat(Stage.ENTITY, List(5) { Runnable { calls.addAndGet(100) } })
            assertEquals(506, calls.get())
            calls.addAndGet(-500)
            scheduler.finishWorld()
            profile.recordTick(1_000_000_000) // Longer than any fixture wave, so the serial share is positive.
            val json = profile.toJson()
            assertEquals(1, json.getAsJsonObject("ticks").get("count").asInt)
            val entity = json.getAsJsonObject("stages").getAsJsonObject("BLOCK_ENTITY")
            assertEquals(3, entity.get("tasks").asInt)
            assertEquals(2, entity.get("regions").asInt)
            assertEquals(1, entity.get("waves").asInt)
            assertEquals(2, entity.get("maxTasksPerRegion").asInt)
            assertEquals(2, entity.getAsJsonObject("regionExecution").get("count").asInt)
            val flat = json.getAsJsonObject("stages").getAsJsonObject("ENTITY")
            assertEquals(5, flat.get("tasks").asInt)
            assertEquals(0, flat.get("regions").asInt)
            assertEquals(1, flat.get("waves").asInt)
            for (phase in listOf("BLOCK_ENTITY preparation", "BLOCK_ENTITY execution", "ENTITY preparation",
                "ENTITY execution", "world total")) {
                assertEquals(1, json.getAsJsonObject("phases").getAsJsonObject("test:world / $phase").get("count").asInt)
            }
            assertTrue(entity.get("utilization").asDouble in 0.0..1.0)
            assertEquals(3, json.getAsJsonObject("preparations").getAsJsonObject("SPAWN_COUNTS").get("jobs").asInt)
            val split = json.getAsJsonObject("split")
            val shares = listOf("stageWaves", "preparation", "serial").sumOf { split.get(it).asDouble }
            assertEquals(1.0, shares, 1e-9)
            scheduler.profile = null
            scheduler.begin(Stage.BLOCK_ENTITY)
            scheduler.enqueue(Stage.BLOCK_ENTITY, 0, 0, { calls.incrementAndGet() }, null)
            scheduler.run(Stage.BLOCK_ENTITY)
            scheduler.prepare(PreparationKind.SPAWN_COUNTS, List(2) { Runnable { calls.incrementAndGet() } })
            assertEquals(9, calls.get())
            assertEquals(json, profile.toJson())
        } finally {
            pool.close()
        }
    }

    @Test
    fun sampleStorageIsBoundedAndTruncationIsReported() {
        val profile = TickProfile()
        repeat(TickProfile.MAX_SAMPLES + 1) { profile.recordTick(1_000_000) }
        assertTrue(profile.isFull())
        val ticks = profile.toJson().getAsJsonObject("ticks")
        assertEquals(TickProfile.MAX_SAMPLES + 1, ticks.get("count").asInt)
        assertEquals(TickProfile.MAX_SAMPLES, ticks.get("percentileSamples").asInt)
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
