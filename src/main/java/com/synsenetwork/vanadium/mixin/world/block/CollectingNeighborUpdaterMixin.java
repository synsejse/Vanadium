package com.synsenetwork.vanadium.mixin.world.block;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.redstone.CollectingNeighborUpdater;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(CollectingNeighborUpdater.class)
public abstract class CollectingNeighborUpdaterMixin {

    @WrapMethod(method = "addAndRun")
    private synchronized void syncEnqueue(BlockPos pos, CollectingNeighborUpdater.NeighborUpdates entry, Operation<Void> original) {
        original.call(pos, entry);
    }

}
