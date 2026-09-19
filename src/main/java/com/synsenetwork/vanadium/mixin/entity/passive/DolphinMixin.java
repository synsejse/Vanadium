package com.synsenetwork.vanadium.mixin.entity.passive;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import java.util.concurrent.locks.ReentrantLock;
import com.synsenetwork.vanadium.concurrent.ItemLockAccess;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.animal.dolphin.Dolphin;
import net.minecraft.world.entity.item.ItemEntity;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(Dolphin.class)
public abstract class DolphinMixin {


    @WrapMethod(method = "pickUpItem")
    private void loot(ServerLevel level, ItemEntity itemEntity, Operation<Void> original) {
        ReentrantLock lock = ((ItemLockAccess) itemEntity).vanadium$itemLock();
        lock.lock();
        try {
            if (!itemEntity.isRemoved())
                original.call(level, itemEntity);
        } finally {
            lock.unlock();
        }
    }
}
