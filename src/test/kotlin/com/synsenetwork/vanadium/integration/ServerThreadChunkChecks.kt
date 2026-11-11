package com.synsenetwork.vanadium.integration

import com.synsenetwork.vanadium.chunk.ParallelChunkCache
import com.synsenetwork.vanadium.tick.SpawnTasks
import com.synsenetwork.vanadium.tick.WorkerPool
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.Registries
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.chunk.LevelChunk
import net.minecraft.world.level.chunk.status.ChunkStatus
import net.minecraft.world.level.levelgen.structure.BuiltinStructures
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Workers must never wait for chunks only the server thread can provide: it is in the same wave. Reproduces
 * a join-time stall where a spawn check read an ungenerated structure start from a worker.
 */
internal object ServerThreadChunkChecks {
    fun run(level: ServerLevel) {
        val source = level.chunkSource as? ParallelChunkCache ?: error("parallel chunk cache was not installed")
        val loaded = level.getChunkAt(BlockPos.ZERO)
        val far = ChunkPos(40_000, 40_000)
        check(source.getChunkNow(far.x(), far.z()) == null && source.getVisibleChunkIfPresent(far.pack()) == null,
            "far fixture chunk is already tracked")
        val structure = level.registryAccess().lookupOrThrow(Registries.STRUCTURE).getValue(BuiltinStructures.MINESHAFT)
            ?: error("mineshaft structure missing")
        val fixture = LevelChunk(level, ChunkPos(-100_000, 7))
        fixture.addReferenceForStructure(structure, far.pack())
        WorkerPool(1).use { pool ->
            onWorker(pool) {
                check(source.isAvailable(loaded.pos.pack(), ChunkStatus.STRUCTURE_STARTS), "loaded chunk unavailable")
                check(!source.isAvailable(far.pack(), ChunkStatus.STRUCTURE_STARTS), "missing chunk reported available")
                check(WorkerPool.runDeferrable {
                    check(source.getChunk(loaded.pos.x(), loaded.pos.z(), ChunkStatus.FULL, true) === loaded, "FULL hit missed")
                    check(source.getChunk(loaded.pos.x(), loaded.pos.z(), ChunkStatus.STRUCTURE_STARTS, true) === loaded,
                        "completed lower status was not served on the worker")
                    check(source.getChunk(far.x(), far.z(), ChunkStatus.STRUCTURE_STARTS, false) == null, "absent lookup returned a chunk")
                }, "an available chunk deferred its task")
                check(!WorkerPool.runDeferrable {
                    source.getChunk(far.x(), far.z(), ChunkStatus.STRUCTURE_STARTS, true)
                    error("missing chunk load returned on a worker")
                }, "missing chunk load was not deferred")
                // The reported stack: NaturalSpawner -> StructureManager.fillStartsForStructure -> getChunk.
                check(!WorkerPool.runDeferrable {
                    level.structureManager().fillStartsForStructure(structure, fixture.getReferencesForStructure(structure)) {}
                }, "structure start lookup was not deferred")
            }

            // Spawning: whichever participant takes a task, every spawn runs once, on the server thread, after the wave.
            val spawns = SpawnTasks(source)
            val ran = ConcurrentLinkedQueue<Thread>()
            val server = Thread.currentThread()
            val spawn = Runnable { ran.add(Thread.currentThread()) }
            pool.runWave(listOf(spawns.task(fixture, spawn), spawns.task(fixture, spawn)))
            val direct = ran.size
            check(ran.all { it === server }, "a spawn ran on a worker with its structure start missing")
            // The server thread defers too: loading there mid-wave would run chunk-system work beside the helper.
            check(direct == 0, "the server thread spawned with a structure start missing while a helper ran")
            spawns.runDeferred()
            check(spawns.lastDeferred == 2 && ran.size == 2,
                "spawn deferral lost or repeated work: direct=$direct deferred=${spawns.lastDeferred} ran=${ran.size}")
            check(ran.all { it === server }, "a deferred spawn did not run on the server thread")
            check(source.getVisibleChunkIfPresent(far.pack()) == null, "worker lookups scheduled the missing chunk")
            println("SERVER_THREAD_CHUNK_CHECKS_PASSED deferred=${spawns.lastDeferred}")
        }
    }

    /** Runs [check] on a pool worker while the caller waits, as the server thread does inside a wave. */
    private fun onWorker(pool: WorkerPool, check: () -> Unit) {
        val done = CountDownLatch(1)
        val task = Runnable {
            if (WorkerPool.isWorkerThread()) {
                try {
                    check()
                } finally {
                    done.countDown()
                }
            } else {
                check(done.await(10, TimeUnit.SECONDS), "worker check timed out")
            }
        }
        pool.runWave(listOf(task, task))
    }
}
