package com.synsenetwork.vanadium.integration;

import com.mojang.authlib.GameProfile;
import com.synsenetwork.vanadium.Vanadium;
import com.synsenetwork.vanadium.chunk.ChunkPacketPreparation;
import io.netty.buffer.Unpooled;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import net.minecraft.core.BlockPos;
import net.minecraft.network.Connection;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.ClientboundChunkBatchFinishedPacket;
import net.minecraft.network.protocol.game.ClientboundChunkBatchStartPacket;
import net.minecraft.network.protocol.game.ClientboundLevelChunkPacketData;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.PlayerChunkSender;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.LevelChunk;

final class ChunkPacketChecks {
    static void run(ServerLevel level) {
        List<LevelChunk> chunks = new ArrayList<>();
        try {
            for (int x = 0; x < 8; x++) {
                level.setChunkForced(x, 0, true);
                chunks.add(level.getChunk(x, 0));
            }
            level.setBlockAndUpdate(new BlockPos(0, 100, 0), Blocks.CHEST.defaultBlockState());
            byte[][] reference = new byte[chunks.size()][];
            for (int i = 0; i < chunks.size(); i++) reference[i] = bytes(level, new ClientboundLevelChunkPacketData(chunks.get(i)));
            try (ChunkPacketPreparation ignored = new ChunkPacketPreparation()) {
                ChunkPacketPreparation.prepare(chunks);
                for (int i = 0; i < chunks.size(); i++) {
                    check(ChunkPacketPreparation.payload(chunks.get(i)) != null, "missing prepared section data");
                    check(Arrays.equals(reference[i], bytes(level, new ClientboundLevelChunkPacketData(chunks.get(i)))), "prepared packet differs from vanilla");
                }
            }
            check(ChunkPacketPreparation.payload(chunks.getFirst()) == null, "packet scope retained chunks");
            boolean previous = Vanadium.config.parallelChunkPackets;
            try (ChunkPacketPreparation ignored = new ChunkPacketPreparation()) {
                Vanadium.config.parallelChunkPackets = false;
                ChunkPacketPreparation.prepare(chunks);
                check(ChunkPacketPreparation.payload(chunks.getFirst()) == null, "disabled packet preparation ran");
            } finally { Vanadium.config.parallelChunkPackets = previous; }

            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
            var source = level.getChunkSource();
            source.mainThreadProcessor.managedBlock(() -> System.nanoTime() >= deadline
                    || chunks.stream().allMatch(chunk -> source.chunkMap.getChunkToSend(chunk.getPos().pack()) != null));
            check(chunks.stream().allMatch(chunk -> source.chunkMap.getChunkToSend(chunk.getPos().pack()) != null), "fixture chunks not ready for sending");
            var profile = new GameProfile(UUID.randomUUID(), "PacketChecks");
            var player = new ServerPlayer(level.getServer(), level, profile, ClientInformation.createDefault());
            List<Packet<?>> packets = new ArrayList<>();
            Thread caller = Thread.currentThread();
            player.connection = new ServerGamePacketListenerImpl(level.getServer(), new Connection(PacketFlow.SERVERBOUND), player,
                    CommonListenerCookie.createInitial(profile, false)) {
                @Override public void send(Packet<?> packet) {
                    check(Thread.currentThread() == caller, "packet send moved off the caller");
                    if (packet instanceof ClientboundLevelChunkWithLightPacket chunkPacket) {
                        check(ChunkPacketPreparation.payload(chunks.get(chunkPacket.getX())) != null, "sender did not prepare packets");
                        check(Arrays.equals(reference[chunkPacket.getX()], bytes(level, chunkPacket.getChunkData())), "sender changed chunk data");
                    }
                    packets.add(packet);
                }
            };
            player.setPos(0, 100, 0);
            PlayerChunkSender sender = new PlayerChunkSender(false);
            for (LevelChunk chunk : chunks) sender.markChunkPendingToSend(chunk);
            sender.sendNextChunks(player);
            check(packets.getFirst() instanceof ClientboundChunkBatchStartPacket, "missing batch start");
            check(packets.getLast() instanceof ClientboundChunkBatchFinishedPacket, "missing batch finish");
            check(packets.stream().filter(ClientboundLevelChunkWithLightPacket.class::isInstance).count() == 8, "wrong packet batch size");
            check(ChunkPacketPreparation.payload(chunks.getFirst()) == null, "sender leaked prepared buffers");
            int sent = packets.size();
            sender.sendNextChunks(player);
            check(packets.size() == sent, "sender ignored batch acknowledgment limit");
            player.connection = new ServerGamePacketListenerImpl(level.getServer(), new Connection(PacketFlow.SERVERBOUND), player,
                    CommonListenerCookie.createInitial(profile, false)) {
                @Override public void send(Packet<?> packet) { throw new IllegalStateException("fixture send failure"); }
            };
            PlayerChunkSender failing = new PlayerChunkSender(false);
            for (LevelChunk chunk : chunks) failing.markChunkPendingToSend(chunk);
            try {
                failing.sendNextChunks(player);
                throw new AssertionError("send failure swallowed");
            } catch (IllegalStateException expected) {
                check(expected.getMessage().equals("fixture send failure"), "unexpected send failure");
            }
            check(ChunkPacketPreparation.payload(chunks.getFirst()) == null, "failed sender leaked prepared buffers");
            System.out.println("CHUNK_PACKET_CHECKS_PASSED chunks=8 blockEntity=chest");
        } finally {
            for (int x = 0; x < 8; x++) level.setChunkForced(x, 0, false);
        }
    }

    private static byte[] bytes(ServerLevel level, ClientboundLevelChunkPacketData data) {
        var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), level.registryAccess());
        try {
            data.write(buffer);
            byte[] bytes = new byte[buffer.readableBytes()];
            buffer.readBytes(bytes);
            return bytes;
        } finally { buffer.release(); }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
