package com.synsenetwork.vanadium.mixin.server.world;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(ChunkHolder.class)
public abstract class ChunkHolderMixin {

    // Keep vanilla's local and stored set references identical. The holder monitor protects
    // section-set creation, additions, and clearing together, including the broadcast flags.
    @WrapMethod(method = "blockChanged")
    private synchronized boolean blockChanged(BlockPos pos, Operation<Boolean> original) {
        return original.call(pos);
    }

    @WrapMethod(method = "sectionLightChanged")
    private synchronized boolean sectionLightChanged(LightLayer layer, int sectionY, Operation<Boolean> original) {
        return original.call(layer, sectionY);
    }

    @WrapMethod(method = "hasChangesToBroadcast")
    private synchronized boolean hasChangesToBroadcast(Operation<Boolean> original) {
        return original.call();
    }

    @WrapMethod(method = "broadcastChanges")
    private synchronized void broadcastChanges(LevelChunk chunk, Operation<Void> original) {
        original.call(chunk);
    }
}
