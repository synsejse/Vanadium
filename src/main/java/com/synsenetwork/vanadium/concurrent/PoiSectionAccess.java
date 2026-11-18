package com.synsenetwork.vanadium.concurrent;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.ai.village.poi.PoiRecord;

/** A POI section's record map; implemented by {@code PoiSectionMixin}. */
public interface PoiSectionAccess {
    /** The record this section holds at {@code pos}, or null. The caller holds the section's monitor. */
    PoiRecord vanadium$recordAt(BlockPos pos);
}
