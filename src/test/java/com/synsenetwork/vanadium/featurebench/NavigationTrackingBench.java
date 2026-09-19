package com.synsenetwork.vanadium.featurebench;

import com.mojang.authlib.GameProfile;
import com.synsenetwork.vanadium.Vanadium;
import com.synsenetwork.vanadium.tick.Stage;
import com.synsenetwork.vanadium.tracking.NavigationAccess;
import com.synsenetwork.vanadium.tracking.NavigationIndex;
import com.synsenetwork.vanadium.tracking.NearbyTrackers;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.entity.animal.pig.Pig;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.Path;

final class NavigationTrackingBench {
    static void navigation(ServerLevel level, Measurements measurements) throws Exception {
        NavigationIndex index = ((NavigationAccess) level).vanadium$navigations();
        Field pathField = PathNavigation.class.getDeclaredField("path");
        pathField.setAccessible(true);
        for (boolean dense : new boolean[]{false, true}) {
            List<Mob> mobs = new ArrayList<>();
            try {
                for (int i = 0; i < 1024; i++) {
                    Pig mob = new Pig(EntityTypes.PIG, level);
                    int x = dense ? 0 : (i % 32 - 16) * 128;
                    int z = dense ? 0 : (i / 32 - 16) * 128;
                    mob.setPos(x, 100, z);
                    List<Node> nodes = new ArrayList<>();
                    for (int j = 0; j < 32; j++) nodes.add(new Node(x + j, 100, z));
                    pathField.set(mob.getNavigation(), new Path(nodes, new BlockPos(x + 31, 100, z), true));
                    mobs.add(mob);
                    index.add(mob);
                }
                index.refresh();
                for (int edits : new int[]{1, 64}) {
                    for (boolean moving : new boolean[]{false, true}) {
                        if (dense && (moving || edits == 1)) continue;
                        boolean[] enabled = {true};
                        measurements.compare("navigation-" + (dense ? "dense" : "spread") + "-edits-" + edits
                                + "-" + (moving ? "dirty" : "static"), on -> enabled[0] = on, () -> {
                                    // Same invalidation hooks remain installed in both modes, as with enabled=false.
                                    if (moving) for (Mob mob : mobs) NavigationIndex.invalidate(mob);
                                    if (enabled[0]) index.refresh();
                                    int matches = 0;
                                    for (int i = 0; i < edits; i++) {
                                        BlockPos pos = mobs.get(i * 13 % mobs.size()).blockPosition();
                                        Iterable<Mob> candidates = enabled[0] ? index.candidates(pos) : index;
                                        for (Mob mob : candidates) if (mob.getNavigation().shouldRecomputePath(pos)) matches++;
                                    }
                                    FeatureBench.sink = matches;
                                });
                    }
                }
            } finally { for (Mob mob : mobs) index.remove(mob); }
        }
    }

    static void tracking(ServerLevel level, Measurements measurements, int count) throws Exception {
        NearbyTrackers index = new NearbyTrackers();
        List<ServerPlayer> players = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            var player = new ServerPlayer(level.getServer(), level,
                    new GameProfile(new UUID(0, i + 1), "Bench" + i), ClientInformation.createDefault());
            player.setPos(0, 100, 0);
            players.add(player);
        }
        for (ServerPlayer player : players) index.add(tracker(level, player), players);
        List<Entity> mobs = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            Pig mob = new Pig(EntityTypes.PIG, level);
            mob.setPos(i % 64 - 32, 100, i / 64 % 64 - 32);
            mobs.add(mob);
            index.add(tracker(level, mob), players);
        }
        Runnable tick = () -> {
            Vanadium.scheduler.begin(Stage.TRACKING);
            index.tick(players, level.getChunkSource().chunkMap.getDistanceManager());
            Vanadium.scheduler.run(Stage.TRACKING);
        };
        for (int i = 0; i < 202; i++) tick.run();
        long[] frame = {0};
        measurements.compare("tracking-8-players-" + count + "-mobs",
                on -> FeatureBench.parallelTrackingPreparation = on, () -> {
                    double x = (++frame[0] & 1) == 0 ? 0 : 0.25;
                    for (ServerPlayer player : players) player.setPos(x, 100, 0);
                    for (Entity mob : mobs) mob.needsSync = true;
                    tick.run();
                });
    }

    private static ChunkMap.TrackedEntity tracker(ServerLevel level, Entity entity) {
        return level.getChunkSource().chunkMap.new TrackedEntity(entity, 64, 3, true) {
            @Override public void updatePlayer(ServerPlayer player) {}
            @Override public void removePlayer(ServerPlayer player) {}
            @Override public void sendToTrackingPlayersAndSelf(Packet<? super ClientGamePacketListener> packet) {}
        };
    }
}
