package com.synsenetwork.vanadium.mixin.server.world;

import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerEntity;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(ChunkMap.TrackedEntity.class)
public interface TrackedEntityAccessor {
    @Accessor("entity")
    Entity vanadium$entity();

    @Accessor("serverEntity")
    ServerEntity vanadium$entry();

    @Accessor("lastSectionPos")
    SectionPos vanadium$trackedSection();

    @Accessor("lastSectionPos")
    void vanadium$setTrackedSection(SectionPos section);

    @Invoker("getEffectiveRange")
    int vanadium$maxTrackDistance();
}
