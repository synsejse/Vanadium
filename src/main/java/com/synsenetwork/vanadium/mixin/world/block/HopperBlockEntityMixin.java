package com.synsenetwork.vanadium.mixin.world.block;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.synsenetwork.vanadium.concurrent.ItemLockAccess;
import java.util.concurrent.locks.ReentrantLock;
import net.minecraft.world.Container;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
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
}
