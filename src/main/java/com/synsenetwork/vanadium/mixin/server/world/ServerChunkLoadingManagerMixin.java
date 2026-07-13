package com.synsenetwork.vanadium.mixin.server.world;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.datafixers.DataFixer;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.objects.ObjectCollection;
import it.unimi.dsi.fastutil.objects.ObjectLists;
import com.synsenetwork.vanadium.Vanadium;
import com.synsenetwork.vanadium.concurrent.ConcurrentLongLinkedOpenHashSet;
import com.synsenetwork.vanadium.concurrent.Int2ObjectConcurrentHashMap;
import com.synsenetwork.vanadium.tick.Stage;
import com.synsenetwork.vanadium.tracking.NearbyTrackers;
import net.minecraft.entity.Entity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ChunkHolder;
import net.minecraft.server.world.ChunkTicketManager;
import net.minecraft.server.world.PlayerChunkWatchingManager;
import net.minecraft.server.world.ServerChunkLoadingManager;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.world.ChunkLoadingManager;
import net.minecraft.world.chunk.ChunkLoader;
import net.minecraft.world.storage.StorageKey;
import net.minecraft.world.storage.VersionedChunkStorage;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

@Mixin(ServerChunkLoadingManager.class)
public abstract class ServerChunkLoadingManagerMixin extends VersionedChunkStorage implements ChunkHolder.PlayersWatchingChunkProvider, ChunkLoadingManager {

    public ServerChunkLoadingManagerMixin(StorageKey storageKey, Path directory, DataFixer dataFixer, boolean dsync) {
        super(storageKey, directory, dataFixer, dsync);
    }

    @Shadow
    @Final
    @Mutable
    private Int2ObjectMap<ServerChunkLoadingManager.EntityTracker> entityTrackers = new Int2ObjectConcurrentHashMap<>();


    @Shadow
    @Final
    @Mutable
    private List<ChunkLoader> loaders = new CopyOnWriteArrayList<>();

    @Shadow
    @Final
    @Mutable
    final LongSet unloadedChunks = new ConcurrentLongLinkedOpenHashSet();

    @Shadow
    @Final
    ServerWorld world;

    @Shadow
    @Final
    private PlayerChunkWatchingManager playerChunkWatchingManager;

    @Shadow
    public abstract ChunkTicketManager getTicketManager();

    @Shadow
    private void sendWatchPackets(ServerPlayerEntity player) {
        throw new AssertionError();
    }

    @Unique
    private final NearbyTrackers vanadium$nearbyTrackers = new NearbyTrackers();

    @WrapMethod(method = "loadEntity")
    private synchronized void loadEntity(Entity entity, Operation<Void> original) {
        original.call(entity);
    }

    @WrapMethod(method = "unloadEntity")
    private synchronized void unloadEntity(Entity entity, Operation<Void> original) {
        original.call(entity);
    }

    /**
     * Runs entity tracking as a parallel TRACKING wave driven by the {@link NearbyTrackers}
     * area-map index instead of vanilla's O(entities x players) scans. The serial phase
     * (index maintenance, per-player diff) runs here on the server thread; the per-tracker
     * work it produces executes as cell tasks, keyed by the tracked entity's chunk so each
     * tracker's listener set and entry are touched by one worker only. The wave runs inside
     * the window where CHUNK tasks are queued but not yet running, so stages never overlap.
     */
    @Inject(method = "tickEntityMovement", at = @At("HEAD"), cancellable = true)
    private void vanadium$parallelTracking(CallbackInfo ci) {
        if (!Vanadium.config.enabled || !Vanadium.config.parallelTracking) {
            return;
        }
        Vanadium.scheduler.begin(Stage.TRACKING);
        for (ServerPlayerEntity player : this.playerChunkWatchingManager.getPlayersWatchingChunk()) {
            this.sendWatchPackets(player);
        }
        this.vanadium$nearbyTrackers.tick(this.world.getPlayers(), this.getTicketManager());
        Vanadium.scheduler.run(Stage.TRACKING);
        ci.cancel();
    }

    /**
     * Registers new trackers with the index. Runs in both config modes: add() performs the
     * same initial per-player visibility pass vanilla did here, and always-on registration
     * keeps the index warm so parallelTracking can be toggled live.
     */
    @Redirect(method = "loadEntity", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/world/ServerChunkLoadingManager$EntityTracker;updateTrackedStatus(Ljava/util/List;)V"))
    private void vanadium$registerTracker(ServerChunkLoadingManager.EntityTracker tracker, List<ServerPlayerEntity> players) {
        this.vanadium$nearbyTrackers.add(tracker, players);
    }

    @Redirect(method = "unloadEntity", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/world/ServerChunkLoadingManager$EntityTracker;stopTracking()V"))
    private void vanadium$unregisterTracker(ServerChunkLoadingManager.EntityTracker tracker) {
        this.vanadium$nearbyTrackers.remove(tracker);
        tracker.stopTracking();
    }

    /**
     * Kills the full-tracker scans on player join/leave/move when the index is live — the
     * per-tick diff (and add()'s load-time pass) covers them. Vanilla behavior when off.
     */
    @Redirect(method = {"loadEntity", "unloadEntity", "updatePosition"}, at = @At(value = "INVOKE",
            target = "Lit/unimi/dsi/fastutil/ints/Int2ObjectMap;values()Lit/unimi/dsi/fastutil/objects/ObjectCollection;"))
    private ObjectCollection<ServerChunkLoadingManager.EntityTracker> vanadium$skipFullScans(
            Int2ObjectMap<ServerChunkLoadingManager.EntityTracker> instance) {
        if (Vanadium.config.enabled && Vanadium.config.parallelTracking) {
            return ObjectLists.emptyList();
        }
        return instance.values();
    }

}
