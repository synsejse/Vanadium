package com.synsenetwork.vanadium.mixin.world;

import com.synsenetwork.vanadium.collections.ConcurrentLongByteMap;
import it.unimi.dsi.fastutil.longs.Long2ByteMap;
import net.minecraft.server.level.SimulationChunkTracker;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(SimulationChunkTracker.class)
public abstract class SimulationChunkTrackerMixin {
    /**
     * A shadow-field initializer, not a {@code <init>} RETURN install: the constructor sets this map's default
     * return value (C2ME changes it) after vanilla's own initializer, and Mixin places this one right after vanilla's,
     * so the setting reaches the replacement (checked in a mixin export, 2026-11-20). Versioned: {@code ChunkStage}
     * caches the ticking set.
     */
    @Shadow
    @Final
    @Mutable
    protected Long2ByteMap chunks = new ConcurrentLongByteMap(true);
}
