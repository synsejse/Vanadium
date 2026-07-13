package com.synsenetwork.vanadium.mixin.server.world;

import net.minecraft.entity.Entity;
import net.minecraft.server.network.EntityTrackerEntry;
import net.minecraft.server.network.PlayerAssociatedNetworkHandler;
import net.minecraft.server.world.ServerChunkLoadingManager;
import net.minecraft.util.math.ChunkSectionPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

import java.util.Set;

@Mixin(ServerChunkLoadingManager.EntityTracker.class)
public interface EntityTrackerAccessor {
    @Accessor("entity")
    Entity vanadium$entity();

    @Accessor("entry")
    EntityTrackerEntry vanadium$entry();

    @Accessor("trackedSection")
    ChunkSectionPos vanadium$trackedSection();

    @Accessor("trackedSection")
    void vanadium$setTrackedSection(ChunkSectionPos section);

    @Accessor("listeners")
    Set<PlayerAssociatedNetworkHandler> vanadium$listeners();

    @Invoker("getMaxTrackDistance")
    int vanadium$maxTrackDistance();
}
