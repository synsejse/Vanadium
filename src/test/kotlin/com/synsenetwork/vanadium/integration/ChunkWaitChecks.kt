package com.synsenetwork.vanadium.integration

import com.synsenetwork.vanadium.chunk.ParallelChunkCache
import com.synsenetwork.vanadium.stage.BlockEntityStage
import com.synsenetwork.vanadium.tick.ClaimWave
import com.synsenetwork.vanadium.tick.DataflowWave
import com.synsenetwork.vanadium.tick.RegionWave
import com.synsenetwork.vanadium.tick.WorkerPool
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.TicketType
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.entity.vault.VaultBlockEntity
import net.minecraft.world.level.chunk.ChunkAccess
import net.minecraft.world.level.chunk.status.ChunkStatus
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

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
        regionNeighborLoad(level, source, claiming = true)
        regionNeighborLoad(level, source, claiming = false)
        blockEntityNeighbourhood(level, source)
        println("CHUNK_WAIT_CHECKS_PASSED shared=1 tail=1")
    }

    /**
     * Block entities tick on workers only in chunks [BlockEntityStage.ready] accepts, and ready must mean that
     * every neighbouring chunk is FULL: the vault path above then never reaches a chunk a worker has to wait for.
     */
    private fun blockEntityNeighbourhood(level: ServerLevel, source: ParallelChunkCache) {
        val center = ChunkPos(4500, -3001)
        val ring = (-1..1).flatMap { dx -> (-1..1).map { dz -> ChunkPos(center.x() + dx, center.z() + dz) } }
        for (pos in ring) {
            check(source.getVisibleChunkIfPresent(pos.pack()) == null) { "fixture chunk $pos is already tracked" }
        }
        // A stage of its own: the level's stage keeps its cached answer until its next block-entity phase.
        val neighbourhood = BlockEntityStage(level, ArrayList())
        check(!neighbourhood.ready(center.pack())) { "an untracked chunk counts as ready" }
        // Block-ticking level for the centre; its neighbours get FULL level and load asynchronously.
        source.addTicketWithRadius(TicketType.FORCED, center, 1)
        try {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
            source.mainThreadProcessor.managedBlock { System.nanoTime() >= deadline || source.isPositionTicking(center.pack()) }
            check(neighbourhood.ready(center.pack()) == false) { "the cached answer was not kept for the phase" }
            check(BlockEntityStage(level, ArrayList()).ready(center.pack())) { "chunk $center never became ready for block entities" }
            for (pos in ring) {
                check(source.isAvailable(pos.pack(), ChunkStatus.FULL)) { "chunk $pos is not FULL although $center is ready" }
            }
        } finally {
            source.removeTicketWithRadius(TicketType.FORCED, center, 1)
        }
        println("BLOCK_ENTITY_NEIGHBOURHOOD_PASSED")
    }

    /** The reported vault path: setChanged reads past a solid neighbour into a chunk not yet FULL. */
    private fun regionNeighborLoad(level: ServerLevel, source: ParallelChunkCache, claiming: Boolean) {
        val origin = ChunkPos(if (claiming) 4200 else 4300, -3001)
        val chunk = level.getChunk(origin.x(), origin.z())
        val pos = BlockPos(origin.minBlockX + 14, 100, origin.minBlockZ + 8)
        chunk.setBlockState(pos.east(), Blocks.STONE.defaultBlockState(), 0)
        val neighbor = ChunkPos(origin.x() + 1, origin.z())
        check(!source.isAvailable(neighbor.pack(), ChunkStatus.FULL)) { "vault neighbour is already FULL" }
        // No registered ticker is needed: exercise the same dirty/notification operation as Vault.Server.tick.
        val vault = VaultBlockEntity(pos, Blocks.VAULT.defaultBlockState()).apply { setLevel(level) }
        val completed = AtomicInteger()
        val work = listOf(Runnable {
            check(WorkerPool.isWorkerThread()) { "vault check must start on a worker" }
            vault.setChanged()
            completed.incrementAndGet()
        }, Runnable {
            check(completed.get() == 1) { "conflicting region ran before the vault finished" }
            completed.incrementAndGet()
        })
        val starts = intArrayOf(0, 1, 2)
        val neighbors = intArrayOf(1, 0)
        val wave: RegionWave = if (claiming) ClaimWave(work, starts, neighbors)
            else DataflowWave(work, starts, neighbors, intArrayOf(0, 1))
        val participants = wave.participants(2)
        val task = Runnable {
            if (WorkerPool.isWorkerThread()) {
                participants[0].run()
            } else {
                awaitWorkerRequest()
                participants[1].run()
            }
        }
        WorkerPool(1).use { pool -> pool.runWave(listOf(task, task)) }
        check(wave.failure == null) { "vault neighbour load failed: ${wave.failure}" }
        check(completed.get() == 2) { "region work was lost or repeated" }
        check(source.getChunkNow(neighbor.x(), neighbor.z()) != null) { "vault neighbour was not published" }
        check(!WorkerPool.callerHasRequests()) { "region wave left a chunk request queued" }
        println("REGION_CHUNK_WAIT_PASSED policy=${if (claiming) "claim" else "dataflow"}")
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
