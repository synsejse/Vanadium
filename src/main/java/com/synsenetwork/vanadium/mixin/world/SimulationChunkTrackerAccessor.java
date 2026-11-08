package com.synsenetwork.vanadium.mixin.world;

import it.unimi.dsi.fastutil.longs.Long2ByteMap;
import net.minecraft.server.level.SimulationChunkTracker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(SimulationChunkTracker.class)
public interface SimulationChunkTrackerAccessor {
    @Accessor("chunks")
    Long2ByteMap vanadium$chunks();
}
