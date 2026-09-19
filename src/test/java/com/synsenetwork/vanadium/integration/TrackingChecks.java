package com.synsenetwork.vanadium.integration;

import com.mojang.authlib.GameProfile;
import com.synsenetwork.vanadium.Vanadium;
import com.synsenetwork.vanadium.tick.Stage;
import com.synsenetwork.vanadium.tick.TickScheduler;
import com.synsenetwork.vanadium.tick.WorkerPool;
import com.synsenetwork.vanadium.tracking.NearbyTrackers;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.animal.pig.Pig;

final class TrackingChecks {
    static void run(ServerLevel level) {
        TickScheduler previous = Vanadium.scheduler;
        try (WorkerPool pool = new WorkerPool(4)) {
            Vanadium.scheduler = new TickScheduler(pool, 1);
            NearbyTrackers index = new NearbyTrackers();
            List<ServerPlayer> players = new ArrayList<>();
            List<Set<ServerPlayer>> watchers = new ArrayList<>();
            List<ChunkMap.TrackedEntity> playerTrackers = new ArrayList<>();
            List<ChunkMap.TrackedEntity> mobTrackers = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                ServerPlayer player = new ServerPlayer(level.getServer(), level,
                        new GameProfile(UUID.randomUUID(), "Tracking" + i), ClientInformation.createDefault());
                player.setPos(0, 100, 0);
                players.add(player);
            }
            for (ServerPlayer player : players) {
                var tracker = tracker(level, player, ConcurrentHashMap.newKeySet());
                playerTrackers.add(tracker);
                index.add(tracker, players);
            }
            for (int i = 0; i < 160; i++) {
                Pig mob = new Pig(EntityTypes.PIG, level);
                mob.setPos(i % 32, 100, i / 32);
                Set<ServerPlayer> seen = ConcurrentHashMap.newKeySet();
                watchers.add(seen);
                var tracker = tracker(level, mob, seen);
                mobTrackers.add(tracker);
                index.add(tracker, players);
            }
            for (int i = 0; i < 202; i++) tick(index, players, level);
            check(watchers.stream().allMatch(seen -> seen.size() == 8), "staging migration lost watchers");
            players.getFirst().setPos(10000, 100, 0);
            tick(index, players, level);
            check(watchers.stream().allMatch(seen -> seen.size() == 7 && !seen.contains(players.getFirst())), "leave-range diff failed");
            players.getFirst().setPos(0, 100, 0);
            tick(index, players, level);
            check(watchers.stream().allMatch(seen -> seen.size() == 8), "return-range diff failed");

            // Grow player rows, reorder slots by removing a tracker, then reuse the buffers.
            ServerPlayer newcomer = new ServerPlayer(level.getServer(), level,
                    new GameProfile(UUID.randomUUID(), "NewTracker"), ClientInformation.createDefault());
            newcomer.setPos(0, 100, 0);
            players.add(newcomer);
            index.add(tracker(level, newcomer, ConcurrentHashMap.newKeySet()), players);
            tick(index, players, level);
            check(watchers.stream().allMatch(seen -> seen.size() == 9), "joining player lost tracking actions");
            ServerPlayer leaving = players.removeFirst();
            index.remove(playerTrackers.getFirst());
            index.remove(mobTrackers.getFirst());
            Set<ServerPlayer> removed = watchers.removeFirst();
            removed.clear();
            for (int i = 0; i < 3; i++) tick(index, players, level);
            check(removed.isEmpty(), "removed tracker received stale actions");
            check(watchers.stream().allMatch(seen -> seen.size() == 8 && !seen.contains(leaving)
                    && seen.contains(newcomer)), "player removal or slot reuse corrupted actions");
            System.out.println("TRACKING_PREPARATION_CHECKS_PASSED players=8 mobs=160");
        } finally {
            Vanadium.scheduler = previous;
        }
    }

    private static void tick(NearbyTrackers index, List<ServerPlayer> players, ServerLevel level) {
        Vanadium.scheduler.begin(Stage.TRACKING);
        index.tick(players, level.getChunkSource().chunkMap.getDistanceManager());
        Vanadium.scheduler.run(Stage.TRACKING);
    }

    private static ChunkMap.TrackedEntity tracker(ServerLevel level, Entity entity, Set<ServerPlayer> watchers) {
        return level.getChunkSource().chunkMap.new TrackedEntity(entity, 64, 3, true) {
            private final AtomicBoolean updating = new AtomicBoolean();
            @Override public void updatePlayer(ServerPlayer player) {
                check(updating.compareAndSet(false, true), "concurrent writes to one tracker");
                try {
                    if (entity.distanceToSqr(player) <= 64 * 64) watchers.add(player);
                    else watchers.remove(player);
                } finally { updating.set(false); }
            }
            @Override public void removePlayer(ServerPlayer player) {
                check(updating.compareAndSet(false, true), "concurrent remove/update on one tracker");
                try { watchers.remove(player); }
                finally { updating.set(false); }
            }
            @Override public void sendToTrackingPlayersAndSelf(Packet<? super ClientGamePacketListener> packet) {}
        };
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
