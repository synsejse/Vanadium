package com.synsenetwork.vanadium.mixin.world;

import it.unimi.dsi.fastutil.longs.Long2ByteMap;
import net.minecraft.server.level.ChunkTracker;
import net.minecraft.server.level.SimulationChunkTracker;
import com.synsenetwork.vanadium.concurrent.Long2ByteConcurrentHashMap;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(SimulationChunkTracker.class)
public abstract class SimulationDistanceLevelPropagatorMixin extends ChunkTracker {
    @Shadow
    @Final
    @Mutable
    protected Long2ByteMap chunks = new Long2ByteConcurrentHashMap();


    protected SimulationDistanceLevelPropagatorMixin(int i, int j, int k) {
        super(i, j, k);
    }
}
