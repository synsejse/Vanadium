package com.synsenetwork.vanadium.mixin.entity.passive;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import java.util.concurrent.locks.ReentrantLock;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.animal.fox.Fox;
import net.minecraft.world.entity.item.ItemEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin(Fox.class)
public class FoxMixin {
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
