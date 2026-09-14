package com.synsenetwork.vanadium.mixin.entity;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

import java.util.concurrent.locks.ReentrantLock;
import net.minecraft.world.entity.item.ItemEntity;

@Mixin(ItemEntity.class)
public class ItemEntityMixin {
    @Unique
    private static final ReentrantLock lock = new ReentrantLock();

    @WrapMethod(method = "mergeWithNeighbours()V")
    private void tryMerge(Operation<Void> original) {
        lock.lock();
        try {
            original.call();
        } finally {
            lock.unlock();
        }
    }
}
