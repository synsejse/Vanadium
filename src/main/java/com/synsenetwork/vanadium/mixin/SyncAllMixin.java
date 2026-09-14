package com.synsenetwork.vanadium.mixin;

import net.minecraft.advancements.triggers.SimpleCriterionTrigger;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.entity.ai.village.poi.PoiRecord;
import net.minecraft.world.entity.ai.village.poi.PoiSection;
import net.minecraft.world.entity.monster.warden.AngerManagement;
import net.minecraft.world.level.NaturalSpawner;
import net.minecraft.world.level.TicketStorage;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.level.gameevent.EuclideanGameEventListenerRegistry;
import net.minecraft.world.level.levelgen.LegacyRandomSource;
import net.minecraft.world.level.lighting.DynamicGraphMinFixedPoint;
import net.minecraft.world.level.pathfinder.BinaryHeap;
import net.minecraft.world.level.pathfinder.PathTypeCache;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import net.minecraft.world.ticks.LevelChunkTicks;
import net.minecraft.world.ticks.LevelTicks;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(value = {BinaryHeap.class, LevelChunkTicks.class, DynamicGraphMinFixedPoint.class, PathNavigation.class,
        EuclideanGameEventListenerRegistry.class, LegacyRandomSource.class, AngerManagement.class, SimpleCriterionTrigger.class, WorldBorder.class, LevelTicks.class,
        PathTypeCache.class, PoiRecord.class, PoiSection.class, NaturalSpawner.SpawnState.class,
        MapItemSavedData.class, TicketStorage.class})
public class SyncAllMixin {
}
