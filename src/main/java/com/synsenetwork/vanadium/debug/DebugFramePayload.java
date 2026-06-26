package com.synsenetwork.vanadium.debug;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.List;

/**
 * A sampled tick's per-object timings for the cell a subscribed player is standing in. Sent once per
 * sampling window (see the dispatcher). Durations are in nanoseconds.
 */
public record DebugFramePayload(int cellSize,
                                Stats stats,
                                List<EntityTick> entities,
                                List<BlockEntityTick> blockEntities,
                                List<ChunkTick> chunks) implements CustomPayload {

    /**
     * Aggregate scheduler telemetry for the sampling window, for the HUD panel. The {@code *Nanos} and
     * count fields are summed over {@code ticks}; the client divides by {@code ticks} for per-tick figures.
     */
    public record Stats(int ticks, int workers,
                        long chunkNanos, long entityNanos, long blockEntityNanos,
                        long workNanos,
                        int cellsRun, int chunksTicked, int entitiesTicked, int blockEntitiesTicked,
                        long cacheHits, long cacheMisses, long cacheBounces,
                        float mspt) {
    }

    /** One entity's tick: its network id, position at sample time, and how long ticking it took. */
    public record EntityTick(int id, double x, double y, double z, long nanos) {
    }

    /** One block entity's tick: its packed {@code BlockPos} and how long ticking it took. */
    public record BlockEntityTick(long pos, long nanos) {
    }

    /** One chunk's tick: its position and how long ticking it took. */
    public record ChunkTick(int chunkX, int chunkZ, long nanos) {
    }

    public static final CustomPayload.Id<DebugFramePayload> ID =
            new CustomPayload.Id<>(Identifier.of("vanadium", "debug_frame"));

    public static final PacketCodec<PacketByteBuf, DebugFramePayload> CODEC =
            PacketCodec.of(DebugFramePayload::write, DebugFramePayload::read);

    private void write(PacketByteBuf buf) {
        buf.writeVarInt(cellSize);

        buf.writeVarInt(stats.ticks());
        buf.writeVarInt(stats.workers());
        buf.writeVarLong(stats.chunkNanos());
        buf.writeVarLong(stats.entityNanos());
        buf.writeVarLong(stats.blockEntityNanos());
        buf.writeVarLong(stats.workNanos());
        buf.writeVarInt(stats.cellsRun());
        buf.writeVarInt(stats.chunksTicked());
        buf.writeVarInt(stats.entitiesTicked());
        buf.writeVarInt(stats.blockEntitiesTicked());
        buf.writeVarLong(stats.cacheHits());
        buf.writeVarLong(stats.cacheMisses());
        buf.writeVarLong(stats.cacheBounces());
        buf.writeFloat(stats.mspt());

        buf.writeVarInt(entities.size());
        for (EntityTick e : entities) {
            buf.writeVarInt(e.id());
            buf.writeDouble(e.x());
            buf.writeDouble(e.y());
            buf.writeDouble(e.z());
            buf.writeVarLong(e.nanos());
        }

        buf.writeVarInt(blockEntities.size());
        for (BlockEntityTick b : blockEntities) {
            buf.writeLong(b.pos());
            buf.writeVarLong(b.nanos());
        }

        buf.writeVarInt(chunks.size());
        for (ChunkTick c : chunks) {
            buf.writeVarInt(c.chunkX());
            buf.writeVarInt(c.chunkZ());
            buf.writeVarLong(c.nanos());
        }
    }

    private static DebugFramePayload read(PacketByteBuf buf) {
        int cellSize = buf.readVarInt();

        Stats stats = new Stats(buf.readVarInt(), buf.readVarInt(),
                buf.readVarLong(), buf.readVarLong(), buf.readVarLong(),
                buf.readVarLong(),
                buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt(),
                buf.readVarLong(), buf.readVarLong(), buf.readVarLong(),
                buf.readFloat());

        int entityCount = buf.readVarInt();
        List<EntityTick> entities = new ArrayList<>(entityCount);
        for (int i = 0; i < entityCount; i++) {
            entities.add(new EntityTick(buf.readVarInt(),
                    buf.readDouble(), buf.readDouble(), buf.readDouble(), buf.readVarLong()));
        }

        int blockEntityCount = buf.readVarInt();
        List<BlockEntityTick> blockEntities = new ArrayList<>(blockEntityCount);
        for (int i = 0; i < blockEntityCount; i++) {
            blockEntities.add(new BlockEntityTick(buf.readLong(), buf.readVarLong()));
        }

        int chunkCount = buf.readVarInt();
        List<ChunkTick> chunks = new ArrayList<>(chunkCount);
        for (int i = 0; i < chunkCount; i++) {
            chunks.add(new ChunkTick(buf.readVarInt(), buf.readVarInt(), buf.readVarLong()));
        }

        return new DebugFramePayload(cellSize, stats, entities, blockEntities, chunks);
    }

    @Override
    public CustomPayload.Id<? extends CustomPayload> getId() {
        return ID;
    }
}
