package com.synsenetwork.vanadium;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.synsenetwork.vanadium.config.GeneralConfig;
import com.synsenetwork.vanadium.parallelised.threads.GlobalAffinityThreadPool;
import com.synsenetwork.vanadium.parallelised.threads.ThreadedChunksRegion;
import net.minecraft.block.entity.*;
import net.minecraft.entity.Entity;
import net.minecraft.entity.FallingBlockEntity;
import net.minecraft.entity.TntEntity;
import net.minecraft.entity.passive.AllayEntity;
import net.minecraft.entity.projectile.ProjectileEntity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.World;
import net.minecraft.world.chunk.BlockEntityTickInvoker;
import net.minecraft.world.chunk.WorldChunk;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

public class ParallelProcessor {

    private static final Logger LOGGER = LogManager.getLogger();

    static Phaser worldPhaser;

    static ExecutorService worldPool;
    static MinecraftServer mcs;
    static AtomicBoolean isTicking = new AtomicBoolean();

    private static final ConcurrentHashMap<ServerWorld, List<Runnable>> delayedChunkTasks = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<ServerWorld, List<Runnable>> delayedEntityTasks = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<ServerWorld, List<Runnable>> delayedBlockEntityTasks = new ConcurrentHashMap<>();

    static Map<String, Set<Thread>> mcThreadTracker = new ConcurrentHashMap<String, Set<Thread>>();

    // List of ThreadedChunksRegion
    public static final List<ThreadedChunksRegion> threadedChunksRegions = new ArrayList<>();

    private static final Set<ThreadedChunksRegion> pendingRegionsToAdd = ConcurrentHashMap.newKeySet();

    private static final Set<ThreadedChunksRegion> pendingRegionsToRemove = ConcurrentHashMap.newKeySet();

    private static final Cache<ChunkPos, ThreadedChunksRegion> chunkRegionCache = Caffeine.newBuilder()
            .initialCapacity(10000)
            .maximumSize(10000)
            .build();

    public static void resetThreadedChunksRegions() {
        synchronized (threadedChunksRegions) {
            for (ThreadedChunksRegion region : threadedChunksRegions) {
                region.shutdownExecutors();
            }
            threadedChunksRegions.clear();
            chunkRegionCache.invalidateAll(); // Invalidate cache when regions are reset
        }
    }

    public static void addThreadedChunksRegion(ThreadedChunksRegion region) {
        synchronized (pendingRegionsToAdd) {
            pendingRegionsToAdd.add(region);
            region.getSingleThreadExecutor(); // Force load executor once to avoid blocking when there is no chunk tick
        }
    }


    public static void removeThreadedChunksRegion(ThreadedChunksRegion region) {
        synchronized (threadedChunksRegions) {
            pendingRegionsToRemove.add(region);
        }
    }

    public static void removeThreadedChunksRegionByName(String name) {
        synchronized (threadedChunksRegions) {
            ThreadedChunksRegion region = null;
            for (ThreadedChunksRegion r : threadedChunksRegions) {
                if (r.getName().equals(name)) {
                    region = r;
                    break;
                }
            }
            if (region != null) {
                removeThreadedChunksRegion(region);
            }
        }
    }

    private static ThreadedChunksRegion findMatchingRegion(int chunkX, int chunkZ, World world) {
        ChunkPos pos = new ChunkPos(chunkX, chunkZ);
        return chunkRegionCache.get(pos, key -> {
            synchronized (threadedChunksRegions) {
                String worldId = world.getRegistryKey().getValue().toString();

                // Single stream operation to find the smallest matching region
                return threadedChunksRegions.stream()
                        .filter(region -> region.contains(worldId, chunkX, chunkZ))
                        .min(Comparator.comparingLong(ThreadedChunksRegion::getArea))
                        .orElse(null);
            }
        });
    }

    public static void setupThreadPool(int parallelism) {
        GlobalAffinityThreadPool.getAffinitySharedPool();

        worldPool = GlobalAffinityThreadPool.getAffinityWorldAndRegionPool();
        for (int i = 0; i < 2; i++)
            GlobalAffinityThreadPool.increaseWorldAndRegionPoolSize();
    }

    public static void regThread(String poolName, Thread thread) {
        mcThreadTracker.computeIfAbsent(poolName, s -> ConcurrentHashMap.newKeySet()).add(thread);
    }

    public static boolean isThreadPooled(String poolName, Thread t) {
        return mcThreadTracker.containsKey(poolName) && mcThreadTracker.get(poolName).contains(t);
    }

    public static boolean serverExecutionThreadPatch(MinecraftServer ms) {
        return isThreadPooled("Vanadium-World", Thread.currentThread()) || isThreadPooled("Vanadium-Tick", Thread.currentThread());
    }

    static long tickStart = 0;
    static GeneralConfig config;

