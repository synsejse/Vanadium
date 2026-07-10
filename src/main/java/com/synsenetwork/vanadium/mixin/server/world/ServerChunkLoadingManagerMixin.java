package com.synsenetwork.vanadium.mixin.server.world;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.datafixers.DataFixer;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.longs.LongSet;
import com.synsenetwork.vanadium.Vanadium;
import com.synsenetwork.vanadium.concurrent.ConcurrentLongLinkedOpenHashSet;
import com.synsenetwork.vanadium.concurrent.Int2ObjectConcurrentHashMap;
import com.synsenetwork.vanadium.mixin.server.network.EntityTrackerEntryAccessor;
import com.synsenetwork.vanadium.tick.Stage;
import net.minecraft.entity.Entity;
import net.minecraft.server.network.EntityTrackerEntry;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ChunkHolder;
import net.minecraft.server.world.ServerChunkLoadingManager;
import net.minecraft.world.ChunkLoadingManager;
import net.minecraft.world.chunk.ChunkLoader;
import net.minecraft.world.storage.StorageKey;
import net.minecraft.world.storage.VersionedChunkStorage;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
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

    @WrapMethod(method = "loadEntity")
    private synchronized void loadEntity(Entity entity, Operation<Void> original) {
        original.call(entity);
    }

    @WrapMethod(method = "unloadEntity")
    private synchronized void unloadEntity(Entity entity, Operation<Void> original) {
        original.call(entity);
    }

    /**
     * Runs entity tracking as a parallel TRACKING wave. tickEntityMovement keeps its cheap parts
     * on the server thread (section-change detection, the moved-players list, trackedSection
     * updates) and the two expensive per-tracker calls — updateTrackedStatus and entry.tick() —
     * are enqueued into cells keyed by the tracked entity's chunk. Per-tracker order (status
     * update, then tick, then moved-player re-scan) is preserved by cell insertion order; packet
     * sends are safe from workers because ClientConnection dispatches onto the Netty event loop.
     * The wave runs at method tail, inside the window where CHUNK tasks are queued but not yet
     * running, so no two stages ever execute concurrently.
     */
    @Inject(method = "tickEntityMovement", at = @At("HEAD"))
    private void preTracking(CallbackInfo ci) {
        if (Vanadium.config.enabled && Vanadium.config.parallelTracking) {
            Vanadium.scheduler.begin(Stage.TRACKING);
        }
    }

    @Redirect(method = "tickEntityMovement", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/world/ServerChunkLoadingManager$EntityTracker;updateTrackedStatus(Ljava/util/List;)V"))
    private void overwriteUpdateTrackedStatus(ServerChunkLoadingManager.EntityTracker tracker, List<ServerPlayerEntity> players) {
        if (!Vanadium.config.enabled || !Vanadium.config.parallelTracking) {
            tracker.updateTrackedStatus(players);
            return;
        }
        Entity entity = ((EntityTrackerAccessor) (Object) tracker).vanadium$entity();
        Vanadium.scheduler.enqueue(Stage.TRACKING, entity.getChunkPos().x, entity.getChunkPos().z,
                () -> tracker.updateTrackedStatus(players));
    }

    @Redirect(method = "tickEntityMovement", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/network/EntityTrackerEntry;tick()V"))
    private void overwriteEntryTick(EntityTrackerEntry entry) {
        if (!Vanadium.config.enabled || !Vanadium.config.parallelTracking) {
            entry.tick();
            return;
        }
        Entity entity = ((EntityTrackerEntryAccessor) entry).vanadium$entity();
        Vanadium.scheduler.enqueue(Stage.TRACKING, entity.getChunkPos().x, entity.getChunkPos().z,
                entry::tick);
    }

    @Inject(method = "tickEntityMovement", at = @At("TAIL"))
    private void postTracking(CallbackInfo ci) {
        if (Vanadium.config.enabled && Vanadium.config.parallelTracking) {
            Vanadium.scheduler.run(Stage.TRACKING);
        }
    }

}