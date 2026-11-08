package com.synsenetwork.vanadium.chunk;

import com.mojang.datafixers.DataFixer;
import com.synsenetwork.vanadium.Vanadium;
import com.synsenetwork.vanadium.concurrent.ReadOptimizedLongMap;
import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ForkJoinWorkerThread;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import javax.annotation.Nullable;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Util;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.entity.ChunkStatusUpdateListener;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import net.minecraft.world.level.storage.LevelStorageSource;
import net.minecraft.world.level.storage.SavedDataStorage;

/**
 * A {@link ServerChunkCache} whose full-chunk lookups are lock-free for every thread. Loaded full
 * chunks are published from {@code LevelChunk.setLoaded} into an exact map, so worker lookups never
 * touch vanilla's four-entry cache (its parallel arrays tear across threads) or C2ME's synchronized
 * ticket-level map. Misses and other statuses take vanilla's path, with concurrent loads of one
 * position serialised through a {@link ChunkLockTable}; a maintenance thread evicts idle load locks.
 */
public class ParallelChunkManager extends ServerChunkCache {

    private static final long LOCK_EVICT_SECONDS = 30;

    private final ChunkLockTable loadingLocks = new ChunkLockTable();
    /** Chunks between LevelChunk.setLoaded(true) and setLoaded(false); exact, never stale. */
    private final ReadOptimizedLongMap<LevelChunk> loadedChunks = new ReadOptimizedLongMap<>();
    private final ScheduledExecutorService maintenance;

    public ParallelChunkManager(ServerLevel world, LevelStorageSource.LevelStorageAccess session, DataFixer dataFixer,
                                StructureTemplateManager structureManager, Executor workerExecutor,
                                ChunkGenerator chunkGenerator, int viewDistance, int simulationDistance,
                                boolean dsync,
                                ChunkStatusUpdateListener chunkStatusChangeListener,
                                Supplier<SavedDataStorage> persistentStateManagerFactory) {
        super(world, session, dataFixer, structureManager, workerExecutor, chunkGenerator, viewDistance,
                simulationDistance, dsync, chunkStatusChangeListener,
                persistentStateManagerFactory);
        maintenance = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable,
                    "Vanadium-ChunkMaintenance-" + world.dimension().identifier().getPath());
            thread.setDaemon(true);
            return thread;
        });
        maintenance.scheduleWithFixedDelay(loadingLocks::evictIdle,
                LOCK_EVICT_SECONDS, LOCK_EVICT_SECONDS, TimeUnit.SECONDS);
    }

    @Override
    @Nullable
    public ChunkAccess getChunk(int chunkX, int chunkZ, ChunkStatus requiredStatus, boolean load) {
        // A server "Main" worker thread must not load chunks off the main thread; bounce it back.
        if (isMinecraftMainWorker(Thread.currentThread())) {
            return CompletableFuture.supplyAsync(
                    () -> getChunk(chunkX, chunkZ, requiredStatus, load), this.mainThreadProcessor).join();
        }
        if (!Vanadium.config.enabled || !Vanadium.config.chunkCache) {
            return super.getChunk(chunkX, chunkZ, requiredStatus, load);
        }

        long pos = ChunkPos.pack(chunkX, chunkZ);
        if (requiredStatus == ChunkStatus.FULL) {
            LevelChunk loaded = loadedChunks.get(pos);
            if (loaded != null) return loaded;
        }

        if (Vanadium.config.parallelChunkLoads) {
            // Serialise loads of this one chunk so only a single thread does the work.
            ChunkLockTable.Held held = loadingLocks.lock(pos, 0);
            try {
                return load(chunkX, chunkZ, pos, requiredStatus, load);
            } finally {
                loadingLocks.unlock(held);
            }
        } else {
            synchronized (this) {
                return load(chunkX, chunkZ, pos, requiredStatus, load);
            }
        }
    }

    /**
     * Called under the load lock. A full chunk loaded by the previous holder of the lock was
     * published by setLoaded before its load completed, so the recheck sees it.
     */
    @Nullable
    private ChunkAccess load(int chunkX, int chunkZ, long pos, ChunkStatus status, boolean load) {
        if (status == ChunkStatus.FULL) {
            LevelChunk loaded = loadedChunks.get(pos);
            if (loaded != null) return loaded;
        }
        return super.getChunk(chunkX, chunkZ, status, load);
    }

    /** A full chunk without the shared four-entry vanilla cache, which is unsafe across threads. */
    @Override
    @Nullable
    public LevelChunk getChunkNow(int chunkX, int chunkZ) {
        if (!Vanadium.config.enabled || !Vanadium.config.chunkCache) {
            return super.getChunkNow(chunkX, chunkZ);
        }
        return loadedChunks.get(ChunkPos.pack(chunkX, chunkZ));
    }

    /**
     * Vanilla/C2ME resolve the holder's ticket level through a synchronized map, and every monster
     * asks this every tick for its own (loaded) chunk. Loaded full chunks answer lock-free; other
     * positions keep the ticket-level answer, which is already true while a ticketed chunk is
     * still loading (commands rely on that before they block on the load).
     */
    @Override
    public boolean hasChunk(int chunkX, int chunkZ) {
        if (Vanadium.config.enabled && Vanadium.config.chunkCache
                && loadedChunks.get(ChunkPos.pack(chunkX, chunkZ)) != null) {
            return true;
        }
        return super.hasChunk(chunkX, chunkZ);
    }

    /** Called from LevelChunk.setLoaded on whichever thread (server or C2ME) changes the state. */
    public void onChunkLoaded(LevelChunk chunk, boolean loaded) {
        long pos = chunk.getPos().pack();
        if (loaded) {
            loadedChunks.put(pos, chunk);
        } else {
            loadedChunks.remove(pos, chunk);
        }
    }

    @SuppressWarnings("resource") // Minecraft owns this shared executor; we only compare its identity.
    private static boolean isMinecraftMainWorker(Thread thread) {
        return thread instanceof ForkJoinWorkerThread worker
                && worker.getPool() == Util.backgroundExecutor().service();
    }

    @Override
    public void close() throws IOException {
        try {
            super.close();
        } finally {
            maintenance.close();
            loadedChunks.clear();
            loadingLocks.evictIdle();
        }
    }
}