    public static void preTick(int size, MinecraftServer server) {
        config = Vanadium.config;
        if (!config.disabled && !config.disableWorld) {
            if (worldPhaser != null) {
                LOGGER.warn("Multiple servers?");
                return;
            } else {
                tickStart = System.nanoTime();
                isTicking.set(true);
                worldPhaser = new Phaser(size + 1);
                mcs = server;
            }
        }
        // Process pending regions
        synchronized (pendingRegionsToAdd) {
            if (!pendingRegionsToAdd.isEmpty()) {
                synchronized (threadedChunksRegions) {
                    for (ThreadedChunksRegion region : pendingRegionsToAdd) {
                        threadedChunksRegions.add(region);
                        GlobalAffinityThreadPool.increaseWorldAndRegionPoolSize();
                    }
                    chunkRegionCache.invalidateAll();
                    pendingRegionsToAdd.clear();
                }
            }
        }
        synchronized (pendingRegionsToRemove) {
            if (!pendingRegionsToRemove.isEmpty()) {
                synchronized (pendingRegionsToRemove) {
                    for (ThreadedChunksRegion region : pendingRegionsToRemove) {
                        threadedChunksRegions.remove(region);
                        region.shutdownExecutors();
                        GlobalAffinityThreadPool.decreaseWorldAndRegionPoolSize();
                    }
                    chunkRegionCache.invalidateAll();
                    pendingRegionsToRemove.clear();
                }
            }
        }
    }


    public static void callTick(ServerWorld serverworld, BooleanSupplier hasTimeLeft, MinecraftServer server) {
        if (config.disabled || config.disableWorld) {
            try {
                serverworld.tick(hasTimeLeft);
            } catch (Exception e) {
                throw e;
            }
            return;
        }
        if (mcs != server) {
            LOGGER.warn("Multiple servers?");
            config.disabled = true;
            serverworld.tick(hasTimeLeft);
            return;
        } else {
            worldPool.execute(() -> {
                try {
                    serverworld.tick(hasTimeLeft);
                } finally {
                    worldPhaser.arriveAndDeregister();
                }
            });
        }
    }

    public static void postTick(MinecraftServer server) {
        if (!config.disabled && !config.disableWorld) {
            if (mcs != server) {
                LOGGER.warn("Multiple servers?");
                return;
            } else {
                worldPhaser.arriveAndAwaitAdvance();
                isTicking.set(false);
                worldPhaser = null;
            }
        }
    }


    public static void preChunkTick(ServerWorld world) {
        if (config.disabled || config.disableEnvironment) {
            return;
        }
        synchronized (threadedChunksRegions) {
            for (ThreadedChunksRegion region : threadedChunksRegions) {
                if (region.getWorldId().equals(world.getRegistryKey().getValue().toString())) {
                    // Register the main thread in the region's chunkTickPhaser
//                    region.getChunkTickPhaser().register();
                }
            }
        }
    }


    public static void callTickChunks(ServerWorld world, WorldChunk chunk, int k) {
        int chunkX = chunk.getPos().x;
        int chunkZ = chunk.getPos().z;

        ThreadedChunksRegion matchingRegion = findMatchingRegion(chunkX, chunkZ, world);
        if (matchingRegion == null) {
            // No matching region, delay processing
            delayedChunkTasks.computeIfAbsent(world, w -> new ArrayList<>())
                    .add(() -> world.tickChunk(chunk, k));
            return;
        }

        Executor executor = matchingRegion.getChunkTickExecutor();

        matchingRegion.getChunkTickPhaser().register();

        executor.execute(() -> {
            try {
                world.tickChunk(chunk, k);
            } finally {
                matchingRegion.getChunkTickPhaser().arrive();
            }
        });
    }

    public static void postChunkTick(ServerWorld world) {
        synchronized (threadedChunksRegions) {
            for (ThreadedChunksRegion region : threadedChunksRegions) {
                // Region's post-chunk-tick handler
                if (region.getWorldId().equals(world.getRegistryKey().getValue().toString())) {
                    GlobalAffinityThreadPool.getAffinitySharedPool().execute(region::postChunkTick);
                }
            }
        }

        // Process delayed chunk tasks
        List<Runnable> tasks = delayedChunkTasks.remove(world);
        if (tasks != null) {
            for (Runnable task : tasks) {
                task.run();
            }
        }
    }


    public static void preEntityTick(ServerWorld world) {
        if (config.disabled || config.disableEntity) {
            return;
        }
        synchronized (threadedChunksRegions) {
            for (ThreadedChunksRegion region : threadedChunksRegions) {
                if (region.getWorldId().equals(world.getRegistryKey().getValue().toString())) {
                    // Register the main thread in the region's entityTickPhaser
//                    region.getEntityTickPhaser().register();
                }
            }
        }
    }


    public static void callEntityTick(Consumer<Entity> tickConsumer, Entity entityIn, ServerWorld serverworld) {
        if (config.disabled || config.disableEntity) {
            tickConsumer.accept(entityIn);
            return;
        }

        if (shouldTickPortalSynchronously(entityIn)) {
            tickConsumer.accept(entityIn);
            return;
        }

        int chunkX = entityIn.getChunkPos().x;
        int chunkZ = entityIn.getChunkPos().z;

        ThreadedChunksRegion matchingRegion = findMatchingRegion(chunkX, chunkZ, serverworld);
        if (matchingRegion == null) {
            // No matching region, delay processing
            delayedEntityTasks.computeIfAbsent(serverworld, w -> new ArrayList<>())
                    .add(() -> tickConsumer.accept(entityIn));
            return;
        }

        Executor executor = shouldUseSingleThread(entityIn) ?
                matchingRegion.getSingleThreadExecutor() :
                matchingRegion.getEntityTickExecutor();


        matchingRegion.getEntityTickPhaser().register();
        executor.execute(() -> {
            try {
                // Wait for chunk tick stage to complete in this region
                matchingRegion.getChunkTickPhaser().awaitAdvance(0);

                tickConsumer.accept(entityIn);
            } finally {
                matchingRegion.getEntityTickPhaser().arrive();
            }
        });
    }

