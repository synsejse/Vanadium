package com.synsenetwork.vanadium.mixin.world.chunk;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.block.entity.BlockEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.chunk.Chunk;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;

import java.util.Map;

@Mixin(Chunk.class)
public abstract class ChunkMixin {

    @Shadow
    @Final
    @Mutable
    protected Map<BlockPos, BlockEntity> blockEntities =  new ConcurrentHashMap<>();
}
