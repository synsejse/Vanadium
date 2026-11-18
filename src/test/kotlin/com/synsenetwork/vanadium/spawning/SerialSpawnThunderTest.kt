package com.synsenetwork.vanadium.spawning

import com.synsenetwork.vanadium.tick.Stage
import com.synsenetwork.vanadium.tick.TickScheduler
import com.synsenetwork.vanadium.tick.WorkerPool
import kotlin.test.Test
import kotlin.test.assertEquals

class SerialSpawnThunderTest {
    private class Chunk(val name: String, val x: Int, val z: Int)

    private fun withThunder(test: (TickScheduler, SerialSpawnThunder<Chunk>, MutableList<String>) -> Unit) {
        WorkerPool(2).use { pool ->
            val scheduler = TickScheduler(pool, 1)
            val log = ArrayList<String>()
            val thunder = SerialSpawnThunder<Chunk> { synchronized(log) { log.add("thunder ${it.name}") } }
            scheduler.begin(Stage.SPAWNING)
            test(scheduler, thunder, log)
        }
    }

    /** Vanilla's order: each chunk's thunder, then its spawn attempt, with no wave in between. */
    @Test
    fun thunderRunsInlineBeforeItsChunksSpawnAttempt() = withThunder { scheduler, thunder, log ->
        val chunks = listOf(Chunk("a", 0, 0), Chunk("b", 8, 0), Chunk("c", -8, -8))
        for (chunk in chunks) {
            thunder.hold(scheduler, chunk, chunk.x, chunk.z)
            assertEquals(emptyList(), log.filter { it == "thunder ${chunk.name}" }, "held until its spawn attempt")
            thunder.beforeSpawn(scheduler, chunk)
            log.add("spawn ${chunk.name}")
        }
        thunder.flush(scheduler)
        scheduler.run(Stage.SPAWNING)
        assertEquals(chunks.flatMap { listOf("thunder ${it.name}", "spawn ${it.name}") }, log, "nothing left for the wave")
    }

    /** Chunks with no spawn attempt (no categories, or the chunk cannot spawn) keep thunder in the stage's wave. */
    @Test
    fun thunderWithoutASpawnAttemptJoinsTheWave() = withThunder { scheduler, thunder, log ->
        val a = Chunk("a", 0, 0)
        val b = Chunk("b", 8, 0)
        val c = Chunk("c", 16, 0)
        val d = Chunk("d", 24, 0)
        thunder.hold(scheduler, a, a.x, a.z)
        thunder.hold(scheduler, b, b.x, b.z) // a had no attempt
        thunder.beforeSpawn(scheduler, b)
        log.add("spawn b")
        thunder.hold(scheduler, c, c.x, c.z)
        thunder.beforeSpawn(scheduler, d) // d is outside entity-ticking range: no thunder of its own, c had no attempt
        log.add("spawn d")
        assertEquals(listOf("thunder b", "spawn b", "spawn d"), log)
        thunder.flush(scheduler)
        scheduler.run(Stage.SPAWNING)
        assertEquals(setOf("thunder a", "thunder c"), log.drop(3).toSet())
        assertEquals(5, log.size, "every thunder ran exactly once")
    }

    @Test
    fun thunderOnlyTicksStillRunEveryChunkOnce() = withThunder { scheduler, thunder, log ->
        val chunks = List(12) { Chunk("$it", it * 4, 0) }
        for (chunk in chunks) thunder.hold(scheduler, chunk, chunk.x, chunk.z)
        thunder.flush(scheduler)
        thunder.flush(scheduler) // nothing held: no-op
        scheduler.run(Stage.SPAWNING)
        assertEquals(chunks.map { "thunder ${it.name}" }.toSet(), log.toSet())
        assertEquals(chunks.size, log.size)
    }

    @Test
    fun clearDropsWhatAFailedTickLeft() = withThunder { scheduler, thunder, log ->
        thunder.hold(scheduler, Chunk("a", 0, 0), 0, 0)
        thunder.clear()
        thunder.flush(scheduler)
        scheduler.run(Stage.SPAWNING)
        assertEquals(emptyList(), log)
    }
}
