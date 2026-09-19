package com.synsenetwork.vanadium.integration;

import com.mojang.authlib.GameProfile;
import it.unimi.dsi.fastutil.longs.LongSet;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ChunkResult;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.LevelChunk;

/** Live Fabric regression checks; packaged only by blockUpdateChecksJar. */
public class BlockUpdateChecks implements ModInitializer {
    public void onInitialize() {
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            try {
                checkBlocks(server);
                checkDirty(server);
                TickSafetyChecks.run(server.overworld());
                System.out.println("BLOCK_AND_DIRTY_CHECKS_PASSED");
            } catch (Exception e) { throw new RuntimeException(e); }
        });
    }

    private static void checkBlocks(MinecraftServer server) throws Exception {
        ServerLevel level = server.overworld();
        BlockPos pos = new BlockPos(1000, 100, 1000);
        LevelChunk chunk = level.getChunkAt(pos);
        GameProfile profile = new GameProfile(UUID.randomUUID(), "BlockChecks");
        ServerPlayer player = new ServerPlayer(server, level, profile, ClientInformation.createDefault());
        List<Packet<?>> packets = new ArrayList<>();
        player.connection = new ServerGamePacketListenerImpl(server, new Connection(PacketFlow.SERVERBOUND), player,
                CommonListenerCookie.createInitial(profile, false)) {
            @Override public void send(Packet<?> packet) { packets.add(packet); }
        };
        player.setPos(1000.5, 100, 1002.5);
        ChunkHolder holder = new ChunkHolder(chunk.getPos(), 31, level, level.getChunkSource().getLightEngine(),
                (p, old, next, setter) -> {}, (p, border) -> List.of(player)) {
            @Override public LevelChunk getTickingChunk() { return chunk; }
            // C2ME's no-tick view distance reads the full-chunk future instead.
            @Override public CompletableFuture<ChunkResult<LevelChunk>> getFullChunkFuture() {
                return CompletableFuture.completedFuture(ChunkResult.of(chunk));
            }
        };
        check(holder.getTickingChunk() == chunk, "fixture ticking chunk missing");
        int sequence = 0;
        for (GameType mode : List.of(GameType.CREATIVE, GameType.SURVIVAL)) {
            player.setGameMode(mode);
            player.setOnGround(true);
            for (int count : new int[]{1, 2, 1}) {
                List<BlockPos> changed = new ArrayList<>();
                for (int i = 0; i < count; i++) {
                    BlockPos target = pos.offset(i, 0, 0);
                    level.setBlockAndUpdate(target, Blocks.DIRT.defaultBlockState());
                    player.gameMode.handleBlockBreakAction(target, ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK,
                            Direction.UP, level.getMaxY(), ++sequence);
                    if (mode == GameType.SURVIVAL) {
                        for (int tick = 0; tick < 40; tick++) player.gameMode.tick();
                        player.gameMode.handleBlockBreakAction(target, ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK,
                                Direction.UP, level.getMaxY(), ++sequence);
                    }
                    check(level.getBlockState(target).isAir(), mode + " server did not break block " + target);
                    holder.blockChanged(target);
                    check(holder.hasChangesToBroadcast(), "block update was not queued");
                    changed.add(target);
                }
                packets.clear();
                holder.broadcastChanges(chunk);
                List<BlockPos> sent = new ArrayList<>();
                for (Packet<?> packet : packets) {
                    if (packet instanceof ClientboundBlockUpdatePacket update) {
                        check(update.getBlockState().isAir(), "wrong single-block state");
                        sent.add(update.getPos());
                    } else if (packet instanceof ClientboundSectionBlocksUpdatePacket update) {
                        update.runUpdates((p, state) -> {
                            check(state.isAir(), "wrong section-block state");
                            sent.add(p.immutable());
                        });
                    }
                }
                check(sent.size() == changed.size() && sent.containsAll(changed), mode + " missing block updates: expected=" + changed + " actual=" + sent);
            }
        }
        System.out.println("BLOCK_PACKETS_PASSED");
    }

    private static void checkDirty(MinecraftServer server) throws Exception {
        ChunkMap map = server.overworld().getChunkSource().chunkMap;
        Method mark = ChunkMap.class.getDeclaredMethod("setChunkUnsaved", ChunkPos.class);
        Field field = ChunkMap.class.getDeclaredField("chunksToEagerlySave");
        mark.setAccessible(true);
        field.setAccessible(true);
        LongSet dirty = (LongSet) field.get(map);
        var pool = Executors.newFixedThreadPool(4, Thread.ofPlatform().daemon().factory());
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<?>> tasks = new ArrayList<>();
            for (int worker = 0; worker < 4; worker++) {
                int index = worker;
                tasks.add(pool.submit(() -> {
                    try {
                        check(start.await(10, TimeUnit.SECONDS), "start timeout");
                        for (int i = 0; i < 4096; i++) mark.invoke(map, new ChunkPos(100000 + index, i));
                    } catch (Exception e) { throw new RuntimeException(e); }
                }));
            }
            start.countDown();
            for (var task : tasks) task.get(15, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
            check(pool.awaitTermination(5, TimeUnit.SECONDS), "dirty-chunk workers did not stop");
        }
        // C2ME can replace vanilla eager saving. The regular chunk-map tick must also
        // collect worker notifications before C2ME's idle autosave scans the vanilla set.
        Method tick = ChunkMap.class.getDeclaredMethod("tick", BooleanSupplier.class);
        tick.setAccessible(true);
        tick.invoke(map, (BooleanSupplier) () -> false);
        for (int worker = 0; worker < 4; worker++) {
            for (int i = 0; i < 4096; i++) check(dirty.contains(ChunkPos.pack(100000 + worker, i)), "dirty chunk lost: " + worker + "/" + i);
        }
        System.out.println("DIRTY_CHUNK_CONCURRENCY_PASSED");
    }

    private static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }
}
