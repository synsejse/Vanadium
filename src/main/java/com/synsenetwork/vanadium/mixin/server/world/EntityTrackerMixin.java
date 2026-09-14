package com.synsenetwork.vanadium.mixin.server.world;

import com.synsenetwork.vanadium.Vanadium;
import com.synsenetwork.vanadium.tracking.NearbyTrackers;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Set;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerEntity;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerPlayerConnection;

@Mixin(ChunkMap.TrackedEntity.class)
public abstract class EntityTrackerMixin {

    @Shadow
    @Final
    private Set<ServerPlayerConnection> seenBy;

    @Shadow
    @Final
    ServerEntity serverEntity;

    /**
     * Under the area-map index, trackers with no watchers are not ticked, so their entry's
     * position/velocity/data baseline goes stale. Fires just before the first listener is
     * added — while the listener set is still empty, so the forced tick sends nothing — to
     * re-baseline the entry for the new watcher's spawn packet and subsequent deltas.
     */
    @Inject(method = "updatePlayer(Lnet/minecraft/server/level/ServerPlayer;)V",
            at = @At(value = "INVOKE", target = "Ljava/util/Set;add(Ljava/lang/Object;)Z"))
    private void vanadium$resyncDormantEntry(ServerPlayer player, CallbackInfo ci) {
        if (Vanadium.config.enabled && Vanadium.config.parallelTracking && this.seenBy.isEmpty()) {
            NearbyTrackers.forceResync(this.serverEntity);
        }
    }
}
