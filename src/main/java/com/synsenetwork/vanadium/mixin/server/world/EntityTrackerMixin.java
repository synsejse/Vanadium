package com.synsenetwork.vanadium.mixin.server.world;

import com.synsenetwork.vanadium.Vanadium;
import com.synsenetwork.vanadium.tracking.NearbyTrackers;
import net.minecraft.server.network.EntityTrackerEntry;
import net.minecraft.server.network.PlayerAssociatedNetworkHandler;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerChunkLoadingManager;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Set;

@Mixin(ServerChunkLoadingManager.EntityTracker.class)
public abstract class EntityTrackerMixin {

    @Shadow
    @Final
    private Set<PlayerAssociatedNetworkHandler> listeners;

    @Shadow
    @Final
    EntityTrackerEntry entry;

    /**
     * Under the area-map index, trackers with no watchers are not ticked, so their entry's
     * position/velocity/data baseline goes stale. Fires just before the first listener is
     * added — while the listener set is still empty, so the forced tick sends nothing — to
     * re-baseline the entry for the new watcher's spawn packet and subsequent deltas.
     */
    @Inject(method = "updateTrackedStatus(Lnet/minecraft/server/network/ServerPlayerEntity;)V",
            at = @At(value = "INVOKE", target = "Ljava/util/Set;add(Ljava/lang/Object;)Z"))
    private void vanadium$resyncDormantEntry(ServerPlayerEntity player, CallbackInfo ci) {
        if (Vanadium.config.enabled && Vanadium.config.parallelTracking && this.listeners.isEmpty()) {
            NearbyTrackers.forceResync(this.entry);
        }
    }
}
