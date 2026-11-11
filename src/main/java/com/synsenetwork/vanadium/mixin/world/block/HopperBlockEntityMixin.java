package com.synsenetwork.vanadium.mixin.world.block;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(HopperBlockEntity.class)
public abstract class HopperBlockEntityMixin {
    /**
     * An item lying in a hopper pushes itself into it from the item's own tick, and several
     * items do so in the same tick. Each insertion owns the hopper's inventory. (The hopper's own tick runs in
     * the block-entity stage, never beside these.)
     */
    @WrapMethod(method = "entityInside")
    private static void vanadium$oneItemAtATime(Level level, BlockPos pos, BlockState state, Entity entity,
            HopperBlockEntity hopper, Operation<Void> original) {
        synchronized (hopper) {
            original.call(level, pos, state, entity, hopper);
        }
    }
}
