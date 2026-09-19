package com.synsenetwork.vanadium.chunk;

import com.synsenetwork.vanadium.Vanadium;
import io.netty.buffer.Unpooled;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundLevelChunkPacketData;
import net.minecraft.world.level.chunk.LevelChunk;

/** Section serialization between world ticks. Block-entity callbacks and light snapshots stay on the caller. */
public final class ChunkPacketPreparation implements AutoCloseable {
    private static final ThreadLocal<Map<LevelChunk, byte[]>> ACTIVE = new ThreadLocal<>();
    private final Map<LevelChunk, byte[]> previous = ACTIVE.get();

    public ChunkPacketPreparation() {
        ACTIVE.set(new IdentityHashMap<>());
    }

    public static void prepare(List<LevelChunk> chunks) {
        Map<LevelChunk, byte[]> batch = ACTIVE.get();
        if (batch == null || !Vanadium.config.enabled || !Vanadium.config.parallelChunkPackets || chunks.size() < 4) return;
        // C2ME may enlarge batches; cap retained buffers and let later chunks use the vanilla path.
        int count = Math.min(64, chunks.size());
        byte[][] payloads = new byte[count][];
        List<Runnable> tasks = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            int index = i;
            LevelChunk chunk = chunks.get(i);
            tasks.add(() -> {
                int size = 0;
                for (var section : chunk.getSections()) size += section.getSerializedSize();
                byte[] bytes = new byte[size];
                FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.wrappedBuffer(bytes));
                try {
                    buffer.writerIndex(0);
                    ClientboundLevelChunkPacketData.extractChunkData(buffer, chunk);
                    payloads[index] = bytes;
                } finally { buffer.release(); }
            });
        }
        Vanadium.scheduler.prepare(tasks);
        for (int i = 0; i < count; i++) batch.put(chunks.get(i), payloads[i]);
    }

    public static byte[] payload(LevelChunk chunk) {
        Map<LevelChunk, byte[]> batch = ACTIVE.get();
        return batch == null ? null : batch.get(chunk);
    }

    @Override public void close() {
        if (previous == null) ACTIVE.remove();
        else ACTIVE.set(previous);
    }
}
