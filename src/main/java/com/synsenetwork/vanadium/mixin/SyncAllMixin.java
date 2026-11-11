package com.synsenetwork.vanadium.mixin;

import net.caffeinemc.mods.lithium.common.util.deduplication.LithiumInterner;
import net.caffeinemc.mods.lithium.common.world.listeners.WorldBorderPositionListenerMulti;
import net.minecraft.advancements.triggers.SimpleCriterionTrigger;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.entity.ai.village.poi.PoiRecord;
import net.minecraft.world.entity.ai.village.poi.PoiSection;
import net.minecraft.world.entity.monster.warden.AngerManagement;
import net.minecraft.world.level.NaturalSpawner;
import net.minecraft.world.level.TicketStorage;
import net.minecraft.world.level.gameevent.EuclideanGameEventListenerRegistry;
import net.minecraft.world.level.levelgen.LegacyRandomSource;
import net.minecraft.world.level.lighting.DynamicGraphMinFixedPoint;
import net.minecraft.world.level.pathfinder.BinaryHeap;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import net.minecraft.world.ticks.LevelChunkTicks;
import net.minecraft.world.ticks.LevelTicks;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Lithium's tracker interner and world-border listener registry are filled from block-entity and hopper
 * ticks on any worker (every ticker re-registers with the border after it changes); both are rarely written.
 */
@Mixin(value = {BinaryHeap.class, LevelChunkTicks.class, DynamicGraphMinFixedPoint.class, PathNavigation.class,
        EuclideanGameEventListenerRegistry.class, LegacyRandomSource.class, AngerManagement.class, SimpleCriterionTrigger.class, LevelTicks.class,
        PoiRecord.class, PoiSection.class, NaturalSpawner.SpawnState.class,
        MapItemSavedData.class, TicketStorage.class, LithiumInterner.class, WorldBorderPositionListenerMulti.class})
public class SyncAllMixin {
}
