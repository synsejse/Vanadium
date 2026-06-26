package com.synsenetwork.vanadium.debug;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.ChunkPos;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Owns the debug subscriber set and, on one tick out of every {@link #SAMPLE_INTERVAL}, collects
 * per-object tick timings and sends each subscriber a frame for the cell they are standing in.
 * Sampling only one tick per window keeps both the instrumentation overhead and the packet size down.
 */
public final class DebugDispatcher {

    private static final int SAMPLE_INTERVAL = 20; // ticks between samples (~1s)

    private final Set<UUID> subscribers = ConcurrentHashMap.newKeySet();
    private final TickSampler sampler;
    private volatile boolean sampling = false;

    public DebugDispatcher(int cellSize) {
        this.sampler = new TickSampler(cellSize);
    }

    public boolean isRecording() {
        return !subscribers.isEmpty();
    }

    /** True only on the tick being sampled; the enqueue hooks check this before timing their work. */
    public boolean isSampling() {
        return sampling;
    }

    public void subscribe(UUID player) {
        subscribers.add(player);
    }

    public void unsubscribe(UUID player) {
        subscribers.remove(player);
    }

    public boolean isSubscribed(UUID player) {
        return subscribers.contains(player);
    }

    public void setCellSize(int cellSize) {
        sampler.setCellSize(cellSize);
    }

    public void recordEntity(ServerWorld world, int id, double x, double y, double z, long nanos) {
        sampler.recordEntity(world.getRegistryKey(), id, x, y, z, nanos);
    }

    public void recordBlockEntity(ServerWorld world, long pos, long nanos) {
        sampler.recordBlockEntity(world.getRegistryKey(), pos, nanos);
    }

    public void recordChunk(ServerWorld world, int chunkX, int chunkZ, long nanos) {
        sampler.recordChunk(world.getRegistryKey(), chunkX, chunkZ, nanos);
    }

    public void register() {
        ServerTickEvents.START_SERVER_TICK.register(server ->
                sampling = isRecording() && server.getTicks() % SAMPLE_INTERVAL == 0);
        ServerTickEvents.END_SERVER_TICK.register(this::onEndTick);
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            ServerPlayerEntity player = handler.getPlayer();
            if (player != null) {
                unsubscribe(player.getUuid());
            }
        });
    }

    private void onEndTick(MinecraftServer server) {
        if (!sampling) {
            return;
        }
        for (UUID id : subscribers) {
            ServerPlayerEntity player = server.getPlayerManager().getPlayer(id);
            if (player == null) {
                continue;
            }
            ChunkPos chunk = player.getChunkPos();
            int radius = server.getPlayerManager().getViewDistance();
            DebugFramePayload frame = sampler.frameFor(
                    player.getServerWorld().getRegistryKey(), chunk.x, chunk.z, radius);
            ServerPlayNetworking.send(player, frame);
        }
        sampler.reset();
        sampling = false;
    }
}
