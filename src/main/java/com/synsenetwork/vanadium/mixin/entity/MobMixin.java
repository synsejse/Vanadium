package com.synsenetwork.vanadium.mixin.entity;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.synsenetwork.vanadium.concurrent.ItemLockAccess;
import java.util.concurrent.locks.ReentrantLock;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.item.ItemEntity;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(Mob.class)
public abstract class MobMixin {
    @WrapMethod(method = "pickUpItem")
    private void pickup(ServerLevel level, ItemEntity item, Operation<Void> original) {
        ReentrantLock lock = ((ItemLockAccess) item).vanadium$itemLock();
        lock.lock();
        try {
            if (!item.isRemoved()) original.call(level, item);
        } finally { lock.unlock(); }
    }
}
