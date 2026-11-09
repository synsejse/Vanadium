package com.synsenetwork.vanadium.tick

import com.synsenetwork.vanadium.chunk.ParallelChunkCache
import net.minecraft.server.level.ServerChunkCache
import net.minecraft.world.level.chunk.ChunkAccess
import net.minecraft.world.level.chunk.status.ChunkStatus
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * One world's natural-spawning tasks. Spawn checks look up structure starts, which can be chunks that only the
 * server thread can still load or generate, and the server thread is busy in the same wave. Such a chunk's
 * spawning is deferred instead of blocking, then reruns on the server thread after the wave, where vanilla's
 * blocking lookup is safe. Deferred chunks keep their thunder-then-spawn order; deferral is rare outside
 * joins and exploration, when nearby structure chunks are still generating.
 */
class SpawnTasks(chunks: ServerChunkCache) {
    private val chunks = chunks as? ParallelChunkCache
    private val deferred = ConcurrentLinkedQueue<Task>()

    /** Chunks rerun on the server thread by the last [runDeferred]. */
    var lastDeferred = 0
        private set

    /** Server thread, before collecting: drops anything left by a failed wave. */
    fun clear() {
        deferred.clear()
    }

    /** Deferred chunks waiting for [runDeferred]. */
    internal val pending: Int get() = deferred.size

    /** Server thread: queues [spawn] at [chunk] for this tick's spawning wave. */
    fun enqueue(scheduler: TickScheduler, chunk: ChunkAccess, spawn: Runnable) {
        val pos = chunk.pos
        scheduler.enqueue(Stage.SPAWNING, pos.x(), pos.z(), task(chunk, spawn), null)
    }

    /** The wave task for [spawn]: on a worker it defers instead of waiting for the server thread. */
    internal fun task(chunk: ChunkAccess, spawn: Runnable): Runnable = Task(chunk, spawn)

    /** Server thread, after the spawning wave: reruns deferred chunks in the order they were deferred. */
    fun runDeferred() {
        var count = 0
        while (true) {
            val task = deferred.poll() ?: break
            count++
            task.spawn.run()
        }
        lastDeferred = count
    }

    private inner class Task(private val chunk: ChunkAccess, val spawn: Runnable) : Runnable {
        override fun run() {
            // Checking first keeps the common deferral free of partial spawns; the signal catches other lookups.
            if (!structureStartsAvailable(chunk) || !WorkerPool.runDeferrable(spawn)) deferred.add(this)
        }
    }

    /** Whether every structure start referenced by [chunk] can be read without the server thread. */
    private fun structureStartsAvailable(chunk: ChunkAccess): Boolean {
        val cache = chunks ?: return true
        if (!WorkerPool.isWorkerThread()) return true
        val references = chunk.allReferences
        if (references.isEmpty()) return true
        for (starts in references.values) {
            val iterator = starts.iterator()
            while (iterator.hasNext()) {
                if (!cache.isAvailable(iterator.nextLong(), ChunkStatus.STRUCTURE_STARTS)) return false
            }
        }
        return true
    }
}