    private static boolean shouldUseSingleThread(Entity entity) {
        return entity instanceof FallingBlockEntity ||
                entity instanceof AllayEntity ||
                entity instanceof TntEntity;
    }

    private static boolean shouldTickPortalSynchronously(Entity entity) {
        if (entity.portalManager != null && entity.portalManager.isInPortal()) {
            return true;
        }
        return entity instanceof ProjectileEntity;
    }

    public static void postEntityTick(ServerWorld world) {
        synchronized (threadedChunksRegions) {
            for (ThreadedChunksRegion region : threadedChunksRegions) {
                if (region.getWorldId().equals(world.getRegistryKey().getValue().toString())) {
                    GlobalAffinityThreadPool.getAffinitySharedPool().execute(region::postEntityTick);

                }
            }
        }

        // Process delayed entity tasks
        List<Runnable> tasks = delayedEntityTasks.remove(world);
        if (tasks != null) {
            for (Runnable task : tasks) {
                task.run();
            }
        }

    }

    public static void preBlockEntityTick(ServerWorld world) {
        if (config.disabled || config.disableBlockEntity) {
            return;
        }
        synchronized (threadedChunksRegions) {
            for (ThreadedChunksRegion region : threadedChunksRegions) {
                if (region.getWorldId().equals(world.getRegistryKey().getValue().toString())) {
//                    region.getBlockEntityTickPhaser().register();
                }
            }
        }
    }

    public static void callBlockEntityTick(BlockEntityTickInvoker tte, World world) {
        if (!(world instanceof ServerWorld) || !(tte instanceof WorldChunk.WrappedBlockEntityTickInvoker wrappedInvoker)) {
            tte.tick();
            return;
        }

        if (!(wrappedInvoker.wrapped instanceof WorldChunk.DirectBlockEntityTickInvoker<?>)) {
            tte.tick();
            return;
        }

        if (config.disabled || config.disableBlockEntity) {
            tte.tick();
            return;
        }

        BlockEntity blockEntity = ((WorldChunk.DirectBlockEntityTickInvoker<?>) wrappedInvoker.wrapped).blockEntity;
        int chunkX = blockEntity.getPos().getX() >> 4;
        int chunkZ = blockEntity.getPos().getZ() >> 4;

        ThreadedChunksRegion matchingRegion = findMatchingRegion(chunkX, chunkZ, world);
        if (matchingRegion == null) {
            // No matching region, delay processing
            delayedBlockEntityTasks.computeIfAbsent((ServerWorld) world, w -> new ArrayList<>())
                    .add(tte::tick);
            return;
        }

        Executor executor = shouldUseSingleThread(blockEntity) ?
                matchingRegion.getSingleThreadExecutor() :
                matchingRegion.getBlockEntityTickExecutor();


        matchingRegion.getBlockEntityTickPhaser().register();
        executor.execute(() -> {
            try {
                // Wait for entity tick stage to complete in this region
                matchingRegion.getEntityTickPhaser().awaitAdvance(0);

                tte.tick();
            } finally {
                matchingRegion.getBlockEntityTickPhaser().arrive();
            }
        });
    }


    private static boolean shouldUseSingleThread(BlockEntity blockEntity) {
        return blockEntity instanceof PistonBlockEntity ||
                blockEntity instanceof SculkSensorBlockEntity ||
                blockEntity instanceof SculkShriekerBlockEntity ||
                blockEntity instanceof SculkCatalystBlockEntity;
    }

    public static void postBlockEntityTick(ServerWorld world) {
        synchronized (threadedChunksRegions) {
            for (ThreadedChunksRegion region : threadedChunksRegions) {
                if (region.getWorldId().equals(world.getRegistryKey().getValue().toString())) {
                    GlobalAffinityThreadPool.getAffinitySharedPool().execute(region::postBlockEntityTick);
                }
            }
        }

        // Process delayed block entity tasks
        List<Runnable> tasks = delayedBlockEntityTasks.remove(world);
        if (tasks != null) {
            for (Runnable task : tasks) {
                task.run();
            }
        }

        synchronized (threadedChunksRegions) {
            for (ThreadedChunksRegion region : threadedChunksRegions) {
                if (region.getWorldId().equals(world.getRegistryKey().getValue().toString())) {
                    region.getBlockEntityTickPhaser().awaitAdvance(0);
                    region.initializePhaser();
                }
            }
        }

    }

    public static boolean shouldThreadChunks() {
        return !Vanadium.config.disableMultiChunk;
    }

}
