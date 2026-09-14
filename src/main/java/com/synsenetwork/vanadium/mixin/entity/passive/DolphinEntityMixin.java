package com.synsenetwork.vanadium.mixin.entity.passive;

import net.minecraft.server.level.ServerLevel;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

import java.util.concurrent.locks.ReentrantLock;
import net.minecraft.world.entity.animal.dolphin.Dolphin;
import net.minecraft.world.entity.item.ItemEntity;

@Mixin(Dolphin.class)
public abstract class DolphinEntityMixin {

    @Unique
    private static final ReentrantLock lock = new ReentrantLock();


    @WrapMethod(method = "pickUpItem")
    private void loot(ServerLevel level, ItemEntity itemEntity, Operation<Void> original) {
        lock.lock();
        try {
            if (!itemEntity.isRemoved() && itemEntity.level() != null)
                original.call(level, itemEntity);
        } finally {
            lock.unlock();
        }
    }
}
