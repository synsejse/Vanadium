package com.synsenetwork.vanadium.chunk

import com.mojang.datafixers.DataFixer
import com.synsenetwork.vanadium.Vanadium
import com.synsenetwork.vanadium.tick.WorkerPool
import org.jctools.maps.NonBlockingHashMapLong
import net.minecraft.server.level.ChunkHolder
import net.minecraft.server.level.GenerationChunkHolder
import net.minecraft.server.level.ServerChunkCache
import net.minecraft.server.level.ServerLevel
import net.minecraft.util.Util
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.chunk.ChunkAccess
import net.minecraft.world.level.chunk.ChunkGenerator
import net.minecraft.world.level.chunk.ImposterProtoChunk
import net.minecraft.world.level.chunk.LevelChunk
import net.minecraft.world.level.chunk.status.ChunkStatus
import net.minecraft.world.level.entity.ChunkStatusUpdateListener
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager
import net.minecraft.world.level.storage.LevelStorageSource
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.ForkJoinWorkerThread
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** Unconditional loaded-FULL lookups and load locking around vanilla/C2ME synchronous misses. */
class ParallelChunkCache(
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
) : ServerChunkCache(level, storage, dataFixer, structureManager, workerExecutor, generator, viewDistance,
    simulationDistance, syncWrites, statusListener) {

    private val loadingLocks = ChunkLockTable()

    /** Full chunks published by `LevelChunk.setLoaded`; removal matches the published instance. */
    private val loadedChunks = NonBlockingHashMapLong<LevelChunk>()

    private val loadLockMaintenance = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "Vanadium-ChunkMaintenance-${level.dimension().identifier().path}").apply { isDaemon = true }
    }.apply {
        scheduleWithFixedDelay(loadingLocks::evictIdle, LOCK_EVICT_SECONDS, LOCK_EVICT_SECONDS, TimeUnit.SECONDS)
    }

    override fun getChunk(chunkX: Int, chunkZ: Int, requiredStatus: ChunkStatus, load: Boolean): ChunkAccess? {
        // A server "Main" worker thread must not load chunks off the main thread; bounce it back.
        if (isMinecraftMainWorker(Thread.currentThread())) {
            return CompletableFuture.supplyAsync({ getChunk(chunkX, chunkZ, requiredStatus, load) }, mainThreadProcessor).join()
        }
        val pos = ChunkPos.pack(chunkX, chunkZ)
        loadedFull(pos, requiredStatus)?.let { return it }
        val worker = WorkerPool.isWorkerThread()
        if (worker || WorkerPool.isParallelWaveCaller()) {
            // A worker: the server thread is in this wave, so only a chunk the chunk system has already completed
            // can be used. The server thread itself can load, but chunk-system work would overlap the workers, so a
            // deferrable task waits for the end of the wave instead.
            val holder = getVisibleChunkIfPresent(pos)
            if (holder != null) completed(holder, requiredStatus)?.let { return it } else if (!load) return null
            WorkerPool.deferToServerThread("chunk ($chunkX, $chunkZ) at $requiredStatus")?.let { throw it }
            if (worker) warnWorkerWait(chunkX, chunkZ, requiredStatus)
        }
        if (Vanadium.config.parallelChunkLoads) {
            val held = loadingLocks.lock(pos)
            try {
                return getChunkUnderLoadLock(chunkX, chunkZ, pos, requiredStatus, load)
            } finally {
                loadingLocks.unlock(held)
            }
        }
        synchronized(this) {
            return getChunkUnderLoadLock(chunkX, chunkZ, pos, requiredStatus, load)
        }
    }

    /** Under the load lock: the previous holder's full chunk was published by setLoaded before its load completed. */
    private fun getChunkUnderLoadLock(chunkX: Int, chunkZ: Int, pos: Long, status: ChunkStatus, load: Boolean): ChunkAccess? =
        loadedFull(pos, status) ?: super.getChunk(chunkX, chunkZ, status, load)

    private fun loadedFull(pos: Long, status: ChunkStatus): LevelChunk? =
        if (status == ChunkStatus.FULL) loadedChunks[pos] else null

    /**
     * Whether a worker can get [status] at packed [pos] without the server thread. Never blocks; like C2ME's
     * off-thread lookup it may schedule generation for a holder that is not there yet.
     */
    fun isAvailable(pos: Long, status: ChunkStatus): Boolean {
        if (loadedChunks.containsKey(pos)) return true
        val holder = getVisibleChunkIfPresent(pos) ?: return false
        return completed(holder, status) != null
    }

    /** C2ME's off-thread lookup: the chunk only if [holder] has already reached [status]. */
    private fun completed(holder: ChunkHolder, status: ChunkStatus): ChunkAccess? {
        val chunk = holder.scheduleChunkGenerationTask(status, chunkMap).getNow(GenerationChunkHolder.UNLOADED_CHUNK).orElse(null)
        return if (chunk is ImposterProtoChunk) chunk.wrapped else chunk
    }

    /** A worker task that cannot be deferred is about to wait for the server thread, which may never come. */
    private fun warnWorkerWait(chunkX: Int, chunkZ: Int, status: ChunkStatus) {
        val now = System.nanoTime()
        val last = lastWorkerWaitWarning.get()
        if (last != 0L && now - last < WORKER_WAIT_WARNING_NANOS || !lastWorkerWaitWarning.compareAndSet(last, now)) return
        Vanadium.LOGGER.warn("{} is waiting for chunk ({}, {}) at {}, which only the server thread can load; " +
            "if the tick stalls, make this caller serial or report it", Thread.currentThread().name, chunkX, chunkZ, status,
            Throwable("waiting caller"))
    }

    /** A full chunk without the shared four-entry vanilla cache, which is unsafe across threads. */
    /** Packed positions of the published full chunks, for diagnostics and live checks. */
    fun loadedChunkPositions(): LongArray = loadedChunks.keySetLong()

    override fun getChunkNow(chunkX: Int, chunkZ: Int): LevelChunk? =
        loadedChunks[ChunkPos.pack(chunkX, chunkZ)]

    /** Preserve ticket-level presence for chunks still loading, beyond the published FULL index. */
    override fun hasChunk(chunkX: Int, chunkZ: Int): Boolean =
        loadedChunks[ChunkPos.pack(chunkX, chunkZ)] != null || super.hasChunk(chunkX, chunkZ)

    /** Called from `LevelChunk.setLoaded` on whichever thread (server or C2ME) changes the state. */
    fun onChunkLoaded(chunk: LevelChunk, loaded: Boolean) {
        val pos = chunk.pos.pack()
        synchronized(loadedChunks) {
            if (loaded) loadedChunks.put(pos, chunk)
            else if (loadedChunks.get(pos) === chunk) loadedChunks.remove(pos)
        }
    }

    override fun close() {
        try {
            super.close()
        } finally {
            loadLockMaintenance.close()
            synchronized(loadedChunks) { loadedChunks.clear() }
            loadingLocks.evictIdle()
        }
    }

    private companion object {
        const val LOCK_EVICT_SECONDS = 30L
        const val WORKER_WAIT_WARNING_NANOS = 60_000_000_000L

        /** Shared by all worlds: one warning a minute is enough to name the caller. */
        val lastWorkerWaitWarning = AtomicLong()

        /** Minecraft owns this shared executor; only its identity is compared. */
        fun isMinecraftMainWorker(thread: Thread): Boolean =
            thread is ForkJoinWorkerThread && thread.pool === Util.backgroundExecutor().service()
    }
}
