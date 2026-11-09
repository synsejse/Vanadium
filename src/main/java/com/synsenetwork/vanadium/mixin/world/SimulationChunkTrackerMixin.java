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
    @Shadow
    @Final
    @Mutable
    protected Long2ByteMap chunks = new ConcurrentLongByteMap();

}
