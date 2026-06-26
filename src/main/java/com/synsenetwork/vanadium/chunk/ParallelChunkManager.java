package com.synsenetwork.vanadium.chunk;

import com.mojang.datafixers.DataFixer;
import com.synsenetwork.vanadium.Vanadium;
import com.synsenetwork.vanadium.tick.SchedulerStats;
import net.minecraft.server.WorldGenerationProgressListener;
import net.minecraft.server.world.ServerChunkManager;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.structure.StructureTemplateManager;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.PersistentStateManager;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.ChunkStatus;
import net.minecraft.world.chunk.ChunkStatusChangeListener;
import net.minecraft.world.gen.chunk.ChunkGenerator;
import net.minecraft.world.level.storage.LevelStorage;

import javax.annotation.Nullable;
import java.lang.ref.WeakReference;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * A {@link ServerChunkManager} that serves chunk lookups from a thread-safe cache so worker threads
 * can read chunks in parallel. Cache entries are weak references (vanilla chunk storage remains the
 * real owner); a daemon thread clears the cache periodically so it never grows stale or unbounded.
 * Concurrent loads of the same chunk are serialised through a {@link ChunkLockTable}.
 */
public class ParallelChunkManager extends ServerChunkManager {

    private static final long CACHE_TTL_MS = TimeUnit.SECONDS.toMillis(10);

    private final World world;
    private final ConcurrentHashMap<CacheKey, WeakReference<Chunk>> chunkCache = new ConcurrentHashMap<>();
    private final ChunkLockTable loadingLocks = new ChunkLockTable();

    public ParallelChunkManager(ServerWorld world, LevelStorage.Session session, DataFixer dataFixer,
                                StructureTemplateManager structureManager, Executor workerExecutor,
                                ChunkGenerator chunkGenerator, int viewDistance, int simulationDistance,
                                boolean dsync, WorldGenerationProgressListener worldGenerationProgressListener,
                                ChunkStatusChangeListener chunkStatusChangeListener,
                                Supplier<PersistentStateManager> persistentStateManagerFactory) {
        super(world, session, dataFixer, structureManager, workerExecutor, chunkGenerator, viewDistance,
                simulationDistance, dsync, worldGenerationProgressListener, chunkStatusChangeListener,
                persistentStateManagerFactory);
        this.world = world;

        Thread cacheCleaner = new Thread(this::cacheCleanupLoop,
                "Vanadium-ChunkCache-Cleaner-" + world.getRegistryKey().getValue().getPath());
        cacheCleaner.setDaemon(true);
        cacheCleaner.start();
    }

    @Override
    @Nullable
    public Chunk getChunk(int chunkX, int chunkZ, ChunkStatus requiredStatus, boolean load) {
        // A server "Main" worker thread must not load chunks off the main thread; bounce it back.
        if (McThreadTracker.isPooled("Main", Thread.currentThread())) {
            bounce();
            return CompletableFuture.supplyAsync(
                    () -> getChunk(chunkX, chunkZ, requiredStatus, load), this.mainThreadExecutor).join();
        }
        if (Vanadium.config.disabled || Vanadium.config.disableChunkProvider) {
            return super.getChunk(chunkX, chunkZ, requiredStatus, load);
        }

        long pos = ChunkPos.toLong(chunkX, chunkZ);
        Chunk cached = lookup(pos, requiredStatus);
        if (cached != null) {
            hit();
            return cached;
        }

        Chunk chunk;
        if (!Vanadium.config.disableMultiChunk) {
            // Serialise loads of this one chunk so only a single thread does the work.
            ChunkLockTable.Held held = loadingLocks.lock(pos, 0);
            try {
                Chunk c = lookup(pos, requiredStatus);
                if (c != null) {
                    hit();
                    return c;
                }
                miss();
                chunk = super.getChunk(chunkX, chunkZ, requiredStatus, load);
            } finally {
                loadingLocks.unlock(held);
            }
        } else {
            synchronized (this) {
                Chunk c = lookup(pos, requiredStatus);
                if (c != null) {
                    hit();
                    return c;
                }
                miss();
                chunk = super.getChunk(chunkX, chunkZ, requiredStatus, load);
            }
        }

        chunkCache.put(new CacheKey(pos, requiredStatus.getIndex()), new WeakReference<>(chunk));
        return chunk;
    }

    @Nullable
    private Chunk lookup(long chunkPos, ChunkStatus status) {
        WeakReference<Chunk> ref = chunkCache.get(new CacheKey(chunkPos, status.getIndex()));
        return ref != null ? ref.get() : null;
    }

    private void hit() {
        SchedulerStats s = Vanadium.stats;
        if (s != null && s.isEnabled()) s.cacheHit();
    }

    private void miss() {
        SchedulerStats s = Vanadium.stats;
        if (s != null && s.isEnabled()) s.cacheMiss();
    }

    private void bounce() {
        SchedulerStats s = Vanadium.stats;
        if (s != null && s.isEnabled()) s.cacheBounce();
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

    private record CacheKey(long chunkPos, int statusIndex) {
    }
}
