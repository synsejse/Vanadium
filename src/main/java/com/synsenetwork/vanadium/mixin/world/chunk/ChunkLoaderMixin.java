package com.synsenetwork.vanadium.mixin.world.chunk;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.server.level.ChunkGenerationTask;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(ChunkGenerationTask.class)
public class ChunkLoaderMixin {
    @WrapMethod(method = "releaseClaim")
    private synchronized void syncDispose(Operation<Void> original) {
        original.call();
    }
}
