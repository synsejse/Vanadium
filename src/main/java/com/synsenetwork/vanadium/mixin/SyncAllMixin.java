package com.synsenetwork.vanadium.mixin;

import net.minecraft.advancement.criterion.AbstractCriterion;
import net.minecraft.entity.ai.WardenAngerManager;
import net.minecraft.entity.ai.pathing.EntityNavigation;
import net.minecraft.entity.ai.pathing.PathMinHeap;
import net.minecraft.entity.ai.pathing.PathNodeTypeCache;
import net.minecraft.util.math.random.CheckedRandom;
import net.minecraft.world.border.WorldBorder;
import net.minecraft.world.chunk.light.LevelPropagator;
import net.minecraft.world.event.listener.SimpleGameEventDispatcher;
import net.minecraft.world.SpawnHelper;
import net.minecraft.world.poi.PointOfInterest;
import net.minecraft.world.poi.PointOfInterestSet;
import net.minecraft.world.tick.ChunkTickScheduler;
import net.minecraft.world.tick.WorldTickScheduler;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(value = {PathMinHeap.class, ChunkTickScheduler.class, LevelPropagator.class, EntityNavigation.class,
        SimpleGameEventDispatcher.class, CheckedRandom.class, WardenAngerManager.class, AbstractCriterion.class, WorldBorder.class, WorldTickScheduler.class,
        PathNodeTypeCache.class, PointOfInterest.class, PointOfInterestSet.class, SpawnHelper.Info.class})
public class SyncAllMixin {
}
