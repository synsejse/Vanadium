package com.synsenetwork.vanadium.mixin.entity.raid;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import java.util.concurrent.locks.ReentrantLock;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.raid.Raider;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin(Raider.class)
public class RaiderMixin {
    @Unique
    private static final ReentrantLock lock = new ReentrantLock();

    @WrapMethod(method = "pickUpItem")
    private void loot(ServerLevel level, ItemEntity itemEntity, Operation<Void> original) {
        lock.lock();
        try {
            if (!itemEntity.isRemoved())
                original.call(level, itemEntity);
        } finally {
            lock.unlock();
        }
    }
}
