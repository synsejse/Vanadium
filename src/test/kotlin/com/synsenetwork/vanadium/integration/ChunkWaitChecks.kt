package com.synsenetwork.vanadium.integration

import com.synsenetwork.vanadium.chunk.ParallelChunkCache
import com.synsenetwork.vanadium.tick.WorkerPool
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.TicketType
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.chunk.ChunkAccess
import net.minecraft.world.level.chunk.status.ChunkStatus
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit

/**
 * A wave must not deadlock on a chunk load requested from inside it. Reproduces the reported interleaving: a worker's
 * lookup of a chunk that is scheduled but not FULL yet (as Lithium's block search does with `load = false`) is in
 * flight when the server thread, in the same wave, asks for the same chunk. Before the fix the worker held the chunk's
 * load lock while waiting for the server thread, which then waited for the lock; the watchdog ended the run.
 */
internal object ChunkWaitChecks {
    fun run(level: ServerLevel) {
        val source = level.chunkSource as? ParallelChunkCache ?: error("parallel chunk cache was not installed")
        val scheduled = ChunkPos(3001, -3001)
        val missing = ChunkPos(3100, -3001) // Beyond the scheduled ticket's reach.
        for (pos in listOf(scheduled, missing)) {
            check(source.getChunkNow(pos.x(), pos.z()) == null && source.getVisibleChunkIfPresent(pos.pack()) == null) {
                "fixture chunk $pos is already tracked"
            }
        }
        // A holder that will become FULL, which only the server thread can finish: it is not pumping tasks now.
        source.addTicketAndLoadWithRadius(TicketType.PLAYER_LOADING, scheduled, 0)
        try {
            check(source.getVisibleChunkIfPresent(scheduled.pack()) != null) { "the scheduled chunk has no holder" }
            check(!source.isAvailable(scheduled.pack(), ChunkStatus.FULL)) { "the scheduled chunk is already FULL" }
            WorkerPool(1).use { pool ->
                val sameChunk = wave(pool,
                    worker = { source.getChunk(scheduled.x(), scheduled.z(), ChunkStatus.FULL, false) },
                    caller = {
                        // Only once the worker's lookup is waiting for this thread, ask for the same chunk here.
                        awaitWorkerRequest()
                        source.getChunk(scheduled.x(), scheduled.z(), ChunkStatus.FULL, true)
                    })
                val loaded = source.getChunkNow(scheduled.x(), scheduled.z())
                check(loaded != null && sameChunk["worker"] === loaded && sameChunk["caller"] === loaded) {
                    "the scheduled chunk's lookups disagree: $sameChunk, published $loaded"
                }
                // Nothing on the server thread loads for the worker: it is served while the caller waits for the tail.
                val tail = wave(pool,
                    worker = { source.getChunk(missing.x(), missing.z(), ChunkStatus.FULL, true) },
                    caller = { null })
                val generated = source.getChunkNow(missing.x(), missing.z())
                check(generated != null && tail["worker"] === generated) { "the worker's load returned ${tail["worker"]}, published $generated" }
            }
        } finally {
            source.removeTicketWithRadius(TicketType.PLAYER_LOADING, scheduled, 0)
        }
        println("CHUNK_WAIT_CHECKS_PASSED shared=1 tail=1")
    }

    /** One wave of two tasks, one per participant, so [worker] runs on the helper and [caller] on this thread. */
    private fun wave(pool: WorkerPool, worker: () -> ChunkAccess?, caller: () -> ChunkAccess?): Map<String, ChunkAccess?> {
        val results = ConcurrentHashMap<String, Any>()
        val start = CyclicBarrier(2)
        val task = Runnable {
            start.await(10, TimeUnit.SECONDS)
            if (WorkerPool.isWorkerThread()) results["worker"] = worker() ?: "null" else results["caller"] = caller() ?: "null"
        }
        pool.runWave(listOf(task, task))
        check(results.size == 2) { "a wave task did not finish: $results" }
        return results.mapValues { it.value as? ChunkAccess }
    }

    private fun awaitWorkerRequest() {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (!WorkerPool.callerHasRequests()) {
            check(System.nanoTime() < deadline) { "the worker's lookup never reached the server thread" }
            Thread.onSpinWait()
        }
    }
}
