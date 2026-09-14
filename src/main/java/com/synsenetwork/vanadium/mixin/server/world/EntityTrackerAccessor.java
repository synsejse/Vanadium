package com.synsenetwork.vanadium.mixin.server.world;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

import java.util.Set;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerEntity;
import net.minecraft.server.network.ServerPlayerConnection;
import net.minecraft.world.entity.Entity;

@Mixin(ChunkMap.TrackedEntity.class)
public interface EntityTrackerAccessor {
    @Accessor("entity")
    Entity vanadium$entity();

    @Accessor("serverEntity")
    ServerEntity vanadium$entry();

    @Accessor("lastSectionPos")
    SectionPos vanadium$trackedSection();

    @Accessor("lastSectionPos")
    void vanadium$setTrackedSection(SectionPos section);

    @Accessor("seenBy")
    Set<ServerPlayerConnection> vanadium$listeners();

    @Invoker("getEffectiveRange")
    int vanadium$maxTrackDistance();
}
