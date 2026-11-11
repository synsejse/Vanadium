package com.synsenetwork.vanadium.mixin.world.block;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.synsenetwork.vanadium.concurrent.ItemLockAccess;
import java.util.concurrent.locks.ReentrantLock;
import net.minecraft.world.Container;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(HopperBlockEntity.class)
public abstract class HopperBlockEntityMixin {
    @WrapMethod(method = "addItem(Lnet/minecraft/world/Container;Lnet/minecraft/world/entity/item/ItemEntity;)Z")
    private static boolean collectItem(Container container, ItemEntity item, Operation<Boolean> original) {
        ReentrantLock lock = ((ItemLockAccess) item).vanadium$itemLock();
        lock.lock();
        try {
            return !item.isRemoved() && original.call(container, item);
        } finally { lock.unlock(); }
    }

    /**
     * Flat entity wave: an item lying in a hopper pushes itself into it from the item's own tick, and several
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
