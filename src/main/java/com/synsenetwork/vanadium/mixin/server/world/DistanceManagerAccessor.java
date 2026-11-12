package com.synsenetwork.vanadium.mixin.server.world;

import net.minecraft.server.level.DistanceManager;
import net.minecraft.server.level.SimulationChunkTracker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(DistanceManager.class)
public interface DistanceManagerAccessor {
    @Accessor("simulationChunkTracker")
    SimulationChunkTracker vanadium$simulationChunkTracker();
}
