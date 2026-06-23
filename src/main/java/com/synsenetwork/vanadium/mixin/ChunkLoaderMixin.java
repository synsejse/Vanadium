package com.synsenetwork.vanadium.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.world.chunk.ChunkLoader;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(ChunkLoader.class)
public class ChunkLoaderMixin {
    @WrapMethod(method = "dispose")
    private synchronized void syncDispose(Operation<Void> original) {
        original.call();
    }
}
