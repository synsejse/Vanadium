package com.synsenetwork.vanadium.chunk;

import com.mojang.datafixers.DataFixer;
import com.synsenetwork.vanadium.Vanadium;
import it.unimi.dsi.fastutil.HashCommon;
import javax.annotation.Nullable;
import net.minecraft.util.Util;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.entity.ChunkStatusUpdateListener;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import net.minecraft.world.level.storage.SavedDataStorage;
import net.minecraft.world.level.storage.LevelStorageSource;
import java.lang.ref.WeakReference;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinWorkerThread;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * A {@link ServerChunkCache} that serves chunk lookups from a thread-safe cache so worker threads
 * can read chunks in parallel. Cache entries are weak references (vanilla chunk storage remains the
 * real owner); a daemon thread clears the cache periodically so it never grows stale or unbounded.
 * Concurrent loads of the same chunk are serialised through a {@link ChunkLockTable}.
 */
public class ParallelChunkManager extends ServerChunkCache {

    private static final long CACHE_TTL_MS = TimeUnit.SECONDS.toMillis(10);

    private final Level world;
    private final ConcurrentHashMap<CacheKey, WeakReference<ChunkAccess>> chunkCache = new ConcurrentHashMap<>();
    private final ChunkLockTable loadingLocks = new ChunkLockTable();

    public ParallelChunkManager(ServerLevel world, LevelStorageSource.LevelStorageAccess session, DataFixer dataFixer,
                                StructureTemplateManager structureManager, Executor workerExecutor,
                                ChunkGenerator chunkGenerator, int viewDistance, int simulationDistance,
                                boolean dsync,
                                ChunkStatusUpdateListener chunkStatusChangeListener,
                                Supplier<SavedDataStorage> persistentStateManagerFactory) {
        super(world, session, dataFixer, structureManager, workerExecutor, chunkGenerator, viewDistance,
                simulationDistance, dsync, chunkStatusChangeListener,
                persistentStateManagerFactory);
        this.world = world;

        Thread cacheCleaner = new Thread(this::cacheCleanupLoop,
                "Vanadium-ChunkCache-Cleaner-" + world.dimension().identifier().getPath());
        cacheCleaner.setDaemon(true);
        cacheCleaner.start();
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
        ChunkAccess cached = lookup(pos, requiredStatus);
        if (cached != null) {
            return cached;
        }

        if (Vanadium.config.parallelChunkLoads) {
            // Serialise loads of this one chunk so only a single thread does the work.
            ChunkLockTable.Held held = loadingLocks.lock(pos, 0);
            try {
                return loadAndCache(chunkX, chunkZ, pos, requiredStatus, load);
            } finally {
                loadingLocks.unlock(held);
            }
        } else {
            synchronized (this) {
                return loadAndCache(chunkX, chunkZ, pos, requiredStatus, load);
            }
        }
    }

    /** Called under the load lock so the next acquirer can see the published chunk. */
    @Nullable
    private ChunkAccess loadAndCache(int chunkX, int chunkZ, long pos, ChunkStatus status, boolean load) {
        ChunkAccess cached = lookup(pos, status);
        if (cached != null) {
            return cached;
        }
        ChunkAccess chunk = super.getChunk(chunkX, chunkZ, status, load);
        if (chunk != null) {
            chunkCache.put(new CacheKey(pos, status.getIndex()), new WeakReference<>(chunk));
        }
        return chunk;
    }

    private static boolean isMinecraftMainWorker(Thread thread) {
        return thread instanceof ForkJoinWorkerThread worker
                && worker.getPool() == Util.backgroundExecutor().service();
    }

    @Nullable
    private ChunkAccess lookup(long chunkPos, ChunkStatus status) {
        WeakReference<ChunkAccess> ref = chunkCache.get(new CacheKey(chunkPos, status.getIndex()));
        return ref != null ? ref.get() : null;
    }

    private void cacheCleanupLoop() {
        while (world.getServer() == null) {
            sleep(1000);
        }
        while (world.getServer().isRunning()) {
            sleep(CACHE_TTL_MS);
            chunkCache.clear();
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    record CacheKey(long chunkPos, int statusIndex) {
        @Override
        public int hashCode() {
            // Plain Long.hashCode folds packed X/Z together, clustering nearby chunk keys.
            return 31 * Long.hashCode(HashCommon.mix(chunkPos)) + statusIndex;
        }
    }
}
