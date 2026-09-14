package com.synsenetwork.vanadium.mixin.server.world;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.synsenetwork.vanadium.Vanadium;
import com.synsenetwork.vanadium.concurrent.ConcurrentLongLinkedOpenHashSet;
import com.synsenetwork.vanadium.concurrent.Int2ObjectConcurrentHashMap;
import com.synsenetwork.vanadium.tick.Stage;
import com.synsenetwork.vanadium.tracking.NearbyTrackers;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.objects.ObjectCollection;
import it.unimi.dsi.fastutil.objects.ObjectLists;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import net.minecraft.server.level.ChunkGenerationTask;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.DistanceManager;
import net.minecraft.server.level.PlayerMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ChunkMap.class)
public abstract class ChunkMapMixin {


    @Shadow
    @Final
    @Mutable
    private Int2ObjectMap<ChunkMap.TrackedEntity> entityMap = new Int2ObjectConcurrentHashMap<>();


    @Shadow
    @Final
    @Mutable
    private List<ChunkGenerationTask> pendingGenerationTasks = new CopyOnWriteArrayList<>();

    @Shadow
    @Final
    @Mutable
    final LongSet toDrop = new ConcurrentLongLinkedOpenHashSet();

    @Shadow
    @Final
    ServerLevel level;

    @Shadow
    @Final
    private PlayerMap playerMap;

    @Shadow
    public abstract DistanceManager getDistanceManager();

    @Shadow
    private void updateChunkTracking(ServerPlayer player) {
        throw new AssertionError();
    }

    @Unique
    private final NearbyTrackers vanadium$nearbyTrackers = new NearbyTrackers();

    @WrapMethod(method = "addEntity")
    private synchronized void loadEntity(Entity entity, Operation<Void> original) {
        original.call(entity);
    }

    @WrapMethod(method = "removeEntity")
    private synchronized void unloadEntity(Entity entity, Operation<Void> original) {
        original.call(entity);
    }

    /**
     * Runs entity tracking as a parallel TRACKING wave driven by the {@link NearbyTrackers}
     * area-map index instead of vanilla's O(entities x players) scans. The serial phase
     * (index maintenance, per-player diff) runs here on the server thread; the per-tracker
     * work it produces executes as cell tasks, keyed by the tracked entity's chunk so each
     * tracker's listener set and entry are touched by one worker only. Chunk work has
     * already completed before this phase, and tracking finishes before entity ticking.
     */
    @Inject(method = "tick()V", at = @At("HEAD"), cancellable = true)
    private void vanadium$parallelTracking(CallbackInfo ci) {
        if (!Vanadium.config.enabled || !Vanadium.config.parallelTracking) {
            return;
        }
        Vanadium.scheduler.begin(Stage.TRACKING);
        for (ServerPlayer player : this.playerMap.getAllPlayers()) {
            this.updateChunkTracking(player);
        }
        this.vanadium$nearbyTrackers.tick(this.level.players(), this.getDistanceManager());
        Vanadium.scheduler.run(Stage.TRACKING);
        ci.cancel();
    }

    /**
     * Registers new trackers with the index. Runs in both config modes: add() performs the
     * same initial per-player visibility pass vanilla did here, and always-on registration
     * keeps the index warm so parallelTracking can be toggled live.
     */
    @Redirect(method = "addEntity", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/level/ChunkMap$TrackedEntity;updatePlayers(Ljava/util/List;)V"))
    private void vanadium$registerTracker(ChunkMap.TrackedEntity tracker, List<ServerPlayer> players) {
        this.vanadium$nearbyTrackers.add(tracker, players);
    }

    @Redirect(method = "removeEntity", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/level/ChunkMap$TrackedEntity;broadcastRemoved()V"))
    private void vanadium$unregisterTracker(ChunkMap.TrackedEntity tracker) {
        this.vanadium$nearbyTrackers.remove(tracker);
        tracker.broadcastRemoved();
    }

    /**
     * Kills the full-tracker scans on player join/leave/move when the index is live — the
     * per-tick diff (and add()'s load-time pass) covers them. Vanilla behavior when off.
     */
    @Redirect(method = {"addEntity", "removeEntity", "move"}, at = @At(value = "INVOKE",
            target = "Lit/unimi/dsi/fastutil/ints/Int2ObjectMap;values()Lit/unimi/dsi/fastutil/objects/ObjectCollection;"))
    private ObjectCollection<ChunkMap.TrackedEntity> vanadium$skipFullScans(
            Int2ObjectMap<ChunkMap.TrackedEntity> instance) {
        if (Vanadium.config.enabled && Vanadium.config.parallelTracking) {
            return ObjectLists.emptyList();
        }
        return instance.values();
    }

}
