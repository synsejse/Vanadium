package com.synsenetwork.vanadium.mixin.world.poi;

import com.synsenetwork.vanadium.collections.ConcurrentLongByteMap;
import com.synsenetwork.vanadium.concurrent.PoiDistanceAccess;
import it.unimi.dsi.fastutil.longs.Long2ByteMap;
import net.minecraft.server.level.SectionTracker;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;

/** Village distance levels are read by every mob's village checks; reads must not need the monitor. */
@Mixin(targets = "net.minecraft.world.entity.ai.village.poi.PoiManager$DistanceTracker")
public abstract class PoiDistanceTrackerMixin extends SectionTracker implements PoiDistanceAccess {
    /**
     * A shadow-field initializer, not a {@code <init>} RETURN install: the constructor sets this map's default
     * return value after vanilla's own initializer, and Mixin places this one right after vanilla's, so the setting
     * reaches the replacement (checked in a mixin export, 2026-11-20).
     */
    @Shadow
    @Final
    @Mutable
    private Long2ByteMap levels = new ConcurrentLongByteMap();

    protected PoiDistanceTrackerMixin(int levelCount, int minQueueSize, int minMapSize) {
        super(levelCount, minQueueSize, minMapSize);
    }

    @Override
    public boolean vanadium$hasPendingUpdates() {
        return hasWork();
    }

    @Override
    public int vanadium$storedLevel(long sectionPos) {
        return levels.get(sectionPos);
    }

    @Override
    public int vanadium$levelFromSource(long sectionPos) {
        return getLevelFromSource(sectionPos);
    }
}
