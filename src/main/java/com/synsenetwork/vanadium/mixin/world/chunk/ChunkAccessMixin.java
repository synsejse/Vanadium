package com.synsenetwork.vanadium.mixin.world.chunk;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.ChunkAccess;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ChunkAccess.class)
public abstract class ChunkAccessMixin {
    @Shadow
    @Final
    @Mutable
    protected Map<BlockPos, BlockEntity> blockEntities;

    // Runs before a subclass constructor (LevelChunk, ProtoChunk) adds any block entity.
    @Inject(method = "<init>", at = @At("RETURN"))
    private void installConcurrentBlockEntities(CallbackInfo ci) {
        blockEntities = new ConcurrentHashMap<>();
    }
}
