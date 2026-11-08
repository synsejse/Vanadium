package com.synsenetwork.vanadium.chunk

import com.mojang.datafixers.DataFixer
import com.synsenetwork.vanadium.Vanadium
import com.synsenetwork.vanadium.concurrent.ReadOptimizedLongMap
import net.minecraft.server.level.ServerChunkCache
import net.minecraft.server.level.ServerLevel
import net.minecraft.util.Util
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.chunk.ChunkAccess
import net.minecraft.world.level.chunk.ChunkGenerator
import net.minecraft.world.level.chunk.LevelChunk
import net.minecraft.world.level.chunk.status.ChunkStatus
import net.minecraft.world.level.entity.ChunkStatusUpdateListener
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager
import net.minecraft.world.level.storage.LevelStorageSource
import net.minecraft.world.level.storage.SavedDataStorage
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.ForkJoinWorkerThread
import java.util.concurrent.TimeUnit
import java.util.function.Supplier

/**
 * A [ServerChunkCache] whose full-chunk lookups are lock-free for every thread. Loaded full chunks are
 * published from `LevelChunk.setLoaded` into an exact map, so worker lookups never touch vanilla's
 * four-entry cache (its parallel arrays tear across threads) or C2ME's synchronized ticket-level map.
 * Misses and other statuses take vanilla's path, with concurrent loads of one position serialised
 * through a [ChunkLockTable]; a maintenance thread evicts idle load locks.
 */
class ParallelChunkManager(
    level: ServerLevel,
    storage: LevelStorageSource.LevelStorageAccess,
    dataFixer: DataFixer,
    structureManager: StructureTemplateManager,
    workerExecutor: Executor,
    generator: ChunkGenerator,
    viewDistance: Int,
    simulationDistance: Int,
    syncWrites: Boolean,
    statusListener: ChunkStatusUpdateListener,
    savedData: Supplier<SavedDataStorage>,
) : ServerChunkCache(level, storage, dataFixer, structureManager, workerExecutor, generator, viewDistance,
    simulationDistance, syncWrites, statusListener, savedData) {

    private val loadingLocks = ChunkLockTable()

    /** Chunks between `LevelChunk.setLoaded(true)` and `setLoaded(false)`; exact, never stale. */
    private val loadedChunks = ReadOptimizedLongMap<LevelChunk>()

    private val maintenance = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "Vanadium-ChunkMaintenance-${level.dimension().identifier().path}").apply { isDaemon = true }
    }.apply {
        scheduleWithFixedDelay(loadingLocks::evictIdle, LOCK_EVICT_SECONDS, LOCK_EVICT_SECONDS, TimeUnit.SECONDS)
    }

    private val lockFree: Boolean get() = Vanadium.config.enabled && Vanadium.config.chunkCache

    override fun getChunk(chunkX: Int, chunkZ: Int, requiredStatus: ChunkStatus, load: Boolean): ChunkAccess? {
        // A server "Main" worker thread must not load chunks off the main thread; bounce it back.
        if (isMinecraftMainWorker(Thread.currentThread())) {
            return CompletableFuture.supplyAsync({ getChunk(chunkX, chunkZ, requiredStatus, load) }, mainThreadProcessor).join()
        }
        if (!lockFree) return super.getChunk(chunkX, chunkZ, requiredStatus, load)

        val pos = ChunkPos.pack(chunkX, chunkZ)
        loadedFull(pos, requiredStatus)?.let { return it }
        if (Vanadium.config.parallelChunkLoads) {
            // Serialise loads of this one chunk so only a single thread does the work.
            val held = loadingLocks.lock(pos)
            try {
                return load(chunkX, chunkZ, pos, requiredStatus, load)
            } finally {
                loadingLocks.unlock(held)
            }
        }
        synchronized(this) {
            return load(chunkX, chunkZ, pos, requiredStatus, load)
        }
    }

    /** Under the load lock: the previous holder's full chunk was published by setLoaded before its load completed. */
    private fun load(chunkX: Int, chunkZ: Int, pos: Long, status: ChunkStatus, load: Boolean): ChunkAccess? =
        loadedFull(pos, status) ?: super.getChunk(chunkX, chunkZ, status, load)

    private fun loadedFull(pos: Long, status: ChunkStatus): LevelChunk? =
        if (status == ChunkStatus.FULL) loadedChunks[pos] else null

    /** A full chunk without the shared four-entry vanilla cache, which is unsafe across threads. */
    override fun getChunkNow(chunkX: Int, chunkZ: Int): LevelChunk? =
        if (lockFree) loadedChunks[ChunkPos.pack(chunkX, chunkZ)] else super.getChunkNow(chunkX, chunkZ)

    /**
     * Vanilla/C2ME resolve the holder's ticket level through a synchronized map, and every monster asks
     * this every tick for its own (loaded) chunk. Loaded full chunks answer lock-free; other positions
     * keep the ticket-level answer, which is already true while a ticketed chunk is still loading
     * (commands rely on that before they block on the load).
     */
    override fun hasChunk(chunkX: Int, chunkZ: Int): Boolean =
        lockFree && loadedChunks[ChunkPos.pack(chunkX, chunkZ)] != null || super.hasChunk(chunkX, chunkZ)

    /** Called from `LevelChunk.setLoaded` on whichever thread (server or C2ME) changes the state. */
    fun onChunkLoaded(chunk: LevelChunk, loaded: Boolean) {
        val pos = chunk.pos.pack()
        if (loaded) loadedChunks.put(pos, chunk) else loadedChunks.remove(pos, chunk)
    }

    override fun close() {
        try {
            super.close()
        } finally {
            maintenance.close()
            loadedChunks.clear()
            loadingLocks.evictIdle()
        }
    }

    private companion object {
        const val LOCK_EVICT_SECONDS = 30L

        /** Minecraft owns this shared executor; only its identity is compared. */
        fun isMinecraftMainWorker(thread: Thread): Boolean =
            thread is ForkJoinWorkerThread && thread.pool === Util.backgroundExecutor().service()
    }
}
