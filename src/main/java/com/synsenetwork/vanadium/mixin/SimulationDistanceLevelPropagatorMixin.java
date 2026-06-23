package com.synsenetwork.vanadium.mixin;

import it.unimi.dsi.fastutil.longs.Long2ByteMap;
import it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import com.synsenetwork.vanadium.concurrent.fastutil.Long2ByteConcurrentHashMap;
import com.synsenetwork.vanadium.concurrent.fastutil.Long2ObjectOpenConcurrentHashMap;
import net.minecraft.server.world.ChunkTicket;
import net.minecraft.util.collection.SortedArraySet;
import net.minecraft.world.ChunkPosDistanceLevelPropagator;
import net.minecraft.world.SimulationDistanceLevelPropagator;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(SimulationDistanceLevelPropagator.class)
public abstract class SimulationDistanceLevelPropagatorMixin extends ChunkPosDistanceLevelPropagator {
    @Shadow
    @Final
    @Mutable
    protected Long2ByteMap levels = new Long2ByteConcurrentHashMap();

    @Shadow
    @Final
    @Mutable
    private Long2ObjectOpenHashMap<SortedArraySet<ChunkTicket<?>>> tickets = new Long2ObjectOpenConcurrentHashMap<>();

    protected SimulationDistanceLevelPropagatorMixin(int i, int j, int k) {
        super(i, j, k);
    }
}
