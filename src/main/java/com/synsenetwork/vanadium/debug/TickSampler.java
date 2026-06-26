package com.synsenetwork.vanadium.debug;

import net.minecraft.registry.RegistryKey;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Collects per-object tick timings during a sampled tick. The {@code record*} methods are called from
 * worker threads as objects finish ticking, so the per-world buckets are concurrent. {@link #frameFor}
 * and {@link #reset} run on the server thread after the tick's waves have completed.
 */
public final class TickSampler {

    private static final class Bucket {
        final Queue<DebugFramePayload.EntityTick> entities = new ConcurrentLinkedQueue<>();
        final Queue<DebugFramePayload.BlockEntityTick> blockEntities = new ConcurrentLinkedQueue<>();
        final Queue<DebugFramePayload.ChunkTick> chunks = new ConcurrentLinkedQueue<>();
    }

    private final Map<RegistryKey<World>, Bucket> byWorld = new ConcurrentHashMap<>();
    private volatile int cellSize;

    public TickSampler(int cellSize) {
        this.cellSize = cellSize;
    }

    public void setCellSize(int cellSize) {
        this.cellSize = cellSize;
    }

    public void recordEntity(RegistryKey<World> world, int id, double x, double y, double z, long nanos) {
        byWorld.computeIfAbsent(world, w -> new Bucket()).entities
                .add(new DebugFramePayload.EntityTick(id, x, y, z, nanos));
    }

    public void recordBlockEntity(RegistryKey<World> world, long pos, long nanos) {
        byWorld.computeIfAbsent(world, w -> new Bucket()).blockEntities
                .add(new DebugFramePayload.BlockEntityTick(pos, nanos));
    }

    public void recordChunk(RegistryKey<World> world, int chunkX, int chunkZ, long nanos) {
        byWorld.computeIfAbsent(world, w -> new Bucket()).chunks
                .add(new DebugFramePayload.ChunkTick(chunkX, chunkZ, nanos));
    }

    /** Builds a frame of the samples within {@code chunkRadius} chunks of (playerChunkX, playerChunkZ). */
    public DebugFramePayload frameFor(RegistryKey<World> world, int playerChunkX, int playerChunkZ, int chunkRadius,
                                      DebugFramePayload.Stats stats) {
        List<DebugFramePayload.EntityTick> entities = new ArrayList<>();
        List<DebugFramePayload.BlockEntityTick> blockEntities = new ArrayList<>();
        List<DebugFramePayload.ChunkTick> chunks = new ArrayList<>();

        Bucket bucket = byWorld.get(world);
        if (bucket != null) {
            for (DebugFramePayload.EntityTick e : bucket.entities) {
                if (inRadius((int) Math.floor(e.x()) >> 4, (int) Math.floor(e.z()) >> 4,
                        playerChunkX, playerChunkZ, chunkRadius)) {
                    entities.add(e);
                }
            }
            for (DebugFramePayload.BlockEntityTick b : bucket.blockEntities) {
                BlockPos pos = BlockPos.fromLong(b.pos());
                if (inRadius(pos.getX() >> 4, pos.getZ() >> 4, playerChunkX, playerChunkZ, chunkRadius)) {
                    blockEntities.add(b);
                }
            }
            for (DebugFramePayload.ChunkTick c : bucket.chunks) {
                if (inRadius(c.chunkX(), c.chunkZ(), playerChunkX, playerChunkZ, chunkRadius)) {
                    chunks.add(c);
                }
            }
        }
        return new DebugFramePayload(cellSize, stats, entities, blockEntities, chunks);
    }

    public void reset() {
        byWorld.clear();
    }

    private static boolean inRadius(int chunkX, int chunkZ, int playerChunkX, int playerChunkZ, int radius) {
        return Math.abs(chunkX - playerChunkX) <= radius && Math.abs(chunkZ - playerChunkZ) <= radius;
    }
}
