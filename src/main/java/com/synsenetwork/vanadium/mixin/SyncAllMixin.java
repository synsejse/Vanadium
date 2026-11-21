package com.synsenetwork.vanadium.mixin;

import net.caffeinemc.mods.lithium.common.util.deduplication.LithiumInterner;
import net.caffeinemc.mods.lithium.common.world.listeners.WorldBorderPositionListenerMulti;
import net.minecraft.advancements.triggers.SimpleCriterionTrigger;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.entity.ai.village.poi.PoiSection;
import net.minecraft.world.entity.monster.warden.AngerManagement;
import net.minecraft.world.level.TicketStorage;
import net.minecraft.world.level.gameevent.EuclideanGameEventListenerRegistry;
import net.minecraft.world.level.levelgen.LegacyRandomSource;
import net.minecraft.world.level.lighting.DynamicGraphMinFixedPoint;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import net.minecraft.world.ticks.LevelChunkTicks;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Lithium's tracker interner and world-border listener registry are filled from block-entity and hopper
 * ticks on any worker (every ticker re-registers with the border after it changes); both are rarely written.
 * POI records are not listed: their tickets are atomic ({@code PoiRecordMixin}), and a record monitor taken
 * under a section's (and the reverse, through the section's dirty callback) deadlocked villagers.
 * {@code BinaryHeap} is not listed: {@code PathFinder.openSet} is private to {@code PathFinder} (already
 * synchronized by {@code PathFinderMixin}) and {@code EnderDragon} ticks serially.
 */
@Mixin(value = {LevelChunkTicks.class, DynamicGraphMinFixedPoint.class, PathNavigation.class,
        EuclideanGameEventListenerRegistry.class, LegacyRandomSource.class, AngerManagement.class, SimpleCriterionTrigger.class,
        PoiSection.class,
        MapItemSavedData.class, TicketStorage.class, LithiumInterner.class, WorldBorderPositionListenerMulti.class})
public class SyncAllMixin {
}
